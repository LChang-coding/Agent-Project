package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.AgentEventContent;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationValidator;
import cn.bugstack.ai.domain.rag.service.RagInvocationEvidenceStore;
import cn.bugstack.ai.domain.rag.service.RagWorkflowEvidenceLineage;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeInvocationResultEntity;
import cn.bugstack.ai.domain.workflow.service.IWorkflowService;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.domain.workflow.service.WorkflowRunFinalizationService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 对话编排的核心：把一次「用户说了句话」变成一连串可审计、可取消、可恢复的动作。
 *
 * <p>解决什么问题：一次对话背后要做的事很多——校验 Agent 能不能用、建或复用会话、创建运行记录、
 * 把用户消息落库、装配上下文、调模型或跑工作流、收集输出、校验引用真实性、写助手消息、推进运行终态。
 * 这些动作的顺序不能乱：必须先把「用户发过这句话」这个事实落库，再去调模型。
 * 否则模型调用失败时，系统里会查不到用户到底说过什么。</p>
 *
 * <p>所属层次：领域层的领域服务（对话编排的唯一入口）。</p>
 *
 * <p>谁会调用它：trigger 层的 HTTP 控制器（同步对话、流式对话、建会话），
 * 以及独立的智能工作流运行时（复用单节点执行和运行收尾两个方法）。</p>
 *
 * <p>它向下调用什么：装配工厂取 Runner、会话领域落会话与消息、运行控制服务管状态与取消门禁、
 * 工作流服务编译 DAG、上下文服务推进历史快照、RAG 证据仓与引用校验器保证出处真实、
 * 两个线程池承载工作流的协调与节点并行。</p>
 *
 * <p>它不负责什么：不解析 HTTP 参数、不做 SSE 推送、不决定用户身份（身份必须由调用方以可信方式传入）、
 * 不实现上下文裁剪算法（那在上下文服务里）。</p>
 *
 * <p>两个会话概念要分清：平台自己的业务会话落库，是权限校验和历史的权威来源；
 * ADK 会话每次调用都新建一个隔离的，只服务这一次模型调用。这样设计是为了避免 ADK 的内存历史
 * 和数据库里的历史重复注入，让模型看到同一段话两遍。</p>
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    /**
     * 工作流 RAG 证据的血缘计算器。
     *
     * <p>解决一个具体问题：DAG 里可能有旁路节点，它们检索了资料但结果并没有影响最终答案。
     * 如果把这些证据也算进去，模型引用一个旁路节点检索到的文档就会被判为"合法引用"，
     * 而用户看到的答案里其实根本没用到它。这里只保留终点节点能追溯到的证据。</p>
     *
     * <p>做成静态常量是因为它无状态、纯计算，全进程共用一份即可。</p>
     */
    private static final RagWorkflowEvidenceLineage RAG_LINEAGE = new RagWorkflowEvidenceLineage();

    /**
     * 装配工厂，按 agentId 取启动期已经装配好的 Agent 运行体（含 Runner 和模型）。
     *
     * <p>取不到就说明启动装配没做或配置无效，对话会直接失败，不做降级。</p>
     */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /**
     * 静态 Agent 配置，用于给前端列可选的 Agent。
     *
     * <p>它只提供"有哪些 Agent"这个候选集，不含租户维度，因此不能单独用它判断能不能用，
     * 必须再过一遍可用性服务。</p>
     */
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * 会话领域，负责会话和消息落库，以及"这个会话是不是这个用户的"这道校验。
     *
     * <p>它是访问控制的权威来源：所有读写会话数据的动作都要先过它的归属校验，
     * 否则换个 sessionId 就能读到别人的对话。</p>
     */
    @Resource
    private SessionDomain sessionDomain;

    /**
     * 工作流服务，把工作流定义按权限和版本解析成本次运行专用的不可变 DAG 快照。
     *
     * <p>为什么要冻结成快照：执行一次工作流可能要几十秒，期间别人可能发布了新版本。
     * 不冻结就会出现"前半段按旧版跑、后半段按新版跑"这种无法复现的情况。</p>
     */
    @Resource
    private IWorkflowService workflowService;

    /**
     * 工作流节点事件的发布服务，普通 DAG 和智能运行时共用它。
     *
     * <p>事件既落库形成账本，也实时广播给前端展示节点进度。
     * 落库是为了事后能还原"当时到底跑了哪些节点、各花了多久"。</p>
     */
    @Resource
    private WorkflowEventStreamService workflowEventStreamService;

    @Value("${ai.agent.thinking.visible:true}")
    private boolean thinkingVisible = true;

    @Value("${ai.agent.thinking.persist:true}")
    private boolean thinkingPersist = true;

    @Value("${ai.agent.thinking.max-chars:4000}")
    private int thinkingMaxChars = 4000;

    /**
     * 工作流运行的收尾服务，把最终消息和工作流终态事件放在同一个事务里写。
     *
     * <p>为什么必须原子：如果消息写成功但终态事件没写，前端会一直显示"运行中"，
     * 而数据库里已经有了完整答案，状态和内容对不上。</p>
     */
    @Resource
    private WorkflowRunFinalizationService workflowRunFinalizationService;

    /**
     * 上下文服务，消息落库后由它推进上下文快照和触发压缩任务。
     *
     * <p>压缩是异步的：历史太长时把旧消息总结成一段摘要，避免每轮都把全部历史喂给模型。</p>
     */
    @Resource
    private ConversationMemoryService conversationMemoryService;

    /**
     * 运行控制服务，管运行记录的创建、消息绑定、取消门禁和终态推进。
     *
     * <p>它是"取消"能真正生效的基础：取消状态写在数据库里，集群里任何一台机器都认，
     * 因此用户在 A 机器点停止，正在 B 机器上跑的流也会被掐断。</p>
     */
    @Resource
    private RunControlService runControlService;

    /**
     * Agent 可用性服务，合并静态配置和租户启停覆盖，决定公共 Agent 能不能调。
     *
     * <p>每次建会话和发消息前都要过它，保证被管理员关掉的 Agent 不会产生任何模型消费。</p>
     */
    @Resource
    private AgentAvailabilityService agentAvailabilityService;

    /** 会话内有未完成子任务或审批时，拒绝并发开启新的用户轮次。 */
    @Resource
    private SessionOrchestrationQueryService sessionOrchestrationQueryService;

    /** 主 Agent 委派子任务后，将自身输出暂存为草稿并等待全部结果统一汇总。 */
    @Resource
    private ParentWaitAllFinalizationService parentWaitAllFinalizationService;

    /**
     * RAG 证据暂存仓，记录每次模型调用真实注入了哪些知识库片段。
     *
     * <p>它是识别"模型编造出处"的唯一依据：回答生成后拿它比对模型引用的编号，
     * 引用了没检索到的东西就会被标为非法。运行结束或被取消时必须清空，
     * 否则残留证据会让后续回答引用到本次已经作废的资料。</p>
     */
    @Resource
    private RagInvocationEvidenceStore ragInvocationEvidenceStore;

    /**
     * 回答引用校验器，只允许最终回答引用本次调用真实注入过的证据。
     *
     * <p>校验在落库之前完成，非法引用会被记录进消息元数据，而不是原样冒充真实出处。</p>
     */
    @Resource
    private RagAnswerCitationValidator ragAnswerCitationValidator;

    /**
     * JSON 序列化器，用于把引用校验结果和工作流事件正文写成结构稳定的字符串。
     *
     * <p>元数据格式带 schema 版本号，这样以后格式升级时能区分新旧数据。</p>
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 工作流总协调线程池，一次工作流运行占用它一个线程。
     *
     * <p>为什么必须换线程：DAG 执行是阻塞的，可能跑几十秒。留在 HTTP 或 SSE 线程上会把
     * 容器的请求线程耗尽，其它用户连页面都打不开。</p>
     */
    @Resource(name = "workflowCoordinatorExecutor")
    private ExecutorService workflowCoordinatorExecutor;

    /**
     * 工作流节点执行线程池，同一拓扑层的多个节点提交到这里并行跑。
     *
     * <p>和协调线程池分开是为了避免互相饿死：协调线程要等节点结果，如果两者共用一个池，
     * 池满时协调线程会一直等一个永远拿不到线程的节点任务。</p>
     */
    @Resource(name = "workflowNodeExecutor")
    private ExecutorService workflowNodeExecutor;

    /**
     * 列出当前租户可见且可用的公共 Agent。
     *
     * <p>各层职责：
     * 第一层：取静态配置里的全部配置表，它提供"系统里有哪些 Agent"这个候选集。
     * 第二层：逐张表取出对外暴露的那个 Agent，跳过没写 Agent 的配置表。
     * 第三层：对每个候选再过一次租户启停判断，只有真正可用的才进结果。</p>
     *
     * <p>数据流：静态配置表 → 逐表取对外 Agent → 过租户可用性判断 → 返回可用列表。</p>
     *
     * <p>只读不写。工作流内部使用的运行时 Agent 不在静态配置里，因此天然不会出现在这个列表，
     * 用户也就没法拿它直接建会话绕过工作流授权。</p>
     */
    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        // 第一层：取出全部静态配置表；没有配置任何表时为空。
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        // 结果容器，只放本租户真正能用的 Agent。
        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        // 没有配置表时直接返回空列表，前端下拉框会是空的。
        if (null != tables) {
            // 第二层：逐张配置表检查。
            for (AiAgentConfigTableVO vo : tables.values()) {
                // 只有写了对外 Agent 的配置表才有候选项，跳过只写了模块的表。
                if (null != vo.getAgent()) {
                    // 静态配置只提供候选，最终仍由平台状态与租户覆盖共同裁决。
                    if (agentAvailabilityService.isEnabled(currentTenantId(), vo.getAgent().getAgentId())) {
                        // 第三层：确认可用后才加入结果，被禁用的不会出现在用户界面上。
                        agentList.add(vo.getAgent());
                    }
                }
            }
        }

        // 返回可用 Agent 列表，供前端展示选择。
        return agentList;
    }

    /**
     * 为一个公共 Agent 建立新会话。
     *
     * <p>先做准入校验（必须是静态 Agent、必须未被租户禁用、必须已装配成功），
     * 校验通过后才建会话。顺序反了会留下指向不可用 Agent 的空会话。</p>
     *
     * <p>会写数据库。校验不通过时抛业务异常，不产生任何会话记录。</p>
     */
    @Override
    public String createSession(String agentId, String userId) {
        // 先过准入三连：是静态 Agent、租户未禁用、装配已完成；任一不满足直接抛异常。
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        // 复用已校验的运行体建会话，避免下游再查一次注册表。
        return createSession(agentId, userId, aiAgentRegisterVO);
    }

    @Override
    public String createSubagentSession(String agentId, String userId) {
        AiAgentRegisterVO agent = requirePublicAgent(agentId);
        return createSession(currentTenantId(), agentId, userId, agent, "subagent", null, null);
    }

    @Override
    public void deleteSubagentRuntimeSession(String agentId, String userId, String sessionId) {
        AiAgentRegisterVO agent = requireRegisteredAgent(agentId);
        agent.getRunner().sessionService().deleteSession(agent.getAppName(), userId, sessionId).blockingAwait();
    }

    /**
     * 为一个工作流建立新会话，并把本次解析出的真实版本和模型固化进会话。
     *
     * <p>各层职责：
     * 第一层：取可信租户，它决定后续所有查询的隔离范围。
     * 第二层：让工作流服务按权限解析出真实生效的版本、模型和内部运行时 Agent。
     * 第三层：确认那个内部运行时 Agent 已经装配好。
     * 第四层：建会话，同时把解析出的版本和模型写进会话记录固化下来。</p>
     *
     * <p>数据流：
     * 工作流选择条件（workflowId + 期望版本 + 期望模型）
     * → 按权限解析出真实运行时（实际版本 + 实际模型 + 内部 Agent）
     * → 校验内部 Agent 已装配
     * → 建 ADK 会话 + 落库业务会话（固化版本与模型）
     * → 返回 sessionId</p>
     *
     * <p>为什么要固化：客户端传的只是"选择条件"，真实生效值由服务端按权限和发布状态决定。
     * 写进会话后这个会话就一直按同一版本跑，别人发布新版本不会让进行中的对话中途变样。</p>
     *
     * <p>会写数据库。无权访问该工作流、版本不存在或未发布时抛业务异常。</p>
     */
    @Override
    public String createWorkflowSession(String workflowId, Integer workflowVersion, String modelCode, String userId) {
        // 第一层：取可信租户，所有后续查询都以它隔离。
        String tenantId = currentTenantId();
        // 第二层：客户端提交的是选择条件；实际版本、模型和运行时 Agent 均由服务端解析。
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, TenantContextHolder.getRoleCode(),
                workflowId, workflowVersion, modelCode);
        // 第三层：确认工作流内部使用的运行时 Agent 已经装配好，否则会话建了也跑不起来。
        AiAgentRegisterVO aiAgentRegisterVO = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        // 第四层：建会话并把解析出的真实版本和模型固化进记录。
        return createWorkflowSession(tenantId, workflowId, userId, aiAgentRegisterVO, runtime);
    }

    /**
     * 用当前认证上下文里的租户建普通 Agent 会话。
     *
     * <p>只是补上租户参数后转交完整实现，避免每个调用点都写一遍取租户的代码。</p>
     */
    private String createSession(String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        // 补上可信租户后转交，租户来源统一收在一处便于审计。
        return createSession(currentTenantId(), sessionAgentId, userId, aiAgentRegisterVO);
    }

    /**
     * 建普通 Agent 会话：来源类型标为 agent，不固化工作流版本和模型。
     *
     * <p>普通 Agent 会话为什么不固化模型：它的模型是装配时定死在 Agent 上的，
     * 不像工作流那样由请求参数选择，所以没有"需要固化"的可变量。</p>
     */
    private String createSession(String tenantId, String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        // 来源类型固定为 agent，版本和模型传空表示这类会话不需要固化它们。
        return createSession(tenantId, sessionAgentId, userId, aiAgentRegisterVO, "agent", null, null);
    }

    /**
     * 建工作流会话：来源类型标为 workflow，并把服务端解析出的真实版本和模型写进记录。
     *
     * <p>会话里的 agentId 位置存的是 workflowId，因为对工作流会话来说"运行目标"就是这个工作流。</p>
     */
    private String createWorkflowSession(String tenantId, String workflowId, String userId,
                                         AiAgentRegisterVO aiAgentRegisterVO, WorkflowRuntimeEntity runtime) {
        // 来源类型标为 workflow，并固化本次解析出的真实版本和实际生效模型。
        return createSession(tenantId, workflowId, userId, aiAgentRegisterVO, "workflow",
                runtime.getVersion(), runtime.getEffectiveModelCode());
    }

    /**
     * 真正建会话：先建 ADK 会话拿到 ID，再用同一个 ID 落库业务会话。
     *
     * <p>各层职责：
     * 第一层：从运行体取出应用名和 Runner，它们决定 ADK 会话建在哪个应用空间下。
     * 第二层：让 ADK 生成会话，并直接复用它的 ID 作为平台会话 ID。
     * 第三层：把全部事实（租户、用户、会话、运行目标、来源类型、版本、模型、标题）装进命令。
     * 第四层：一次性落库，不允许出现缺少运行目标的半成品会话。</p>
     *
     * <p>数据流：
     * 运行体 + 身份 + 来源信息
     * → ADK 创建会话，得到 sessionId
     * → 组装建会话命令（含全部事实）
     * → 一次性落库业务会话
     * → 返回 sessionId</p>
     *
     * <p>为什么复用 ADK 的 ID：如果两边各生成一个 ID，就必须再维护一张映射表，
     * 而映射表一旦不一致，模型侧就会读不到对应的会话。复用同一个 ID 直接消除了这类问题。</p>
     *
     * <p>会写数据库。ADK 会话建成功但落库失败时，会留下一个 ADK 侧的孤儿会话，
     * 但它不会被任何业务逻辑引用，因为业务只认数据库里的记录。</p>
     */
    private String createSession(String tenantId, String sessionAgentId, String userId,
                                 AiAgentRegisterVO aiAgentRegisterVO, String sourceType,
                                 Integer workflowVersion, String modelCode) {
        // 第一层：取出 ADK 应用名，它决定会话数据落在哪个应用空间。
        String appName = aiAgentRegisterVO.getAppName();
        // 取出已装配好的 Runner，会话服务挂在它上面。
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 第二层：ADK 生成的会话 ID 同时作为平台会话 ID，避免双 ID 映射。
        Session session = runner.sessionService().createSession(appName, userId).blockingGet();
        // 第三层：组装建会话命令，把所有需要固化的事实一次性填齐。
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        // 租户是数据隔离维度，缺失会导致会话归属不清。
        command.setTenantId(tenantId);
        // 用户决定这个会话归谁，后续读写都要和它比对。
        command.setUserId(userId);
        // 会话编号，与 ADK 侧保持一致。
        command.setSessionId(session.id());
        // 运行目标：普通会话存 agentId，工作流会话存 workflowId。
        command.setAgentId(sessionAgentId);
        // 运行目标的展示名，用于会话列表。
        command.setAgentName(aiAgentRegisterVO.getAgentName());
        // 来源类型区分 agent 和 workflow，决定后续按哪条路径执行。
        command.setSourceType(sourceType);
        // 工作流会话固化的真实版本；普通会话为空。
        command.setWorkflowVersion(workflowVersion);
        // 工作流会话固化的实际生效模型；普通会话为空。
        command.setModelCode(modelCode);
        // ADK 应用名，恢复会话时要用它定位 ADK 侧的会话空间。
        command.setAppName(appName);
        // 会话初始标题先用运行目标名，后续可能被改写成首句摘要。
        command.setTitle(aiAgentRegisterVO.getAgentName());
        // 第四层：先准备完整事实再一次落库，不允许存在缺少运行目标的半成品会话。
        sessionDomain.createSession(command);
        // 返回会话编号，前端后续每轮对话都要带上它。
        return session.id();
    }

    /**
     * 建一个临时会话并立刻同步跑一轮对话。
     *
     * <p>先做准入校验再建会话，最后复用带会话的同步入口执行，保证和正常路径走同一套逻辑。</p>
     *
     * <p>会写数据库（会话、运行、用户与助手消息），会调用大模型。</p>
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        // 先过准入校验，Agent 不可用时不建会话也不产生任何消费。
        requirePublicAgent(agentId);

        // 建一个新会话承载这轮对话。
        String sessionId = createSession(agentId, userId);

        // 复用带会话的同步入口，避免两套执行逻辑产生行为差异。
        return handleMessage(agentId, userId, sessionId, message);
    }

    /**
     * 在已有会话里同步跑一轮对话。
     *
     * <p>只做准入校验然后转交内部实现；校验放在这里是为了让所有公共入口都必须过这道关。</p>
     *
     * <p>会写数据库、会调用大模型。会话不属于该用户时抛业务异常。</p>
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        // 准入校验并取出已装配的运行体，顺带避免下游重复查注册表。
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);

        sessionOrchestrationQueryService.assertAcceptsUserMessage(currentTenantId(), userId, sessionId);

        // 转交内部实现执行完整的落库与模型调用流程。
        return doHandleMessage(agentId, userId, sessionId, message, aiAgentRegisterVO, false, null);
    }

    @Override
    public List<String> handleInternalMessage(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        return doHandleMessage(agentId, userId, sessionId, message, aiAgentRegisterVO, true, null);
    }

    @Override
    public List<String> handleInternalMessage(String agentId, String userId, String sessionId, String message,
                                              String requestedRunId) {
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        return doHandleMessage(agentId, userId, sessionId, message, aiAgentRegisterVO, true, requestedRunId);
    }

    /**
     * 同步执行一次完整工作流，只返回收敛后的最终文本。
     *
     * <p>内部直接复用流式实现再取第一个元素——工作流的文本流只会发出一个元素（最终结果），
     * 所以取第一个就等于等它跑完。这样同步和流式共用同一套编排，不会出现行为不一致。</p>
     *
     * <p>会写数据库、会逐节点调用模型。这里会阻塞到整个 DAG 跑完。</p>
     */
    @Override
    public List<String> handleWorkflowMessage(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        // 复用流式实现并阻塞取唯一元素；不传 requestedRunId 表示这是一次全新运行。
        return List.of(startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId,
                message, null).getStream().blockingFirst());
    }

    /**
     * 只返回事件流的旧入口，调用方拿不到运行编号。
     *
     * <p>没有 runId 就无法取消、无法查状态、无法关联引用，所以新代码应改用 startMessageStream。
     * 保留它只为兼容尚未改造的调用方。</p>
     */
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        // 走正式入口但丢掉运行信息，只把流交出去。
        return startMessageStream(agentId, userId, sessionId, message, null).getStream();
    }

    /**
     * 启动一次不带附件的流式对话。
     *
     * <p>只是把附件参数补成空列表后转交完整实现，避免重复维护两套流程。</p>
     */
    @Override
    public RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                                     String requestedRunId) {
        // 附件补成空列表后转交，行为与带附件版本完全一致。
        return startMessageStream(agentId, userId, sessionId, message, requestedRunId, List.of());
    }

    /**
     * 启动一次带附件的流式对话，是流式 Agent 分支的正式入口。
     *
     * <p>各层职责：
     * 第一层：准入校验并取出已装配的运行体，取不到就说明 Agent 不可用。
     * 第二层：取可信租户，并确保会话存在（前端没给就补建一个）。
     * 第三层：创建或恢复运行记录。这一步同时处理三种情况：全新运行、幂等重试、执行中引导的后继运行。
     * 第四层：如果是"执行中引导"的后继运行，把前序的原始问题和用户的新指令拼成本轮输入。
     * 第五层：组装运行 + 惰性事件流返回；真正的模型调用要等到流被订阅才发生。</p>
     *
     * <p>数据流：
     * 请求参数（Agent/用户/会话/消息/运行/附件）
     * → 准入校验取运行体
     * → 确保会话存在
     * → 创建或恢复运行记录（落库）
     * → 计算本轮实际输入（可能拼接引导指令）
     * → 构造惰性事件流
     * → 返回运行 + 流
     * → 订阅时才落用户消息、装上下文、调模型</p>
     *
     * <p>为什么返回的流是惰性的：控制器需要先拿到 runId 去登记取消句柄，再订阅流。
     * 如果构造流时就开始调模型，取消句柄还没登记好就已经在烧钱了。</p>
     *
     * <p>会写数据库、会调用大模型。</p>
     */
    @Override
    public RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                                     String requestedRunId, List<String> attachmentIds) {
        // 第一层：准入校验并取出运行体。
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        // 第二层：取可信租户作为后续所有落库操作的隔离维度。
        String tenantId = currentTenantId();
        // 前端没给会话就补建一个，保证消息有归属。
        String actualSessionId = ensureSessionId(agentId, userId, sessionId, aiAgentRegisterVO);
        sessionOrchestrationQueryService.assertAcceptsUserMessage(tenantId, userId, actualSessionId);
        // 第三层：startOrResume 同时处理新运行、幂等重试和引导后继运行。
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "agent", agentId, requestedRunId);
        // 第四层：引导型后继运行要把前序原始问题接上用户的新指令，普通运行原样返回入参。
        String effectiveMessage = steerResumeMessage(run, message);
        // 第五层：打包运行记录和惰性事件流；流未被订阅前不会调用模型。
        Flowable<Event> stream;
        try {
            stream = doHandleMessageStream(agentId, userId, actualSessionId, effectiveMessage,
                    aiAgentRegisterVO, run, attachmentIds);
        } catch (RuntimeException startupError) {
            // 建流阶段已创建 Run，任何后续失败都必须收口，否则会永久占用会话的活跃运行锁。
            try {
                runControlService.failWithAssistantMessage(tenantId, userId, run.getRunId(),
                        errorContent(startupError, ""), run.getTraceId(), safeMessage(startupError));
            } catch (RuntimeException finalizationError) {
                startupError.addSuppressed(finalizationError);
            }
            throw startupError;
        }
        return RunStreamEntity.<Event>builder()
                .run(run)
                .stream(stream)
                .build();
    }

    /**
     * 已废弃的工作流事件流入口，调用它一定返回错误流。
     *
     * <p>为什么直接拒绝：工作流内部的节点事件属于实现细节，一旦暴露出去，调用方就会依赖具体拓扑，
     * 以后改编排就会破坏兼容。需要看节点进度请查工作流事件账本，需要看结果请用文本流接口。</p>
     */
    @Override
    public Flowable<Event> handleWorkflowMessageStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        // 直接返回错误流，明确告知调用方该用哪个接口，而不是给一个半可用的事件流。
        return Flowable.error(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流流式输出请使用文本流接口"));
    }

    /**
     * 只返回工作流最终文本流的旧入口，调用方拿不到运行编号。
     *
     * <p>同样因为缺少 runId 而无法取消，新代码请改用 startWorkflowMessageTextStream。</p>
     */
    @Override
    public Flowable<String> handleWorkflowMessageTextStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        // 走正式入口但丢掉运行信息，只把文本流交出去。
        return startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId, message, null)
                .getStream();
    }

    /**
     * 启动一次不带附件的工作流运行。
     *
     * <p>把附件参数补成空列表后转交完整实现。</p>
     */
    @Override
    public RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                                   String modelCode, String userId, String sessionId,
                                                                   String message, String requestedRunId) {
        // 附件补成空列表后转交，行为与带附件版本一致。
        return startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId,
                message, requestedRunId, List.of());
    }

    /**
     * 启动一次带附件的工作流运行，是流式工作流分支的正式入口。
     *
     * <p>各层职责：
     * 第一层：取可信租户、链路标识和角色，它们要一路带进每个节点。
     * 第二层：把工作流解析并冻结成本次运行专用的 DAG 快照，避免执行中配置漂移。
     * 第三层：确认内部运行时 Agent 已装配，并确保会话存在。
     * 第四层：创建或恢复运行记录，并算出本轮实际输入。
     * 第五层：把阻塞的 DAG 执行推到协调线程池，包成可取消的单值流。
     * 第六层：给流套上取消观察（跨实例取消靠轮询数据库，客户端断流则反向写入取消）。
     * 第七层：注册收尾动作——被取消的运行必须清掉 RAG 证据，不留下能被后续回答引用的脏数据。</p>
     *
     * <p>数据流：
     * 请求参数（工作流/版本/模型/用户/会话/消息/运行/附件）
     * → 解析并冻结 DAG 快照
     * → 校验内部 Agent + 确保会话存在
     * → 创建或恢复运行记录（落库）
     * → 计算本轮实际输入
     * → 提交 DAG 执行到协调线程池（订阅后才真正开始）
     * → 套取消观察
     * → 套证据清理收尾
     * → 返回运行 + 文本流</p>
     *
     * <p>为什么入口线程只做轻活：编译 DAG 和建运行记录很快，跑节点却可能几十秒。
     * 把执行推到线程池，HTTP/SSE 线程才不会被长时间占住。</p>
     *
     * <p>会写数据库、会逐节点调用模型、会持续发布节点事件。</p>
     */
    @Override
    public RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                                   String modelCode, String userId, String sessionId,
                                                                   String message, String requestedRunId,
                                                                   List<String> attachmentIds) {
        // 第一层：取可信租户作为隔离维度。
        String tenantId = currentTenantId();
        // 取链路标识，它要显式传进每个节点，否则跨线程后节点日志会脱离入口链路。
        String traceId = TraceContext.currentOrNewTraceId();
        // 取角色，工作流权限校验和工具身份都要用它。
        String roleCode = TenantContextHolder.getRoleCode();
        // 第二层：每次运行先解析并冻结 DAG、模型和内部 Agent，避免执行中配置漂移。
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, roleCode,
                workflowId, workflowVersion, modelCode);
        // 第三层：确认工作流内部使用的运行时 Agent 已装配好。
        AiAgentRegisterVO rootAgent = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        // 前端没给会话就补建一个，并固化本次解析出的版本和模型。
        String actualSessionId = ensureWorkflowSessionId(tenantId, workflowId, userId, sessionId, rootAgent, runtime);
        // 第四层：创建或恢复运行记录，同时覆盖新运行、幂等重试和引导后继三种情况。
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "workflow", workflowId, requestedRunId);
        // 引导型后继运行要把前序原始问题接上新指令。
        String effectiveMessage = steerResumeMessage(run, message);
        // 第五、六、七层：真正的 DAG 执行发生在订阅后；HTTP 入口线程不直接跑节点。
        Flowable<String> stream = observeWorkflowCancellation(
                scheduleWorkflow(() -> doHandleWorkflowDagMessage(runtime, tenantId, userId,
                        actualSessionId, effectiveMessage, traceId, run, attachmentIds, roleCode)),
                tenantId, userId, run)
                .doFinally(() -> {
                    // 第七层：流以任何方式结束后都检查一次——被取消的运行不能留下可被后续回答引用的证据。
                    if (runControlService.cancelled(tenantId, userId, run.getRunId())) clearEvidence(run);
                });
        // 打包运行记录和惰性文本流返回。
        return RunStreamEntity.<String>builder()
                .run(run)
                .stream(stream)
                .build();
    }

    /**
     * 给工作流文本流套上双向的取消联动。
     *
     * <p>各层职责：
     * 第一层：每 250 毫秒查一次权威运行状态，一旦发现已取消就终止向下游发射。
     *         为什么要轮询数据库：取消请求可能打到集群里的另一台机器，只有数据库状态是所有实例都认的。
     * 第二层：反过来，如果是下游主动取消（用户关页面导致 SSE 断开），就把取消状态写回数据库，
     *     让正在别处执行的节点也能通过门禁感知到并停下来。</p>
     *
     * <p>数据流：
     * 上游文本流
     * → takeUntil（每 250ms 查一次取消状态）→ 已取消则截断
     * → 下游取消时 → 反向写入取消状态（若尚未取消）
     * → 输出给调用方</p>
     *
     * <p>轮询只是"尽快截断输出"，不是唯一防线：每个节点执行前还会再过一次取消门禁，
     * 所以最坏情况下也只是多跑完当前这个节点，不会一直跑到底。</p>
     *
     * <p>250 毫秒是个折中：太长会让用户觉得停止不灵，太短会给数据库带来无谓的查询压力。</p>
     */
    private <T> Flowable<T> observeWorkflowCancellation(Flowable<T> stream, String tenantId, String userId,
                                                         ChatRunEntity run) {
        // 在原流上叠加两条取消联动规则。
        return stream
                // 第一层：取消信号终止下游发射；各节点调用前还有 requireExecutable 二次门禁。
                .takeUntil(Flowable.interval(250, TimeUnit.MILLISECONDS)
                        .filter(tick -> runControlService.cancelled(tenantId, userId, run.getRunId())))
                .doOnCancel(() -> {
                    // 第二层：下游主动断开（例如浏览器关页）时，若数据库里还没标记取消就补写一次。
                    if (!runControlService.cancelled(tenantId, userId, run.getRunId())) {
                        // 写入取消状态，让别处正在执行的节点也能通过门禁感知并停止。
                        runControlService.cancel(tenantId, userId, run.getRunId(), "流式连接已中断");
                    }
                });
    }

    /**
     * 把一个阻塞的 DAG 执行动作包装成只发一个元素的流，并保证可取消。
     *
     * <p>各层职责：
     * 第一层：创建惰性流，只有被订阅时才提交任务，避免没人要的执行白跑。
     * 第二层：把链路标识显式包进任务里——跨线程后线程本地的链路标识就没了，
     *         不包的话节点日志会脱离入口链路，排查时对不上。
     * 第三层：任务真正拿到线程时再检查一次是否已被取消，取消了就一行代码都不执行，
     *      避免"排队等线程的这段时间里用户已经取消了，却还是跑了一遍"。
     * 第四层：执行成功则发出结果并完成；抛异常则转成流的错误信号。
     * 第五层：线程池满导致提交被拒时，把拒绝异常转成流错误而不是往外抛。
     * 第六层：注册取消动作——下游断开就中断协调线程。</p>
     *
     * <p>数据流：
     * 阻塞动作
     * → 订阅时提交到协调线程池（带上链路标识）
     * → 取得线程后检查取消状态
     * → 执行 DAG → 结果 → onNext + onComplete
     * → 异常 → onError
     * → 下游取消 → 中断协调线程</p>
     *
     * <p>背压策略选 ERROR：这个流最多只发一个元素，下游不可能来不及消费。
     * 真出现背压说明用法有问题，报错比静默缓冲更能暴露问题。</p>
     *
     * <p>注意中断协调线程只能停掉调度逻辑，已经发出去的模型和工具调用要靠节点内的门禁拦。</p>
     */
    private <T> Flowable<T> scheduleWorkflow(Callable<T> action) {
        // 第一层：创建惰性流，订阅时才提交任务。
        return Flowable.create(emitter -> {
            // 保存任务句柄，取消时用它中断线程。
            Future<?> future;
            // 提交本身可能被线程池拒绝，必须接住。
            try {
                // 第二层：跨线程显式传播 traceId，保证节点日志仍属于入口链路。
                Callable<T> tracedAction = TraceContext.wrap(action);
                // 提交到协调线程池，真正的 DAG 执行在那里发生。
                future = workflowCoordinatorExecutor.submit(() -> {
                    // 第三层：订阅在任务取得线程前已取消时，不再产生任何外部调用。
                    if (emitter.isCancelled()) {
                        // 直接返回，一次模型调用都不发生。
                        return;
                    }
                    // 执行过程中的异常必须转成流错误，不能让它消失在线程池里。
                    try {
                        // 第四层：真正跑完整个 DAG，拿到最终文本。
                        T result = tracedAction.call();
                        // 期间可能被取消，取消后就不再向下游发数据。
                        if (!emitter.isCancelled()) {
                            // 发出唯一的结果元素。
                            emitter.onNext(result);
                            // 通知下游流已结束。
                            emitter.onComplete();
                        }
                    } catch (Throwable throwable) {
                        // 用 tryOnError 而不是 onError：下游可能已经取消，此时发错误会被忽略而不是抛异常。
                        emitter.tryOnError(throwable);
                    }
                });
            } catch (RejectedExecutionException exception) {
                // 第五层：线程池满或已关闭导致提交被拒，转成流错误告知调用方。
                emitter.tryOnError(exception);
                // 提交都没成功，没有句柄可注册，直接返回。
                return;
            }
            // 第六层：断流中断协调线程；节点内部仍依赖运行门禁阻止后续工具/模型调用。
            emitter.setCancellable(() -> future.cancel(true));
        }, BackpressureStrategy.ERROR);
    }

    /**
     * 同步发送一条可包含文本、外部文件和内联二进制的复合消息。
     *
     * <p>各层职责：
     * 第一层：准入校验、确保会话存在、校验会话归属，三道关都过了才继续。
     * 第二层：按客户端声明的顺序把三类内容拼成模型输入；顺序不能变，否则语义会变。
     * 第三层：先建运行记录、补发未完成的压缩任务、落用户消息，然后才调模型——
     * 模型永远不先于可审计事实执行。
     * 第四层：为本次调用建一个隔离的 ADK 会话，避免 ADK 内存历史和数据库历史重复注入。
     * 第五层：调模型并阻塞收齐全部输出，每个事件前都复核一次取消状态。
     * 第六层：正常结束则原子写助手消息并推进运行成功；异常则区分"被取消"和"真失败"分别收尾。</p>
     *
     * <p>数据流：
     * 复合命令（文本 + 文件引用 + 内联字节）
     * → 准入与归属校验
     * → 按序拼成 ADK Content
     * → 创建运行记录（落库）
     * → 补发未完成压缩任务
     * → 落用户消息（落库，正文只存可检索的摘要）
     * → 建隔离 ADK 会话
     * → 调模型，逐事件收集输出（每次复核取消）
     * → 正常：校验引用 + 写助手消息 + 运行成功（落库）
     * → 异常：被取消则清证据；真失败则写错误消息并保留已生成片段
     * → 返回全部输出</p>
     *
     * <p>二进制正文不落消息表，只记类型摘要；否则业务库会被大块字节撑爆。</p>
     *
     * <p>失败时会把已生成的片段一起写进错误消息，方便事后判断到底跑到哪一步炸的。
     * 被取消时不写错误消息，只清掉证据——取消是用户的正常操作，不该留下失败记录。</p>
     */
    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        // 第一层：准入校验并取出运行体。
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(chatCommandEntity.getAgentId());

        // 取可信租户作为隔离维度。
        String tenantId = currentTenantId();
        // 没有会话就补建一个，保证消息有归属。
        String actualSessionId = ensureSessionId(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());
        // 校验这个会话确实属于这个用户和这个 Agent，防止换个 sessionId 就写进别人的对话。
        sessionDomain.assertSessionAccess(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId());

        // 第二层：保持客户端声明的三类内容顺序组装为 ADK Part。
        List<Part> parts = new ArrayList<>();

        // 先放文本段。
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        // 没有文本时跳过，允许只发文件或图片。
        if (null != texts && !texts.isEmpty()) {
            // 按列表顺序逐段放入，顺序决定模型读到的语义。
            for (ChatCommandEntity.Content.Text text : texts) {
                // 把一段文本包成模型可读的内容片。
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        // 再放外部文件引用，模型侧会按 URI 自己去拉取内容。
        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        // 没有文件就跳过。
        if (null != files && !files.isEmpty()) {
            // 逐个把地址和类型包成内容片；地址必须是模型服务能访问到的。
            for (ChatCommandEntity.Content.File file : files) {
                // 只传引用不传内容，避免大文件走请求体。
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        // 最后放内联二进制，内容直接随请求发走。
        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        // 没有内联数据就跳过。
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            // 逐块把字节和类型包成内容片；体积过大会撑爆请求体。
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                // 把这块字节交给模型解析。
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        // 把三类内容片打包成一条用户角色的消息。
        Content content = Content.builder().role("user").parts(parts).build();

        // 取出已装配的 Runner，它是真正执行对话的对象。
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        // 取链路标识，落库和日志都要带上它。
        String traceId = TraceContext.currentOrNewTraceId();
        // 第三层：先建立业务运行并落用户消息，模型永远不先于可审计事实执行。
        ChatRunEntity run = runControlService.start(tenantId, chatCommandEntity.getUserId(), actualSessionId,
                "agent", chatCommandEntity.getAgentId(), null, null);
        // 重发未发布的压缩任务，避免历史持久化成功但异步消息丢失。
        conversationMemoryService.republishUnfinished(tenantId, chatCommandEntity.getUserId(), actualSessionId);
        // 落用户消息并与运行绑定；正文用可检索的摘要，二进制本体不入表。
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, chatCommandEntity.getUserId(), run.getRunId(),
                describeContent(chatCommandEntity), traceId);
        // 取出刚落库的用户消息，它的序号决定模型能看到哪些历史。
        ChatMessageEntity userMessage = binding.getMessage();
        // 取出绑定后的最新运行状态，后面的取消复核和终态推进都用它。
        ChatRunEntity activeRun = binding.getRun();
        // 第四层：为这次调用生成一个隔离的 ADK 会话编号。
        String adkSessionId = invocationSessionId(actualSessionId);
        // 幂等确保这个 ADK 会话存在，不存在就建。
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), adkSessionId);

        // 第五层：message 在这里作为 content 交给 ADK Runner；state 同时注入可信运行身份和上下文切面。
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), adkSessionId, content, RunConfig.builder()
                        .streamingMode(RunConfig.StreamingMode.SSE).build(),
                runtimeStateDelta(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId(), traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                        ragTargetType(activeRun, RagBindingTargetType.AGENT),
                        ragQuery(activeRun, describeContent(chatCommandEntity)),
                        activeRun.getRagMode(), activeRun.getRagBindingIds(), activeRun.getRagInvocationMode()));

        StringBuilder assistantContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        publishAgentEvent(activeRun, "AGENT_STARTED", Map.of("agentId", chatCommandEntity.getAgentId()));
        // 收集和终态推进都可能抛异常，必须统一收尾，否则运行会永远停在"进行中"。
        boolean deferred;
        try {
            // 阻塞逐个消费事件，直到流正常结束。
            events.blockingForEach(event -> {
                // 每个事件前复核取消状态，取消后不再接受输出或继续工具链。
                runControlService.requireExecutable(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), null);
                observeAgentEvent(activeRun, event, thinkingContent, assistantContent, null,
                        !isSupervisor(chatCommandEntity.getAgentId()) || AgentOrchestrationContextHolder.isSummaryOnly());
            });
            // 第六层：只有事件流正常结束才原子写助手消息并推进运行成功。
            deferred = completeRunWithAssistant(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(),
                    assistantContent.toString(), traceId);
            publishAgentTerminal(activeRun, deferred, null, null);
        } catch (RuntimeException e) {
            // 区分"被取消"和"真失败"：只有真失败才写错误消息。
            if (!runControlService.cancelled(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId())) {
                // 非取消异常保留已生成片段，便于审计实际失败点。
                failRunWithAssistantError(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), traceId, e,
                        assistantContent.toString());
                publishAgentTerminal(activeRun, false, e, null);
            } else {
                // 取消是用户的正常操作，不写失败记录，但必须清掉证据避免被后续回答引用。
                clearEvidence(activeRun);
            }
            // 异常继续上抛，让调用方知道这次对话没有成功。
            throw e;
        }

        // 返回收集到的全部输出文本。
        return deferred ? List.of() : List.of(assistantContent.toString());
    }

    /**
     * 同步执行一次纯文本对话，业务状态处理顺序与流式入口完全一致。
     *
     * <p>各层职责：
     * 第一层：取 Runner 和可信租户，确保会话存在并校验归属。
     * 第二层：先建运行记录、补发未完成压缩任务、落用户消息，再调模型。
     * 第三层：建隔离 ADK 会话，把文本包成模型输入。
     * 第四层：调模型并阻塞收齐输出，每个事件前复核取消状态。
     * 第五层：正常结束写助手消息推进成功；异常则区分取消与真失败分别收尾。</p>
     *
     * <p>数据流：
     * 文本消息
     * → 归属校验
     * → 创建运行记录（落库）
     * → 补发未完成压缩任务
     * → 落用户消息（落库）
     * → 建隔离 ADK 会话
     * → 调模型，逐事件收集（每次复核取消）
     * → 正常：校验引用 + 写助手消息 + 运行成功
     * → 异常：取消则清证据；真失败则写错误消息
     * → 返回全部输出</p>
     *
     * <p>和复合消息入口的唯一区别是输入只有一段文本；状态处理顺序刻意保持一致，
     * 这样任意入口出问题时的排查思路都一样。</p>
     */
    private List<String> doHandleMessage(String sessionAgentId, String userId, String sessionId, String message,
                                         AiAgentRegisterVO aiAgentRegisterVO, boolean platformInput,
                                         String requestedRunId) {
        // 第一层：取出已装配的 Runner。
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        // 取可信租户作为隔离维度。
        String tenantId = currentTenantId();
        // 没有会话就补建，复用已校验的运行体避免重复查注册表。
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        // 校验会话归属，防止越权写入别人的对话。
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        // 取链路标识，贯穿落库与日志。
        String traceId = TraceContext.currentOrNewTraceId();
        // 第二层：运行和用户消息先落库，保证失败、取消和重试均有稳定锚点。
        ChatRunEntity run = platformInput && requestedRunId != null
                ? runControlService.startOrReuseInternal(tenantId, userId, actualSessionId,
                "agent", sessionAgentId, requestedRunId)
                : runControlService.start(tenantId, userId, actualSessionId, "agent", sessionAgentId, null, null);
        if (platformInput && run.getStatus().terminal()) {
            return sessionDomain.queryRunMessages(tenantId, userId, actualSessionId, run.getRunId()).stream()
                    .filter(value -> SessionDomain.ROLE_ASSISTANT.equals(value.getRole()))
                    .map(ChatMessageEntity::getContent).filter(java.util.Objects::nonNull).toList();
        }
        // 补发之前落库成功但异步消息丢失的压缩任务，避免历史越积越长。
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        // 落用户消息并与运行绑定。
        RunMessageBindingEntity binding = platformInput
                ? runControlService.appendPlatformMessage(tenantId, userId, run.getRunId(), message, traceId)
                : saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId);
        // 取出刚落库的用户消息，它的序号决定历史可见范围。
        ChatMessageEntity userMessage = binding.getMessage();
        // 取出绑定后的最新运行状态。
        ChatRunEntity activeRun = binding.getRun();
        // 第三层：为本次调用生成隔离的 ADK 会话编号。
        String adkSessionId = invocationSessionId(actualSessionId);
        // 幂等确保这个 ADK 会话存在。
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        // 把用户这句话包成模型输入。
        Content userMsg = Content.fromParts(Part.fromText(message));
        // 第四层：message 在此进入 ADK Agent；插件从 state 读取上下文、RAG 与工具身份。
        Flowable<Event> events = runner.runAsync(userId, adkSessionId, userMsg, RunConfig.builder()
                        .streamingMode(RunConfig.StreamingMode.SSE).build(),
                runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                        ragTargetType(activeRun, RagBindingTargetType.AGENT), ragQuery(activeRun, message),
                        activeRun.getRagMode(), activeRun.getRagBindingIds(), activeRun.getRagInvocationMode()));

        StringBuilder assistantContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        publishAgentEvent(activeRun, "AGENT_STARTED", Map.of("agentId", sessionAgentId));
        // 无论成败都要给运行一个明确终态，否则前端会一直显示进行中。
        boolean deferred;
        try {
            // 阻塞逐个消费事件直到流结束。
            events.blockingForEach(event -> {
                // 每个事件前复核取消状态，取消后立即中断，不再消耗模型额度。
                runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                observeAgentEvent(activeRun, event, thinkingContent, assistantContent, null,
                        !isSupervisor(sessionAgentId) || AgentOrchestrationContextHolder.isSummaryOnly());
            });
            // 第五层：正常结束才写助手消息并推进运行成功。
            deferred = completeRunWithAssistant(tenantId, userId, activeRun.getRunId(), assistantContent.toString(), traceId);
            publishAgentTerminal(activeRun, deferred, null, null);
        } catch (RuntimeException e) {
            // 区分取消与真失败。
            if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                // 真失败：写错误消息并保留已生成片段，便于定位失败点。
                failRunWithAssistantError(tenantId, userId, activeRun.getRunId(), traceId, e, assistantContent.toString());
                publishAgentTerminal(activeRun, false, e, null);
            } else {
                // 被取消：不写失败记录，但必须清掉证据。
                clearEvidence(activeRun);
            }
            // 异常上抛，让调用方感知失败。
            throw e;
        }

        // 返回全部输出文本。
        return deferred ? List.of() : List.of(assistantContent.toString());
    }

    /**
     * 构造普通 Agent 的流式事件流，订阅后才真正调用模型。
     *
     * <p>各层职责：
     * 第一层：取 Runner、租户，确保会话存在并校验归属。
     * 第二层：补发未完成的压缩任务，然后把用户消息和附件在同一个运行事务里绑定落库。
     *   同一事务是关键——否则上下文可能看到一个还没确认归属的附件。
     * 第三层：建隔离 ADK 会话，把文本包成模型输入，配置为 SSE 流式模式。
     * 第四层：准备两样收尾用的东西——累计助手文本的缓冲区，和一个"是否已写过终态"的开关。
     *   完成、异常、取消三条路径可能同时到达，开关保证只有一条真正写库。
     * 第五层：订阅链上依次挂：取消轮询截断、逐事件复核取消并累计文本、完成写成功、
     *   出错写失败、被取消回写取消状态、最终清理证据。</p>
     *
     * <p>数据流：
     * 用户消息 + 附件
     * → 归属校验
     * → 补发未完成压缩任务
     * → 用户消息与附件同事务落库
     * → 建隔离 ADK 会话
     * → 调模型（SSE 模式），得到事件流
     * → takeUntil：每 250ms 查取消状态，已取消则截断
     * → doOnNext：复核取消 + 把事件文本累计进缓冲区（自动去重累计快照）
     * → doOnComplete：未取消则写助手消息 + 运行成功（落库，仅一次）
     * → doOnError：未取消则写错误消息 + 运行失败（落库，仅一次）
     * → doOnCancel：若数据库还没标记取消则补写取消
     * → doFinally：已取消则清空 RAG 证据
     * → 交给调用方推送前端</p>
     *
     * <p>为什么用一个原子开关控制终态写入：SSE 断开、模型出错、正常完成这三件事在并发下可能
     * 几乎同时发生。没有开关的话会写出两条助手消息，或者把已成功的运行又改成失败。</p>
     *
     * <p>会写数据库、会调用大模型。被取消时不写失败消息，只清证据。</p>
     */
    private Flowable<Event> doHandleMessageStream(String sessionAgentId, String userId, String sessionId, String message,
                                                  AiAgentRegisterVO aiAgentRegisterVO, ChatRunEntity run,
                                                  List<String> attachmentIds) {
        // 第一层：取出已装配的 Runner。
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        // 取可信租户作为隔离维度。
        String tenantId = currentTenantId();
        // 没有会话就补建。
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        // 校验会话归属，防止越权。
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        // 取链路标识，贯穿落库与日志。
        String traceId = TraceContext.currentOrNewTraceId();
        // 第二层：补发之前丢失的压缩任务，避免历史越积越长最终撑爆上下文。
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        // 用户消息与附件在同一运行事务中绑定，后续上下文只能看到本次已确认资产。
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId,
                attachmentIds);
        // 取出刚落库的用户消息，它的序号决定历史可见范围。
        ChatMessageEntity userMessage = binding.getMessage();
        // 用绑定后的运行状态覆盖入参，后面的取消复核要基于最新状态。
        run = binding.getRun();
        // 第三层：为本次调用生成隔离的 ADK 会话编号。
        String adkSessionId = invocationSessionId(actualSessionId);
        // 幂等确保这个 ADK 会话存在。
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        // 把用户这句话包成模型输入。
        Content userMsg = Content.fromParts(Part.fromText(message));
        // 声明为 SSE 流式模式，模型会边生成边返回分片。
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        // 第四层：累计助手回答文本的缓冲区，流结束时整段写库。
        StringBuilder assistantContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        // 完成、异常和取消可能竞争，只允许一个分支写助手终态。
        AtomicBoolean assistantSaved = new AtomicBoolean(false);
        // 子任务一旦创建，当前流只继续内部累计，不再把主 Agent 草稿当作最终答案下发。
        AtomicBoolean suppressParentOutput = new AtomicBoolean(false);
        // Lambda 需要一个有效不变的引用，所以把最新运行状态另存一份。
        ChatRunEntity activeRun = run;
        boolean supervisor = isSupervisor(sessionAgentId);
        publishAgentEvent(activeRun, "AGENT_STARTED", Map.of("agentId", sessionAgentId, "supervisor", supervisor));
        // 第五层：这是普通会话真正调用 Agent 的位置；用户 message 作为 userMsg 输入。
        Flowable<Event> response = runner.runAsync(userId, adkSessionId, userMsg, runConfig,
                        runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                                TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                                activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                                ragTargetType(activeRun, RagBindingTargetType.AGENT), ragQuery(activeRun, message),
                                activeRun.getRagMode(), activeRun.getRagBindingIds(), activeRun.getRagInvocationMode()))
                // 跨实例取消靠数据库轮询，本实例事件处理靠 requireExecutable 即时拦截。
                .takeUntil(Flowable.interval(250, TimeUnit.MILLISECONDS)
                        .filter(tick -> runControlService.cancelled(tenantId, userId, activeRun.getRunId())))
                .doOnNext(event -> {
                    // 每片到达时先复核取消，取消后立刻中断，不再消耗模型额度。
                    runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                    observeAgentEvent(activeRun, event, thinkingContent, assistantContent, null, !supervisor);
                })
                .doOnComplete(() -> {
                    // 正常完成才保存聚合后的助手文本。
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        // 用一次性开关保护，避免和异常、取消分支重复写库。
                        boolean deferred = completeRunWithAssistantOnce(assistantSaved, tenantId, userId,
                                activeRun.getRunId(), assistantContent.toString(), traceId);
                        if (deferred) suppressParentOutput.set(true);
                        publishAgentTerminal(activeRun, deferred, null, null);
                    }
                })
                .doOnError(throwable -> {
                    // 取消不写错误消息；真实异常写入可审计终态。
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        // 同样用一次性开关保护，并把已生成的片段一起留下便于定位失败点。
                        if (failRunWithAssistantErrorOnce(assistantSaved, tenantId, userId, activeRun.getRunId(), traceId,
                                throwable, assistantContent.toString())) suppressParentOutput.set(true);
                        publishAgentTerminal(activeRun, false, throwable, null);
                    }
                })
                .doOnCancel(() -> {
                    // Supervisor 已委派时，浏览器断开只结束首轮推理，不得取消持久化子任务。
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        boolean deferred = completeParentDraftOnDisconnectOnce(assistantSaved, tenantId, userId,
                                activeRun.getRunId(), assistantContent.toString());
                        if (deferred) suppressParentOutput.set(true);
                        else runControlService.cancel(tenantId, userId, activeRun.getRunId(), "流式连接已中断");
                        publishAgentTerminal(activeRun, deferred, null, deferred ? null : "cancelled");
                    }
                })
                .doFinally(() -> {
                    // 被取消的运行不能留下可被后续回答引用的 RAG 证据。
                    if (runControlService.cancelled(tenantId, userId, activeRun.getRunId())) clearEvidence(activeRun);
                });
        if (!supervisor) return response;
        // Supervisor 首轮整轮缓冲：未委派时结束后一次下发，已委派时正文全部留作隐藏草稿。
        return response
                .onErrorResumeNext(throwable -> suppressParentOutput.get()
                        ? Flowable.empty() : Flowable.error(throwable))
                .toList()
                .flatMapPublisher(events -> suppressParentOutput.get()
                        ? Flowable.empty() : Flowable.fromIterable(events));
    }

    /**
     * 落库工作流输入，执行冻结的 DAG，只把终点节点的汇总结果作为最终答案。
     *
     * <p>各层职责：
     * 第一层：确认计划非空且内部 Agent 已装配，确保会话存在。
     * 第二层：校验会话归属、补发未完成压缩任务、把用户消息和附件同事务落库。
     * 第三层：发出"工作流已启动"事件，前端据此开始画节点进度。
     * 第四层：按拓扑层执行整个 DAG，拿到最终输出和沿途累积的 RAG 证据。
     * 第五层：执行完再复核一次取消，然后原子写最终消息和成功终态。
     *  只带终点祖先证据——旁路节点检索到但没影响答案的资料不算合法出处。
     * 第六层：异常时区分取消与真失败：取消要和权威状态对账并清证据，真失败写失败消息和失败事件。</p>
     *
     * <p>数据流：
     * 冻结的 DAG 计划 + 用户消息 + 附件
     * → 校验计划与 Agent
     * → 归属校验
     * → 补发未完成压缩任务
     * → 用户消息与附件同事务落库
     * → 发 WORKFLOW_STARTED 事件
     * → 按拓扑层执行节点（每个节点一次模型调用，发 NODE_* 事件）
     * → 收集终点输出 + 终点祖先证据
     * → 复核取消
     * → 校验引用 + 写最终消息 + 运行成功 + 工作流完成事件（落库）
     * → 返回最终文本；
     * 异常：取消则对账取消状态并清证据；真失败则写失败消息与失败事件</p>
     *
     * <p>为什么只用终点祖先证据：DAG 里可能有分支节点检索了资料但结果没进最终答案。
     * 把它们算作合法出处，模型就能引用一份用户其实看不到的文档，等于变相编造。</p>
     *
     * <p>会写数据库、会逐节点调用模型、会持续发节点事件。异常一律上抛，让流的订阅者感知失败。</p>
     */
    private String doHandleWorkflowDagMessage(WorkflowRuntimeEntity runtime, String tenantId, String userId,
                                              String sessionId, String message, String traceId, ChatRunEntity run,
                                              List<String> attachmentIds, String roleCode) {
        // 第一层：runtime 已在入口按权限编译；此处只接受非空、可执行的计划。
        WorkflowDagPlanEntity dagPlan = requireDagPlan(runtime);
        // 确认工作流内部使用的运行时 Agent 已装配好。
        AiAgentRegisterVO rootAgent = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        // 没有会话就补建，并固化本次的版本和模型。
        String actualSessionId = ensureWorkflowSessionId(tenantId, runtime.getWorkflowId(), userId, sessionId, rootAgent, runtime);
        // 先用入参运行状态占位，落消息后会被替换成最新状态。
        ChatRunEntity activeRun = run;

        // 整段包起来：无论成败都必须给运行一个明确终态，否则前端会永远停在进行中。
        try {
            // 第二层：校验会话归属，防止越权执行别人的会话。
            sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, runtime.getWorkflowId());
            // 补发之前丢失的压缩任务。
            conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
            // 用户消息与附件同事务落库，保证上下文只看到已确认的资产。
            RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId,
                    attachmentIds);
            // 取出刚落库的用户消息，它的序号决定节点能看到哪些历史。
            ChatMessageEntity userMessage = binding.getMessage();
            // 用绑定后的最新运行状态覆盖占位值。
            activeRun = binding.getRun();
            // 第三层：发出工作流启动事件，带上计划的身份信息，前端据此初始化节点视图。
            publishWorkflowEvent(activeRun, "WORKFLOW_STARTED", null, null, Map.of(
                    "workflowId", dagPlan.getWorkflowId(),
                    "workflowVersion", dagPlan.getVersion(),
                    "workflowKind", "STATIC",
                    "rootNodeId", dagPlan.getRootNodeId()));
            // 第四层：message 在 executeDagPlan 中被组合进每个就绪节点提示词，再交给节点 Agent。
            WorkflowExecutionResult execution = executeDagPlan(dagPlan, tenantId, userId, actualSessionId, message, traceId,
                    roleCode, historyCutoff(userMessage), activeRun);
            // 取出终点节点汇总后的最终文本。
            String finalOutput = execution.output();
            // 打一条完成日志，记录节点数、边数、终点节点和输出长度，便于分析工作流规模与效果。
            AiLog.info(AiLog.workflow().dagCompleted(tenantId, userId, dagPlan.getWorkflowId(), dagPlan.getVersion(),
                    dagPlan.getNodes().size(), dagPlan.getEdges() == null ? 0 : dagPlan.getEdges().size(),
                    String.join(",", terminalNodeIds(dagPlan, outgoingEdges(dagPlan))), finalOutput.length()));
            // 第五层：写终态前再复核一次取消，避免把已取消的运行标记成成功。
            runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
            // 只携带终点祖先证据完成运行，未影响最终答案的旁路证据被排除。
            completeWorkflowRunWithAssistant(activeRun, finalOutput, execution.evidence(), dagPlan.getNodes().size());
            // 返回最终文本，它会作为流里唯一的元素发给调用方。
            return finalOutput;
        } catch (RuntimeException e) {
            // 第六层：区分取消与真失败。
            if (runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                // 被取消：和权威状态对账，把工作流侧的节点状态和事件收敛到取消终态。
                workflowRunFinalizationService.reconcileCancellation(
                        runControlService.require(tenantId, userId, activeRun.getRunId()));
                // 清掉证据，避免后续回答引用本次已作废的资料。
                clearEvidence(activeRun);
            } else {
                // 真失败：写失败消息和失败事件，让前端能显示具体原因。
                failWorkflowRunWithAssistantError(activeRun, e);
            }
            // 异常上抛，交给流的订阅者处理。
            throw e;
        }
    }

    /**
     * 按拓扑分层调度整个 DAG：同一层并行跑，下一层等上一层全部完成。
     *
     * <p>各层职责：
     * 第一层：建三张索引——节点查找表、出边表、入边表，以及入度表和自循环节点集合。
     *         自循环边不算入度，因为它表达的是"这个节点自己重复几次"，不是依赖关系。
     * 第二层：找出入度为零的节点作为第一层；旧计划可能没有零入度节点，用显式根节点兜底。
     * 第三层：循环推进——每轮先复核取消，然后把当前层所有节点提交到线程池并行执行。
     * 第四层：等当前层全部结束再统一处理结果。即使有节点失败也要等兄弟节点收敛，
     *      否则兄弟节点会在工作流已标记失败后继续写事件，账本就乱了。
     * 第五层：把本层结果写回输出表和证据表，并给下游节点扣减入度，归零的进入下一层。
     * 第六层：全部跑完后核对节点数。执行数少于总数说明存在环路或永远满足不了的依赖。
     * 第七层：取终点节点的输出拼成最终答案，并只保留终点可追溯到的证据。</p>
     *
     * <p>数据流：
     * DAG 计划
     * → 建节点/出边/入边/入度索引 + 自循环集合
     * → 选出零入度节点作为首层
     * → 循环：复核取消 → 当前层节点并行执行 → 等全部收敛 → 写回输出与证据 → 扣减下游入度 → 下一层
     * → 校验执行节点数
     * → 汇总终点输出 + 计算终点祖先证据
     * → 返回执行结果</p>
     *
     * <p>关键并发约定：当前层执行时只读上层的输出表，本层结果等全部 join 完才写回。
     * 这样同层节点之间看不到彼此的输出，结果与执行顺序无关，可复现。</p>
     *
     * <p>会逐节点调用模型并发布节点事件。存在环路时抛业务异常。</p>
     */
    private WorkflowExecutionResult executeDagPlan(WorkflowDagPlanEntity dagPlan, String tenantId, String userId,
                                                   String sessionId, String userMessage, String traceId,
                                                   String roleCode, Integer historyCutoffSequence, ChatRunEntity run) {
        // 第一层：三张索引分别服务节点查找、依赖传递和 Kahn 拓扑推进。
        Map<String, WorkflowDagPlanEntity.Node> nodeMap = dagPlan.getNodes().stream()
                .collect(Collectors.toMap(WorkflowDagPlanEntity.Node::getNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));
        // 出边表：某节点完成后该给谁扣减入度。
        Map<String, List<String>> outgoing = outgoingEdges(dagPlan);
        // 入边表：某节点执行时该拼接哪些上游的输出。
        Map<String, List<String>> incoming = incomingEdges(dagPlan);
        // 入度表：还有多少前置依赖没完成；归零即可执行。
        Map<String, Integer> indegree = indegree(dagPlan);
        // 自循环节点集合：这些节点要按配置的次数重复执行。
        Set<String> selfLoopNodeIds = selfLoopNodeIds(dagPlan);
        // 第二层：入度为零的节点没有前置依赖，构成第一层。
        List<String> ready = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> indegree.getOrDefault(nodeId, 0) == 0)
                .collect(Collectors.toCollection(ArrayList::new));
        // 一个零入度节点都没有，说明这是个旧格式计划。
        if (ready.isEmpty() && dagPlan.getRootNodeId() != null) {
            // 兼容只有显式根节点、没有普通零入度节点的旧计划。
            ready.add(dagPlan.getRootNodeId());
        }

        // 输出和证据均以节点 ID 隔离，只有依赖节点能读取上游结果。
        Map<String, String> outputs = new LinkedHashMap<>();
        // 每个节点各自累积的 RAG 证据，最后按血缘筛出终点可达的部分。
        Map<String, List<RagContextEvidence>> provenance = new LinkedHashMap<>();
        // 第三层：还有就绪节点就继续推进，直到没有节点可跑。
        while (!ready.isEmpty()) {
            // 每层开始前复核取消，取消后立刻中断，不再启动新一层的模型调用。
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            // 快照当前层节点列表，接着清空就绪表以承接下一层。
            List<String> currentLevel = new ArrayList<>(ready);
            // 清空就绪表，下面扣减入度时会往里加下一层的节点。
            ready.clear();
            // 当前层共享只读的上层输出；本层结果在全部 join 后才写回。
            List<CompletableFuture<NodeRunResult>> futures = currentLevel.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> runDagNode(nodeMap.get(nodeId), incoming.getOrDefault(nodeId, Collections.emptyList()),
                            selfLoopNodeIds.contains(nodeId), outputs, provenance, tenantId, userId, sessionId, dagPlan.getWorkflowId(),
                            userMessage, traceId, roleCode, historyCutoffSequence, run, dagPlan), workflowNodeExecutor))
                    .collect(Collectors.toList());
            // 收集本层成功的结果。
            List<NodeRunResult> levelResults = new ArrayList<>(futures.size());
            // 记住本层第一个失败，等全部收敛后再抛。
            RuntimeException levelFailure = null;
            // 第四层：逐个等待本层任务，无论成败都要等完。
            for (CompletableFuture<NodeRunResult> future : futures) {
                // 单个任务失败不能中断等待其它任务。
                try {
                    // 阻塞等这个节点的结果，并把包装异常解开成原始领域异常。
                    levelResults.add(joinNodeResult(future));
                } catch (RuntimeException exception) {
                    // 同层分支必须全部收敛后才能发布工作流终态，防止兄弟节点在 FAILED/CANCELLED 后继续写事件。
                    if (levelFailure == null) levelFailure = exception;
                }
            }
            // 本层有失败就在这里抛出，交给外层统一收尾。
            if (levelFailure != null) throw levelFailure;
            // 第五层：本层全部成功，把结果写回共享表。
            for (NodeRunResult result : levelResults) {
                // 记下这个节点的输出，供下游节点拼接提示词。
                outputs.put(result.nodeId(), result.output());
                // 记下这个节点累积的证据，最后按血缘筛选。
                provenance.put(result.nodeId(), result.evidence());
                // 给这个节点的所有下游扣减入度。
                for (String targetNodeId : outgoing.getOrDefault(result.nodeId(), Collections.emptyList())) {
                    // 每完成一个前置节点就扣减入度，归零后进入下一层。
                    int next = indegree.get(targetNodeId) - 1;
                    // 写回扣减后的入度。
                    indegree.put(targetNodeId, next);
                    // 入度归零说明全部前置依赖都完成了。
                    if (next == 0) {
                        // 加入就绪表，下一轮会执行它。
                        ready.add(targetNodeId);
                    }
                }
            }
        }

        // 第六层：核对实际执行的节点数和计划节点总数。
        if (outputs.size() != nodeMap.size()) {
            // 未执行完意味着存在非自循环环路或无法满足的依赖。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 执行失败：存在无法满足依赖的节点");
        }
        // 第七层：找出没有普通后继的节点，它们的输出就是最终答案。
        List<String> terminalIds = terminalNodeIds(dagPlan, outgoing);
        // 只保留终点节点能追溯到的证据，排除旁路节点检索但未影响答案的资料。
        List<RagContextEvidence> terminalEvidence = RAG_LINEAGE.terminal(terminalIds, provenance);
        // 汇总终点输出和终点证据作为本次执行结果。
        return new WorkflowExecutionResult(terminalOutputs(dagPlan, outgoing, outputs), terminalEvidence);
    }

    /**
     * 执行一个节点，如果它配了自循环就按次数重复执行。
     *
     * <p>各层职责：
     * 第一层：节点不存在直接拒绝——计划里的边指向了一个不存在的节点，属于计划本身有问题。
     * 第二层：把所有直接上游的输出带上节点标签拼起来。加标签是因为多父节点的内容混在一起时，
     *  模型分不清哪段来自哪个环节。
     * 第三层：合并上游累积的证据，本节点自己检索到的会追加进来，形成完整的祖先证据链。
     * 第四层：决定执行次数——只有显式自循环边才启用迭代，且次数受硬上限保护。
     * 第五层：逐轮执行。每轮开始前复核取消，构造提示词（含上一轮输出作为反馈），
     *   发节点开始事件，执行一次模型调用，发节点完成事件。
     * 第六层：某轮失败时按取消与否发不同的事件（取消 vs 失败），然后上抛。</p>
     *
     * <p>数据流：
     * 节点定义 + 上游输出表 + 上游证据表
     * → 拼接带标签的上游输出
     * → 合并上游证据
     * → 决定循环次数（自循环才 &gt;1，且钳制在硬上限内）
     * → 逐轮：复核取消 → 构造提示词 → 发 NODE_STARTED → 调模型（流式增量发 NODE_OUTPUT_DELTA）
     *   → 累积输出与证据 → 发 NODE_COMPLETED
     * → 返回节点最终输出 + 累积证据；
     * 失败：发 NODE_CANCELLED 或 NODE_FAILED → 上抛</p>
     *
     * <p>为什么每轮都要复核取消：一次自循环可能跑好几轮，每轮都是一次模型调用。
     * 不复核的话用户取消后还会白花好几轮的钱。</p>
     *
     * <p>会调用模型、会发节点事件。返回的输出是最后一轮的结果，不是各轮拼接。</p>
     */
    private NodeRunResult runDagNode(WorkflowDagPlanEntity.Node node,
                                     List<String> upstreamNodeIds,
                                     boolean selfLoop,
                                     Map<String, String> outputs,
                                     Map<String, List<RagContextEvidence>> provenance,
                                     String tenantId,
                                     String userId,
                                     String sessionId,
                                     String workflowId,
                                     String userMessage,
                                     String traceId,
                                     String roleCode,
                                     Integer historyCutoffSequence,
                                     ChatRunEntity run,
                                     WorkflowDagPlanEntity plan) {
        // 第一层：边指向了不存在的节点，属于计划本身的错误，直接拒绝执行。
        if (node == null) {
            // 抛参数非法异常，交由外层统一收尾。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 节点不存在");
        }
        // 第二层：带节点标签拼接上游输出，避免多父节点内容失去来源。
        List<String> upstreamOutputs = upstreamNodeIds.stream()
                .map(upstreamNodeId -> "[" + upstreamNodeId + "]\n" + outputs.getOrDefault(upstreamNodeId, ""))
                .collect(Collectors.toList());
        // 第三层：合并全部上游的证据作为起点，本节点自己检索到的后面会追加进来。
        List<RagContextEvidence> accumulatedEvidence = new ArrayList<>(
                RAG_LINEAGE.merge(upstreamNodeIds, provenance, List.of()));
        // 第四层：只有显式自循环边启用迭代，且次数受全局硬上限保护。
        int runTimes = selfLoop ? safeLoopTimes(node.getMaxIterations()) : 1;
        // 上一轮的输出，作为下一轮的反馈输入；首轮为空。
        String previousOutput = "";
        // 第五层：按次数逐轮执行。
        for (int index = 1; index <= runTimes; index++) {
            // 每轮调用前再次过取消门禁，防止取消后产生新的模型/工具消费。
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            // 构造本轮提示词：用户输入、上游输出、循环反馈分区呈现。
            String prompt = buildDagNodePrompt(userMessage, upstreamOutputs, previousOutput, index, runTimes);
            // 为本轮执行生成唯一编号，节点事件靠它归组。
            String nodeExecutionId = "wne_" + UUID.randomUUID();
            // 记下单调时钟起点，用于统计本轮耗时。
            long nodeStarted = System.nanoTime();
            // 打节点开始日志，带上轮次和上游数量便于分析。
            AiLog.info(AiLog.workflow().nodeStarted(tenantId, userId, sessionId, run.getRunId(),
                    workflowId, node.getNodeId(), index, runTimes, upstreamNodeIds.size()));
            // 发节点开始事件，前端据此把该节点标为运行中。
            publishWorkflowEvent(run, "NODE_STARTED", nodeExecutionId, node.getNodeId(), Map.of(
                    "nodeName", node.getNodeName(),
                    "executionIndex", index,
                    "totalIterations", runTimes,
                    "upstreamCount", upstreamNodeIds.size()));
            // 本轮执行可能失败，失败也要发事件让前端看到具体是哪个节点哪一轮出的问题。
            try {
                // 真正执行一次节点模型调用；回调把增量文本实时发成节点输出事件。
                NodeExecutionResult execution = runDagNodeOnce(node, tenantId, userId, sessionId, workflowId, prompt,
                        traceId, roleCode, historyCutoffSequence, String.join("\n\n", upstreamOutputs), run,
                        delta -> publishWorkflowEvent(run, "NODE_OUTPUT_DELTA", nodeExecutionId, node.getNodeId(),
                                Map.of("delta", delta)), plan, nodeExecutionId, false);
                // 记下本轮输出，它既是节点结果也是下一轮的反馈输入。
                previousOutput = execution.output();
                // 把本轮真实注入的证据追加进累积链。
                accumulatedEvidence.addAll(execution.evidence());
                // 打节点完成日志，带上输出长度、证据条数和耗时。
                AiLog.info(AiLog.workflow().nodeCompleted(tenantId, userId, sessionId, run.getRunId(),
                        workflowId, node.getNodeId(), index, runTimes, previousOutput.length(),
                        execution.evidence().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
                // 发节点完成事件，带上展示输出和耗时，前端据此把节点标为完成。
                publishWorkflowEvent(run, "NODE_COMPLETED", nodeExecutionId, node.getNodeId(), Map.of(
                        "displayOutput", previousOutput,
                        "executionIndex", index,
                        "costMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
            } catch (RuntimeException exception) {
                // 第六层：先打失败日志，保留耗时便于判断是超时还是立即失败。
                AiLog.error(AiLog.workflow().nodeFailed(tenantId, userId, sessionId, run.getRunId(),
                        workflowId, node.getNodeId(), index, runTimes,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted), exception));
                // 查一次权威状态，区分"是被取消"还是"真的执行失败"。
                boolean cancelled = runControlService.cancelled(tenantId, userId, run.getRunId());
                // 按取消与否发不同事件，前端展示的图标和文案不同。
                publishWorkflowEvent(run, cancelled ? "NODE_CANCELLED" : "NODE_FAILED", nodeExecutionId, node.getNodeId(), Map.of(
                        "executionIndex", index,
                        "errorCode", cancelled ? "RUN_CANCELLED"
                                : exception instanceof AppException app ? app.getCode() : "WORKFLOW_NODE_FAILED",
                        "message", safeMessage(exception),
                        "costMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
                // 异常上抛，由调度层收敛整层后统一处理。
                throw exception;
            }
        }
        // 返回节点最终输出（最后一轮结果）和累积的祖先证据。
        return new NodeRunResult(node.getNodeId(), previousOutput, List.copyOf(accumulatedEvidence));
    }

    /**
     * 执行一次节点模型调用，不需要向调度器透出增量回调的场景用这个重载。
     *
     * <p>把增量回调换成空操作后转交完整实现；主要用在不需要中间输出的内部调用路径。</p>
     */
    private NodeExecutionResult runDagNodeOnce(WorkflowDagPlanEntity.Node node, String tenantId, String userId,
                                               String sessionId, String workflowId, String prompt, String traceId,
                                               String roleCode, Integer historyCutoffSequence,
                                               String upstreamOutput, ChatRunEntity run) {
        // 增量回调换成空操作，其余流程完全一致。
        return runDagNodeOnce(node, tenantId, userId, sessionId, workflowId, prompt, traceId, roleCode,
                historyCutoffSequence, upstreamOutput, run, ignored -> { }, null, null, false);
    }

    /**
     * 真正执行一次节点模型调用，并取回这次调用实际注入的 RAG 证据。
     *
     * <p>各层职责：
     * 第一层：调用前先过取消门禁，取消后一次模型调用都不发生。
     * 第二层：取出这个节点对应的运行时 Agent 和 Runner。
     * 第三层：为"这个会话 + 这个节点 + 这一次"生成一个隔离的 ADK 会话。
     *      隔离是必须的：如果多个节点共用 ADK 会话，ADK 的内存历史会把别的节点的对话也带进来，
     *   和上下文插件注入的业务历史重复甚至冲突。
     * 第四层：生成一个证据绑定编号，让上下文插件写入的证据能精确归属到本节点这一次调用。
     * 第五层：调模型并阻塞消费事件流，把供应商的"累计快照"转成安全的增量文本往外发。
     *   每片到达前都复核取消。
     * 第六层：返回节点输出和从证据仓取回的本次调用证据快照。</p>
     *
     * <p>数据流：
     * 节点 + 提示词 + 运行身份
     * → 过取消门禁
     * → 取节点 Agent 与 Runner
     * → 建隔离 ADK 会话（会话:节点:随机）
     * → 生成证据绑定编号并写进 state
     * → 调模型，逐事件：复核取消 → 算增量 → 累积并回调发增量事件
     * → 从证据仓取本次调用的证据快照
     * → 返回输出 + 证据</p>
     *
     * <p>为什么要算增量：供应商返回的是"到目前为止的全部文本"，直接往外发前端会看到同一段话
     * 重复渲染好几遍。这里减掉已发过的部分只发新增。</p>
     *
     * <p>会调用模型，可能触发工具调用。取消时抛业务异常中断。</p>
     */
    private NodeExecutionResult runDagNodeOnce(WorkflowDagPlanEntity.Node node, String tenantId, String userId,
                                               String sessionId, String workflowId, String prompt, String traceId,
                                               String roleCode, Integer historyCutoffSequence,
                                               String upstreamOutput, ChatRunEntity run, Consumer<String> outputDelta,
                                               WorkflowDagPlanEntity plan, String nodeExecutionId,
                                               boolean routeRepairOnly) {
        // 第一层：调用前过取消门禁，取消后一次模型调用都不发生。
        runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
        // 第二层：取出这个节点使用的运行时 Agent；未装配会抛异常。
        AiAgentRegisterVO agent = requireWorkflowRuntimeAgent(node.getRuntimeAgentId());
        // 取出它的 Runner，真正的模型调用由它发起。
        InMemoryRunner runner = agent.getRunner();
        // 第三层：每个节点每次执行使用独立 ADK 会话，业务历史统一由 Context Manager 注入。
        String adkSessionId = invocationSessionId(sessionId + ":" + node.getNodeId());
        // 幂等确保这个 ADK 会话存在。
        ensureAdkSession(runner, agent.getAppName(), userId, adkSessionId);
        // 把节点提示词包成模型输入。
        Content content = Content.fromParts(Part.fromText(prompt));
        // 累积本次调用的输出文本。
        StringBuilder output = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        // 第四层：invocationId 将上下文插件写入的证据精确绑定到本节点调用。
        String evidenceInvocationId = "wf_" + node.getNodeId() + "_" + UUID.randomUUID();
        // 组装运行状态：身份、链路、上下文切面和 RAG 参数，全部来自可信来源。
        Map<String, Object> state = runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                historyCutoffSequence, upstreamOutput, run.getRunId(), run.getCurrentContextRevision(),
                ragTargetType(run, RagBindingTargetType.WORKFLOW), ragQuery(run, prompt),
                run.getRagMode(), run.getRagBindingIds(), run.getRagInvocationMode());
        // 把证据绑定编号放进状态，上下文插件写证据时会用它。
        state.put(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID, evidenceInvocationId);
        putStateIfPresent(state, ToolRuntimeContextKeys.RAG_INVOCATION_MODE, run.getRagInvocationMode());
        if (plan != null) {
            putStateIfPresent(state, ToolRuntimeContextKeys.WORKFLOW_KIND, plan.getWorkflowKind());
            putStateIfPresent(state, ToolRuntimeContextKeys.ROUTING_PROTOCOL_VERSION, plan.getRoutingProtocolVersion());
            putStateIfPresent(state, ToolRuntimeContextKeys.NODE_EXECUTION_ID, nodeExecutionId);
            putStateIfPresent(state, ToolRuntimeContextKeys.SOURCE_NODE_ID, node.getNodeId());
            putStateIfPresent(state, ToolRuntimeContextKeys.DEFINITION_HASH, plan.getDefinitionHash());
            if (plan.getVersion() != null) state.put(ToolRuntimeContextKeys.WORKFLOW_VERSION, plan.getVersion());
            state.put(ToolRuntimeContextKeys.TERMINAL_NODE, Boolean.TRUE.equals(node.getTerminal()));
            if (node.getRagToolEnabled() != null) {
                state.put(ToolRuntimeContextKeys.RAG_TOOL_ENABLED, node.getRagToolEnabled());
            }
            state.put(ToolRuntimeContextKeys.WORKFLOW_MCP_IDS,
                    node.getMcpIds() == null ? List.of() : List.copyOf(node.getMcpIds()));
            state.put(ToolRuntimeContextKeys.WORKFLOW_SKILL_IDS,
                    node.getSkillIds() == null ? List.of() : List.copyOf(node.getSkillIds()));
            state.put(ToolRuntimeContextKeys.ROUTE_DESCRIPTORS, platformRouteDescriptors(node));
            state.put(ToolRuntimeContextKeys.ROUTE_REPAIR_ONLY, routeRepairOnly);
        }
        // 第五层：prompt 在这里作为 Content 进入节点 Agent，state 提供可信工作流和运行身份。
        runner.runAsync(userId, adkSessionId, content, RunConfig.builder()
                        .streamingMode(RunConfig.StreamingMode.SSE)
                        .build(),
                        state)
                .blockingForEach(event -> {
                    // 每片到达前复核取消，取消后立即中断本节点。
                    runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
                    AgentEventContent.Snapshot snapshot = AgentEventContent.snapshot(event);
                    String thinkingDelta = contentDelta(thinking.toString(), snapshot.thinking());
                    if (!thinkingDelta.isEmpty()) {
                        appendContent(thinking, snapshot.thinking());
                        publishThinkingDelta(run, thinkingDelta, nodeExecutionId);
                    }
                    // 把供应商的累计答案快照减去已输出部分，得到本次真正新增的正文。
                    String delta = contentDelta(output.toString(), snapshot.answer());
                    // 只有确实有新内容才处理，空增量说明这片是重复快照。
                    if (!delta.isEmpty()) {
                        // 累积进节点输出。
                        output.append(delta);
                        // 回调发出增量，用于实时展示节点进度。
                        outputDelta.accept(delta);
                    }
                });
        // 第六层：返回节点输出，并从证据仓取回本次调用真实注入的证据快照。
        return new NodeExecutionResult(output.toString(), ragInvocationEvidenceStore.snapshotInvocation(
                tenantId, userId, sessionId, run.getRunId(), evidenceInvocationId));
    }

    /**
     * 供独立的智能工作流运行时调用单个已编译节点。
     *
     * <p>为什么单独开这个口：智能运行时有自己的调度器（不用这里的分层调度），但节点执行所需的
     * Agent 取用、隔离 ADK 会话、上下文装配、证据绑定这一套不该重复实现一遍。</p>
     *
     * <p>数据流：节点 + 运行 + 提示词 → 校验运行非空 → 复用单节点执行 → 返回输出 + 证据。</p>
     *
     * <p>调用前仍会过一次权威取消门禁，因此运行被取消后不会再产生新的模型或工具消费。</p>
     *
     * <p>运行为空时抛业务异常——没有运行就没有取消门禁，等于放开了一个不受控的模型调用入口。</p>
     */
    @Override
    public WorkflowNodeInvocationResultEntity invokeCompiledWorkflowNode(WorkflowDagPlanEntity.Node node,
                                                                          WorkflowDagPlanEntity plan,
                                                                          ChatRunEntity run,
                                                                          String nodeExecutionId,
                                                                          boolean routeRepairOnly,
                                                                          String sessionId,
                                                                          String workflowId,
                                                                          String prompt,
                                                                          String traceId,
                                                                          String roleCode,
                                                                          Integer historyCutoffSequence,
                                                                          String upstreamOutput) {
        // 没有运行就没有取消门禁可依据，拒绝执行以免出现不受控的模型调用。
        if (run == null) {
            // 抛参数非法异常。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "智能工作流运行不能为空");
        }
        // 复用单节点执行逻辑，身份直接从运行记录里取，保证与权威状态一致。
        boolean streamFinalAnswer = !routeRepairOnly && streamsDirectlyToEnd(node, plan);
        NodeExecutionResult result = runDagNodeOnce(node, run.getTenantId(), run.getUserId(), sessionId,
                workflowId, prompt, traceId, roleCode, historyCutoffSequence, upstreamOutput, run,
                delta -> {
                    publishWorkflowEvent(run, "NODE_OUTPUT_DELTA", nodeExecutionId, node.getNodeId(),
                            Map.of("delta", delta));
                    if (streamFinalAnswer) {
                        publishWorkflowEvent(run, "FINAL_ANSWER_DELTA", null, null, Map.of("delta", delta));
                    }
                }, plan, nodeExecutionId, routeRepairOnly);
        // 包成对外结果返回：节点输出 + 本次真实注入的证据。
        return WorkflowNodeInvocationResultEntity.builder().output(result.output()).evidence(result.evidence()).build();
    }

    /**
     * 只有当前节点无条件结束工作流时，才把它的正文同步映射到会话最终回答。
     * 多分支节点的输出只展示在节点时间线中，避免路由分析过程混进最终答复。
     */
    private boolean streamsDirectlyToEnd(WorkflowDagPlanEntity.Node node, WorkflowDagPlanEntity plan) {
        if (node == null) return false;
        if (Boolean.TRUE.equals(node.getTerminal())) return true;
        if (plan == null || plan.getEdges() == null) return false;
        List<WorkflowDagPlanEntity.Edge> outgoing = plan.getEdges().stream()
                .filter(edge -> edge != null && node.getNodeId().equals(edge.getSourceNodeId()))
                .toList();
        return !outgoing.isEmpty() && outgoing.stream()
                .allMatch(edge -> "END".equalsIgnoreCase(edge.getTargetNodeId()));
    }

    /** 从当前节点冻结出边生成路由工具描述，只暴露允许模型选择的键和目标摘要。 */
    private List<cn.bugstack.ai.domain.tool.service.PlatformToolResolver.RouteDescriptor> platformRouteDescriptors(
            WorkflowDagPlanEntity.Node node) {
        if (node == null || node.getRouteDescriptors() == null) return List.of();
        List<cn.bugstack.ai.domain.tool.service.PlatformToolResolver.RouteDescriptor> result = new ArrayList<>();
        for (WorkflowDagPlanEntity.RouteDescriptor descriptor : node.getRouteDescriptors()) {
            if (descriptor == null) continue;
            result.add(new cn.bugstack.ai.domain.tool.service.PlatformToolResolver.RouteDescriptor(
                    descriptor.getRouteKey(), descriptor.getEdgeId(), descriptor.getTargetNodeId(),
                    descriptor.getRouteAliases() == null ? List.of() : List.copyOf(descriptor.getRouteAliases())));
        }
        return List.copyOf(result);
    }

    /**
     * 用智能运行时累积的证据完成最终回答，与普通工作流共用同一套收尾逻辑。
     *
     * <p>共用的好处是引用校验规则、消息落库格式和运行终态推进只有一套实现，
     * 两条执行路径不会在"引用怎么算合法"这种关键判断上出现分歧。</p>
     *
     * <p>会写数据库（助手消息 + 运行终态），并清除本次运行的临时证据。
     * 证据传空时按空列表处理，此时模型的任何引用都会被判为非法。</p>
     */
    @Override
    public void completeCompiledWorkflowRun(ChatRunEntity run, String output, String traceId,
                                            List<RagContextEvidence> evidence) {
        // 转交统一收尾；证据为空时用空列表，保证校验器拿到的一定是可遍历的集合。
        completeRunWithAssistant(run.getTenantId(), run.getUserId(), run.getRunId(), output, traceId,
                evidence == null ? List.of() : evidence);
    }

    /**
     * 拼出一个结构固定的节点提示词：用户输入、上游结果、循环反馈分区呈现。
     *
     * <p>各层职责：
     * 第一层：先放用户本轮输入，它是整个工作流的原始目标。
     * 第二层：有上游输出就单独起一段，让模型清楚哪些内容来自前置环节。
     * 第三层：处于循环中就告知当前轮次和总轮数，并附上上一轮的产出作为改进依据。
     * 第四层：最后加一句约束，明确只做本节点的事。</p>
     *
     * <p>数据流：用户输入 + 上游输出 + 上一轮输出 + 轮次信息 → 分区拼接 → 加职责约束 → 返回提示词。</p>
     *
     * <p>为什么最后要加"只完成你这个节点的任务"：模型看到完整的用户目标后，很容易自己一口气
     * 把后面几个节点的活也干了，导致后续节点的工作变成重复劳动，输出也会互相打架。</p>
     *
     * <p>拼接顺序固定，因此同样输入永远得到同样提示词，便于复现问题。</p>
     */
    private String buildDagNodePrompt(String userMessage, List<String> upstreamOutputs, String previousOutput, int loopIndex, int loopTotal) {
        // 按行收集各分区内容，最后统一用换行连接。
        List<String> lines = new ArrayList<>();
        // 第一层：先摆明用户本轮的原始输入。
        lines.add("用户本轮输入：");
        // 输入为空时放空串，保持分区结构完整。
        lines.add(userMessage == null ? "" : userMessage);
        // 第二层：有上游输出才加这一段，避免出现空标题。
        if (!upstreamOutputs.isEmpty()) {
            // 起一个独立分区标题，前面留空行让模型更容易区分。
            lines.add("\n上游节点输出：");
            // 各上游输出之间空一行，它们已经带了节点标签。
            lines.add(String.join("\n\n", upstreamOutputs));
        }
        // 第三层：只有真的在循环中才告知轮次，单次执行不需要这些噪音。
        if (loopTotal > 1) {
            // 起循环信息分区。
            lines.add("\n循环信息：");
            // 明确当前是第几轮、共几轮，模型据此判断该收敛还是继续发散。
            lines.add("当前是第 " + loopIndex + " / " + loopTotal + " 次执行。");
            // 有上一轮产出才附上，首轮没有可参考的内容。
            if (previousOutput != null && !previousOutput.isBlank()) {
                // 标明这是上一轮的结果。
                lines.add("上一轮输出：");
                // 附上上一轮正文，作为本轮改进的基础。
                lines.add(previousOutput);
            }
        }
        // 第四层：收窄节点职责，避免模型自行越过图路由执行其他节点。
        lines.add("\n请只完成你这个节点的任务，并输出本节点结果。");
        // 用换行连接成最终提示词。
        return String.join("\n", lines);
    }

    /**
     * 确认运行时计划里真的有可执行的节点。
     *
     * <p>四重判空：运行时、计划、节点列表、节点列表非空。任一为空都说明工作流定义有问题，
     * 继续往下跑只会在后面某个地方抛空指针，报错信息还不知所云。</p>
     *
     * <p>不通过就抛业务异常，前端能收到明确的"工作流计划不存在"提示。</p>
     */
    private WorkflowDagPlanEntity requireDagPlan(WorkflowRuntimeEntity runtime) {
        // 四重判空，任一缺失都视为计划不可执行。
        if (runtime == null || runtime.getDagPlan() == null || runtime.getDagPlan().getNodes() == null || runtime.getDagPlan().getNodes().isEmpty()) {
            // 抛业务异常，让问题在执行前就暴露。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 运行计划不存在");
        }
        // 返回可执行的计划。
        return runtime.getDagPlan();
    }

    /**
     * 建出边索引：每个节点完成后该给哪些下游节点扣减入度。
     *
     * <p>各层职责：
     * 第一层：为每个节点先放一个空列表，保证后面按 key 取时不会拿到 null。
     * 第二层：没有边的计划（单节点工作流）直接返回全空索引。
     * 第三层：逐条边填充，跳过自循环边和端点不在计划内的边。</p>
     *
     * <p>数据流：DAG 计划 → 为每个节点建空出边表 → 逐条边过滤后填充 → 返回出边索引。</p>
     *
     * <p>为什么排除自循环：自循环表达的是"这个节点自己重复几次"，不是依赖关系。
     * 把它算进拓扑，节点的入度永远减不到零，整个 DAG 会卡死。</p>
     *
     * <p>为什么排除计划外端点：边指向了一个不在节点列表里的 ID（脏数据），
     * 放进索引会导致后面按 key 取入度时抛空指针。</p>
     */
    private Map<String, List<String>> outgoingEdges(WorkflowDagPlanEntity dagPlan) {
        // 第一层：用有序 Map 保证遍历顺序稳定，便于复现问题。
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        // 为每个节点预置空列表，避免后续取值为 null。
        dagPlan.getNodes().forEach(node -> outgoing.put(node.getNodeId(), new ArrayList<>()));
        // 第二层：没有边就直接返回全空索引。
        if (dagPlan.getEdges() == null) {
            // 返回只含空列表的索引。
            return outgoing;
        }
        // 第三层：逐条边填充。
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            // 计划外端点和自循环都不能进入普通拓扑传播。
            if (isSelfLoop(edge) || !outgoing.containsKey(edge.getSourceNodeId()) || !outgoing.containsKey(edge.getTargetNodeId())) {
                // 跳过这条边。
                continue;
            }
            // 记录：源节点完成后要通知这个目标节点。
            outgoing.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
        }
        // 返回出边索引。
        return outgoing;
    }

    /**
     * 建入边索引：每个节点执行时该拼接哪些上游节点的输出。
     *
     * <p>结构和过滤规则与出边索引完全一致，只是方向相反：这里记录的是"谁是我的上游"。</p>
     *
     * <p>数据流：DAG 计划 → 为每个节点建空入边表 → 逐条边过滤后反向填充 → 返回入边索引。</p>
     *
     * <p>同样排除自循环和计划外端点，理由与出边索引相同。</p>
     */
    private Map<String, List<String>> incomingEdges(WorkflowDagPlanEntity dagPlan) {
        // 用有序 Map 保证遍历顺序稳定。
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        // 为每个节点预置空列表。
        dagPlan.getNodes().forEach(node -> incoming.put(node.getNodeId(), new ArrayList<>()));
        // 没有边就直接返回全空索引。
        if (dagPlan.getEdges() == null) {
            // 返回只含空列表的索引。
            return incoming;
        }
        // 逐条边反向填充。
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            // 自循环和计划外端点一律跳过，理由同出边索引。
            if (isSelfLoop(edge) || !incoming.containsKey(edge.getSourceNodeId()) || !incoming.containsKey(edge.getTargetNodeId())) {
                // 跳过这条边。
                continue;
            }
            // 记录：目标节点的上游包含这个源节点。
            incoming.get(edge.getTargetNodeId()).add(edge.getSourceNodeId());
        }
        // 返回入边索引。
        return incoming;
    }

    /**
     * 算出每个节点还有多少前置依赖，供拓扑调度判断谁可以执行。
     *
     * <p>入度为零表示没有未完成的前置依赖，可以立即执行；每完成一个前置就扣减一次，
     * 归零时进入下一批可执行节点。这就是标准的 Kahn 拓扑排序做法。</p>
     *
     * <p>数据流：DAG 计划 → 每个节点入度初始化为 0 → 逐条边过滤后给目标节点加一 → 返回入度表。</p>
     *
     * <p>同样排除自循环：自循环会让节点入度永远减不到零，整个调度直接卡死。</p>
     */
    private Map<String, Integer> indegree(WorkflowDagPlanEntity dagPlan) {
        // 用有序 Map 保证遍历顺序稳定。
        Map<String, Integer> indegree = new LinkedHashMap<>();
        // 所有节点入度先置零。
        dagPlan.getNodes().forEach(node -> indegree.put(node.getNodeId(), 0));
        // 没有边说明所有节点都是零入度，可以直接并行跑。
        if (dagPlan.getEdges() == null) {
            // 返回全零入度表。
            return indegree;
        }
        // 逐条边给目标节点累加入度。
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            // 自循环和计划外端点不计入依赖，否则节点永远等不到执行。
            if (isSelfLoop(edge) || !indegree.containsKey(edge.getSourceNodeId()) || !indegree.containsKey(edge.getTargetNodeId())) {
                // 跳过这条边。
                continue;
            }
            // 目标节点多了一个前置依赖。
            indegree.put(edge.getTargetNodeId(), indegree.get(edge.getTargetNodeId()) + 1);
        }
        // 返回入度表。
        return indegree;
    }

    /**
     * 收集所有配了自循环的节点编号。
     *
     * <p>节点执行器拿到这个集合后，才会对集合里的节点启用"重复执行若干轮"的逻辑；
     * 不在集合里的节点一律只跑一次。</p>
     *
     * <p>数据流：DAG 计划 → 筛出自循环边 → 取源节点编号 → 去重成集合。</p>
     *
     * <p>没有边时返回空集合，所有节点都只跑一次。</p>
     */
    private Set<String> selfLoopNodeIds(WorkflowDagPlanEntity dagPlan) {
        // 没有边就没有自循环。
        if (dagPlan.getEdges() == null) {
            // 返回不可变空集合。
            return Collections.emptySet();
        }
        // 筛出自循环边，取它们的源节点编号去重成集合。
        return dagPlan.getEdges().stream()
                .filter(this::isSelfLoop)
                .map(WorkflowDagPlanEntity.Edge::getSourceNodeId)
                .collect(Collectors.toSet());
    }

    /**
     * 判断一条边是不是自循环（起点和终点是同一个节点）。
     *
     * <p>要求编号非空且相等：如果源和目标都是 null，按对象相等判断会误判成自循环，
     * 这条脏数据边就会让一个正常节点变成无限循环。</p>
     */
    private boolean isSelfLoop(WorkflowDagPlanEntity.Edge edge) {
        // 仅源、目标均为同一非空 ID 时认定为自循环。
        return edge != null && edge.getSourceNodeId() != null && edge.getSourceNodeId().equals(edge.getTargetNodeId());
    }

    /**
     * 把所有终点节点的输出按计划节点顺序拼成最终答案。
     *
     * <p>数据流：终点节点列表 → 按计划顺序取各自输出 → 过滤空输出 → 用换行连接。</p>
     *
     * <p>为什么按计划顺序而不是完成顺序：并行终点节点的完成顺序是不确定的，
     * 按完成顺序拼接会让同样的输入产生不同顺序的答案，无法复现也无法测试。</p>
     *
     * <p>过滤空输出是为了避免答案里出现连续空行——某个终点节点没产出内容是允许的。</p>
     */
    private String terminalOutputs(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing, Map<String, String> outputs) {
        // 按计划顺序取终点节点，逐个取输出，丢掉空内容后用换行连接。
        return terminalNodeIds(dagPlan, outgoing).stream()
                .map(nodeId -> outputs.getOrDefault(nodeId, ""))
                .filter(output -> output != null && !output.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 找出所有终点节点：没有普通后继边的节点。
     *
     * <p>它们的输出就是工作流的最终答案，它们的祖先证据就是合法引用范围。</p>
     *
     * <p>数据流：DAG 计划 + 出边索引 → 筛出出边为空的节点 → 为空则兜底取最后一个节点 → 返回列表。</p>
     *
     * <p>为什么要兜底：正常 DAG 一定有终点，找不到终点说明计划有环（历史脏数据）。
     * 这时取最后一个节点的输出，至少能给用户一个答案，而不是抛异常让整次对话失败。
     * 但这只是降级，答案可能不完整。</p>
     */
    private List<String> terminalNodeIds(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing) {
        // 出边为空说明没有下游，就是终点节点。
        List<String> terminalNodeIds = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> outgoing.getOrDefault(nodeId, Collections.emptyList()).isEmpty())
                .collect(Collectors.toList());
        // 一个终点都找不到，说明计划里存在环路，属于异常数据。
        if (terminalNodeIds.isEmpty()) {
            // 兜底取最后一个节点，保证至少能给出一个答案而不是整体失败。
            return List.of(dagPlan.getNodes().get(dagPlan.getNodes().size() - 1).getNodeId());
        }
        // 返回终点节点列表。
        return terminalNodeIds;
    }

    /**
     * 等一个并行节点的结果，并把并发框架包装的异常还原成原始领域异常。
     *
     * <p>为什么必须解包：CompletableFuture 会把所有异常裹进 CompletionException。
     * 不解包的话，上层看到的是一个通用的并发异常，取消、参数非法这些具体原因全被埋掉了，
     * 也就无法据此判断该写失败消息还是清证据。</p>
     *
     * <p>数据流：Future → join → 正常返回结果；异常则取出根因 → 是运行时异常则原样抛出
     * → 否则包成通用业务异常抛出。</p>
     *
     * <p>受检异常没法原样抛，只能包成业务异常并保留原始消息。</p>
     */
    private NodeRunResult joinNodeResult(CompletableFuture<NodeRunResult> future) {
        // 阻塞等结果；失败会抛 CompletionException。
        try {
            // 正常拿到节点结果。
            return future.join();
        } catch (CompletionException e) {
            // 取出真正的根因；没有根因就用它自己。
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 运行时异常可以原样抛出，保留取消、参数非法等具体语义。
            if (cause instanceof RuntimeException runtimeException) {
                // 原样上抛，让上层按具体类型分别处理。
                throw runtimeException;
            }
            // 受检异常无法原样抛出，包成通用业务异常并保留原始消息。
            throw new AppException(ResponseCode.UN_ERROR.getCode(), cause.getMessage());
        }
    }

    /**
     * 把配置的循环次数钳制在 1 到 20 之间。
     *
     * <p>为什么必须有硬上限：每一轮都是一次真实的模型调用。配置里写了 1000 次（或者被误改成很大的值），
     * 一次对话就能烧掉巨额额度，而且用户在界面上看不出异常，只觉得"怎么这么慢"。</p>
     *
     * <p>下限取 1 是因为循环至少要执行一次；空值和小于 1 的值都按 1 处理，不报错。</p>
     */
    private int safeLoopTimes(Integer maxIterations) {
        // 没配或配成非正数都按只跑一次处理。
        if (maxIterations == null || maxIterations < 1) {
            // 返回 1，至少执行一轮。
            return 1;
        }
        // 上限钳到 20，防止错误配置无限消耗模型额度。
        return Math.min(maxIterations, 20);
    }

    /**
     * 为没有业务运行记录的兼容入口构造一份最小运行状态。
     *
     * <p>把运行编号、上下文版本和 RAG 相关参数全部传空后转交完整实现。
     * 结果是插件拿不到运行身份，也就不会启用 RAG 和取消门禁——这正是兼容入口该有的行为，
     * 因为它们本来就没有运行记录可依据。</p>
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput) {
        // 运行、版本和 RAG 参数全传空，插件据此按"无运行上下文"处理。
        return runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                visibleThroughSequence, upstreamOutput, null, null, null, null, null, List.of(), null);
    }

    /**
     * 组装插件唯一可信的运行状态：身份、链路、上下文切面和 RAG 参数。
     *
     * <p>各层职责：
     * 第一层：写链路标识。同时写 ADK 的键和项目工具的键，兼容日志插件和工具网关两套读取方式。
     * 第二层：写身份四件套（租户、用户、会话、工作流）和运行编号，它们是所有权限判断的依据。
     * 第三层：写上下文版本。它锁定本轮可见的历史快照，异步压缩完成后也不会污染进行中的调用。
     * 第四层：写角色和上游输出，供工具鉴权和节点提示词使用。
     * 第五层：只有运行开始时就固化为启用 RAG，才写 RAG 目标和绑定快照——
     *  中途改会话设置不影响进行中的运行。
     * 第六层：写历史可见序号，并把附件可见序号设为它加一。
     *   差这个 1 是关键：历史消息要截止到本轮输入之前（否则本轮输入会被当成历史重复喂进去），
     *  但附件必须包含刚绑定到本轮用户消息的那些，否则模型看不到用户刚上传的文件。</p>
     *
     * <p>数据流：
     * 可信身份与切面参数
     * → 写链路标识（双键）
     * → 写租户/用户/会话/工作流/运行编号（空值跳过）
     * → 写上下文版本
     * → 写角色与上游输出
     * → 启用 RAG 时写目标类型/目标ID/模式/绑定列表/查询词
     * → 写历史可见序号 + 附件可见序号（+1）
     * → 返回状态表交给 ADK</p>
     *
     * <p>为什么身份绝不能取自模型文本：state 是插件和工具判断"这是谁的请求"的唯一依据。
     * 一旦让模型能影响它，模型只要在输出里编一个别人的租户号，就能读到别人的数据。</p>
     *
     * <p>空值一律不写入：插件靠"键是否存在"判断能力是否启用，写一个空值会让插件误以为
     * "有身份但值是空的"，从而做出错误判断。</p>
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput,
                                                  String runId, Long contextRevision,
                                                  RagBindingTargetType ragTargetType, String ragQuery,
                                                   String ragMode, List<String> ragBindingIds,
                                                   String ragInvocationMode) {
        // 状态表交给 ADK 后由插件和工具读取。
        Map<String, Object> state = new HashMap<>();
        // 第一层：同时写 ADK 链路键和项目工具键，兼容日志插件与工具网关。
        putStateIfPresent(state, TraceContext.TRACE_ID_STATE_KEY, traceId);
        // 项目自己的工具键，工具网关按它取链路标识。
        putStateIfPresent(state, ToolRuntimeContextKeys.TRACE_ID, traceId);
        // 第二层：租户是所有数据隔离的基准。
        putStateIfPresent(state, ToolRuntimeContextKeys.TENANT_ID, tenantId);
        // 用户决定工具能读写谁的数据。
        putStateIfPresent(state, ToolRuntimeContextKeys.USER_ID, userId);
        // 会话决定上下文从哪段历史里取。
        putStateIfPresent(state, ToolRuntimeContextKeys.SESSION_ID, sessionId);
        // 工作流编号，工作流场景下同时作为 RAG 绑定目标。
        putStateIfPresent(state, ToolRuntimeContextKeys.WORKFLOW_ID, workflowId);
        // Agent 编排权限只能来自静态配置，绝不从模型参数或用户消息读取。
        AiAgentConfigTableVO.Agent publicAgent = staticAgent(workflowId);
        if (publicAgent != null) {
            putStateIfPresent(state, ToolRuntimeContextKeys.AGENT_ID, publicAgent.getAgentId());
            putStateIfPresent(state, ToolRuntimeContextKeys.ORCHESTRATION_ROLE, publicAgent.getOrchestrationRole());
            state.put(ToolRuntimeContextKeys.ALLOWED_SUB_AGENT_IDS,
                    publicAgent.getAllowedSubAgentIds() == null ? List.of() : List.copyOf(publicAgent.getAllowedSubAgentIds()));
        }
        // 运行编号，取消门禁和证据绑定都靠它。
        putStateIfPresent(state, ToolRuntimeContextKeys.RUN_ID, runId);
        putStateIfPresent(state, ToolRuntimeContextKeys.ORCHESTRATION_ROOT_RUN_ID,
                AgentOrchestrationContextHolder.getRootRunId() == null
                        ? runId : AgentOrchestrationContextHolder.getRootRunId());
        state.put(ToolRuntimeContextKeys.ORCHESTRATION_SUMMARY_ONLY,
                AgentOrchestrationContextHolder.isSummaryOnly());
        putStateIfPresent(state, ToolRuntimeContextKeys.RAG_INVOCATION_MODE, ragInvocationMode);
        // 第三层：上下文版本存在才写；它是版本冲突检测的基准。
        if (contextRevision != null) {
            // 上下文版本锁定本轮可见快照，压缩完成后也不能污染进行中的调用。
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, contextRevision);
        }
        // 第四层：角色用于工具级鉴权。
        putStateIfPresent(state, "roleCode", roleCode);
        // 上游输出供工作流节点拼接提示词，普通对话为空。
        putStateIfPresent(state, ToolRuntimeContextKeys.CONTEXT_UPSTREAM_OUTPUT, upstreamOutput);
        // 第五层：只有运行开始时固化为启用，才向上下文插件暴露 RAG 目标和绑定快照。
        if (ragTargetType != null) {
            // 目标类型决定按 Agent 还是按工作流去找知识库绑定。
            state.put(ToolRuntimeContextKeys.RAG_TARGET_TYPE, ragTargetType.name());
            // 目标编号，工作流场景下就是工作流编号。
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_TARGET_ID, workflowId);
            // 检索模式，决定是必需检索还是尽力检索。
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_MODE, ragMode);
            // 绑定的知识库列表；用不可变副本，避免插件改动影响运行快照。
            state.put(ToolRuntimeContextKeys.RAG_BINDING_IDS,
                    ragBindingIds == null ? List.of() : List.copyOf(ragBindingIds));
            // 检索查询词，通常就是用户这轮的问题或节点提示词。
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_QUERY, ragQuery);
        }
        // 第六层：有可见序号才写上下文范围。
        if (visibleThroughSequence != null) {
            // 历史消息可见到这个序号为止。
            state.put(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE, visibleThroughSequence);
            // 历史消息截止到本轮输入之前；附件必须包含刚绑定到本轮用户消息的资产。
            state.put(ToolRuntimeContextKeys.CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE,
                    visibleThroughSequence + 1);
        }
        // 返回组装好的可信状态表。
        return state;
    }

    /** 按对外编号读取静态 Agent 定义；内部工作流运行体不会命中，也不会获得主 Agent 权限。 */
    private AiAgentConfigTableVO.Agent staticAgent(String agentId) {
        if (agentId == null || aiAgentAutoConfigProperties.getTables() == null) return null;
        return aiAgentAutoConfigProperties.getTables().values().stream()
                .map(AiAgentConfigTableVO::getAgent).filter(value -> value != null && agentId.equals(value.getAgentId()))
                .findFirst().orElse(null);
    }

    private boolean isSupervisor(String agentId) {
        AiAgentConfigTableVO.Agent agent = staticAgent(agentId);
        return agent != null && "SUPERVISOR".equalsIgnoreCase(agent.getOrchestrationRole());
    }

    /**
     * 只有值真正有内容时才写进状态表。
     *
     * <p>为什么不写空值：插件判断能力是否可用的方式是"这个键在不在"。写一个空字符串进去，
     * 插件会认为"身份存在但值是空的"，然后拿空租户号去查库——那会查到不属于任何人的数据，
     * 或者干脆报一个莫名其妙的错。宁可让键不存在，让插件走明确的"无身份"分支。</p>
     */
    private void putStateIfPresent(Map<String, Object> state, String key, String value) {
        // 键和值都必须有内容才写入，空白值一律跳过。
        if (key != null && value != null && !value.isBlank()) {
            // 写入状态表。
            state.put(key, value);
        }
    }

    /**
     * 判断本次调用要不要启用 RAG，只看运行快照里固化的开关。
     *
     * <p>为什么只看运行快照：RAG 开关在运行创建时就固化了。如果每次模型调用都实时读会话设置，
     * 用户在对话进行中改了开关，就会出现"前半段有检索、后半段没检索"这种无法解释的行为，
     * 引用校验也会因为前后基准不一致而误判。</p>
     *
     * <p>不启用时返回空，插件据此完全跳过检索。</p>
     */
    private RagBindingTargetType ragTargetType(ChatRunEntity run, RagBindingTargetType targetType) {
        // 仅按运行快照决定 RAG，忽略会话设置的后续变更。
        return run != null && Boolean.TRUE.equals(run.getRagEnabled()) ? targetType : null;
    }

    /**
     * 决定要不要把检索查询词传给插件。
     *
     * <p>禁用 RAG 时不传查询，确保插件不会因为"有查询词"而误触发一次检索——
     * 那会白花一次向量检索的开销，还可能把不该出现的资料注入上下文。</p>
     */
    private String ragQuery(ChatRunEntity run, String query) {
        // 只有运行快照里启用了 RAG 才传查询词。
        return run != null && Boolean.TRUE.equals(run.getRagEnabled()) ? query : null;
    }

    /**
     * 算出历史上下文该看到第几条消息为止。
     *
     * <p>取本轮用户消息的序号减一：本轮输入由 Runner 单独作为 Content 传给模型，
     * 如果历史里也包含它，模型就会看到同一句话两遍，容易产生自我重复的回答。</p>
     *
     * <p>用 max 兜住下限：第一条消息的序号是 1，减一得 0，表示没有历史可看，这是正确的。
     * 但如果序号异常为 0，减一会得到 -1，那是个无意义的值。</p>
     *
     * <p>消息或序号缺失时返回 0，等于"没有历史"，比猜一个数字安全。</p>
     */
    private Integer historyCutoff(ChatMessageEntity userMessage) {
        // 消息或序号缺失时按无历史处理。
        if (userMessage == null || userMessage.getSequenceNo() == null) {
            // 返回 0 表示不注入任何历史。
            return 0;
        }
        // 截止到本轮输入之前，并用 max 兜住下限避免负数。
        return Math.max(0, userMessage.getSequenceNo() - 1);
    }

    /**
     * 为每一次模型调用生成一个专属的 ADK 会话编号。
     *
     * <p>为什么每次都新建：ADK 自己会在会话里累积对话历史。而本系统的历史是由上下文插件
     * 从数据库读出来注入的。两份历史叠加，模型就会看到同一段对话两遍。
     * 让每次调用都用一个全新的 ADK 会话，ADK 侧就永远是空历史，只有插件注入的那一份。</p>
     *
     * <p>格式带上业务会话编号是为了排查时能一眼看出它属于哪个会话。</p>
     */
    private String invocationSessionId(String businessSessionId) {
        // 业务会话编号 + 固定标记 + 随机串，保证每次调用都是全新的隔离会话。
        return businessSessionId + ":inv:" + UUID.randomUUID();
    }

    /**
     * 会话缺失时按公共 Agent 入口新建一个。
     *
     * <p>用在没有现成运行体的调用点上；建会话过程会自己去查一次注册表。</p>
     */
    private String ensureSessionId(String agentId, String userId, String sessionId) {
        // 空值和纯空白都算没有会话。
        if (sessionId == null || sessionId.isBlank()) {
            // 走公共入口新建会话，包含完整的准入校验。
            return createSession(agentId, userId);
        }
        // 已有会话就原样返回。
        return sessionId;
    }

    /**
     * 会话缺失时新建，复用调用方已经校验过的运行体。
     *
     * <p>和上一个重载的区别：直接用现成运行体，省掉一次注册表查询。
     * 用在已经做过准入校验的路径上。</p>
     */
    private String ensureSessionId(String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        // 空值和纯空白都算没有会话。
        if (sessionId == null || sessionId.isBlank()) {
            // 复用已校验的运行体建会话，避免重复查找。
            return createSession(sessionAgentId, userId, aiAgentRegisterVO);
        }
        // 已有会话就原样返回。
        return sessionId;
    }

    /**
     * 会话缺失时在显式指定的租户下新建。
     *
     * <p>用在已经取好租户的路径上，避免在同一次请求里重复读认证上下文，
     * 也保证整个流程用的是同一个租户值。</p>
     */
    private String ensureSessionId(String tenantId, String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        // 空值和纯空白都算没有会话。
        if (sessionId == null || sessionId.isBlank()) {
            // 用显式租户建会话，保证与调用方使用同一个租户值。
            return createSession(tenantId, sessionAgentId, userId, aiAgentRegisterVO);
        }
        // 已有会话就原样返回。
        return sessionId;
    }

    /**
     * 工作流会话缺失时新建，并把本次解析出的真实版本和模型固化进去。
     *
     * <p>固化很关键：会话建好后就一直按这个版本和模型跑，别人发布新版本不会让进行中的对话中途变样。</p>
     */
    private String ensureWorkflowSessionId(String tenantId, String workflowId, String userId, String sessionId,
                                           AiAgentRegisterVO aiAgentRegisterVO, WorkflowRuntimeEntity runtime) {
        // 空值和纯空白都算没有会话。
        if (sessionId == null || sessionId.isBlank()) {
            // 建工作流会话并固化本次已解析的实际版本和模型。
            return createWorkflowSession(tenantId, workflowId, userId, aiAgentRegisterVO, runtime);
        }
        // 已有会话就原样返回。
        return sessionId;
    }

    /**
     * 公共入口的准入三连：必须是静态 Agent、必须未被租户禁用、必须已装配成功。
     *
     * <p>各层职责：
     * 第一层：不是静态配置里的 Agent 就拒绝。这道判断挡住了用户拿工作流内部运行时 Agent 的 ID
     *   直接建会话——那等于绕过了工作流的权限校验。
     * 第二层：过租户启停判断，被管理员关掉的 Agent 不产生任何模型消费。
     * 第三层：从装配仓取运行体，取不到说明装配没完成或配置无效。</p>
     *
     * <p>数据流：agentId → 静态 Agent 校验 → 租户可用性校验 → 取注册运行体 → 返回运行体。</p>
     *
     * <p>三道校验都在建会话和调模型之前完成，保证不合规的请求不会留下任何痕迹或产生费用。</p>
     */
    private AiAgentRegisterVO requirePublicAgent(String agentId) {
        // 第一层：公共入口只接受静态 Agent，挡住直接指定内部运行时 Agent 的绕行。
        if (!agentAvailabilityService.isStaticAgent(agentId)) {
            // 抛"Agent 不存在"类错误码。
            throw new AppException(ResponseCode.E0001.getCode(), ResponseCode.E0001.getInfo());
        }
        // 第二层：执行租户可用性校验，被禁用时抛异常中断。
        agentAvailabilityService.assertEnabled(currentTenantId(), agentId);
        // 第三层：取出已装配的运行体，取不到会抛异常。
        return requireRegisteredAgent(agentId);
    }

    /**
     * 取一个已由工作流授权并编译出来的内部运行时 Agent。
     *
     * <p>不做静态 Agent 校验，也不做租户启停校验——因为工作流服务在编译运行时的时候
     * 已经做过完整的权限判断了，这里再判一次只会误伤（内部 Agent 本来就不在静态配置里）。</p>
     *
     * <p>正因为它跳过了校验，绝对不能用它处理外部传入的 agentId，否则等于开了一个免校验后门。</p>
     */
    private AiAgentRegisterVO requireWorkflowRuntimeAgent(String agentId) {
        // 直接取运行体；权限已在工作流编译阶段校验过。
        return requireRegisteredAgent(agentId);
    }

    /**
     * 从装配仓按 agentId 取运行体，取不到就抛异常。
     *
     * <p>取不到的原因通常是两种：启动装配没执行（配置开关关着），或者配置有问题导致这个 Agent
     * 装配失败。两种都属于部署问题，直接报错比降级更容易被发现和修复。</p>
     */
    private AiAgentRegisterVO requireRegisteredAgent(String agentId) {
        // 从装配仓读运行体；缺失表示启动装配未完成或配置无效。
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        // 取不到就明确报错，不做降级。
        if (null == aiAgentRegisterVO) {
            // 抛"Agent 不存在"错误码。
            throw new AppException(ResponseCode.E0001.getCode());
        }
        // 返回可用的运行体。
        return aiAgentRegisterVO;
    }

    /**
     * 幂等地确保本次调用要用的隔离 ADK 会话存在。
     *
     * <p>先查后建：因为同一个隔离会话可能在一次调用里被多次访问（比如重试），
     * 每次都建会报"已存在"的错。</p>
     *
     * <p>数据流：查 ADK 会话 → 存在则直接返回；不存在则以空状态创建。</p>
     *
     * <p>初始状态刻意留空：这个 ADK 会话不承载任何历史，业务历史全部由上下文插件在
     * 每次模型调用前注入。这样 ADK 侧永远不会和数据库里的历史重复。</p>
     *
     * <p>并发创建交给 ADK 会话服务处理，这里不额外加锁。</p>
     */
    private void ensureAdkSession(InMemoryRunner runner, String appName, String userId, String sessionId) {
        // 先查这个隔离会话是否已经存在。
        Session session = runner.sessionService()
                .getSession(appName, userId, sessionId, Optional.empty())
                .blockingGet();
        // 不存在才创建，保证方法可以被安全地重复调用。
        if (session == null) {
            // 并发创建由 ADK 会话服务处理；状态初始为空，历史由项目上下文插件注入。
            runner.sessionService()
                    .createSession(appName, userId, new ConcurrentHashMap<>(), sessionId)
                    .blockingGet();
        }
    }

    /**
     * 原子绑定无附件用户消息与运行，并通知 Context Manager。
     */
    private RunMessageBindingEntity saveRunUserMessage(String tenantId, String userId, String runId,
                                                        String content, String traceId) {
        return saveRunUserMessage(tenantId, userId, runId, content, traceId, List.of());
    }

    /** 原子绑定用户消息、附件与运行；成功后才推进上下文派生状态。 */
    private RunMessageBindingEntity saveRunUserMessage(String tenantId, String userId, String runId,
                                                        String content, String traceId, List<String> attachmentIds) {
        RunMessageBindingEntity binding = runControlService.appendUserMessage(
                tenantId, userId, runId, content, traceId, attachmentIds);
        conversationMemoryService.onMessageSaved(binding.getMessage());
        return binding;
    }

    /**
     * 从证据仓读取普通 Agent 的本轮证据，再完成运行。
     */
    private boolean completeRunWithAssistant(String tenantId, String userId, String runId,
                                             String content, String traceId) {
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        return completeRunWithAssistant(tenantId, userId, runId, content, traceId,
                ragInvocationEvidenceStore.snapshot(run.getTenantId(), run.getUserId(), run.getSessionId(), runId));
    }

    /** 引用校验完成后原子保存普通 DAG 的最终消息和事件终态。 */
    private void completeWorkflowRunWithAssistant(ChatRunEntity run, String content,
                                                   List<RagContextEvidence> evidence, int executedNodes) {
        RagAnswerCitationValidation validation = ragAnswerCitationValidator.validate(content, evidence);
        ChatMessageEntity message = workflowRunFinalizationService.complete(run, content,
                citationMetadata(validation), executedNodes);
        clearEvidence(run);
        if (message != null) conversationMemoryService.onAssistantMessageSaved(message);
    }

    /** 原子保存普通 DAG 的失败消息与失败事件，再清理临时证据。 */
    private void failWorkflowRunWithAssistantError(ChatRunEntity run, RuntimeException exception) {
        String reason = safeMessage(exception);
        ChatMessageEntity message = workflowRunFinalizationService.fail(run,
                errorContent(exception, ""), reason,
                exception instanceof AppException app ? app.getCode() : "WORKFLOW_EXECUTION_FAILED");
        if (message != null) conversationMemoryService.onMessageSaved(message);
        clearEvidence(run);
    }

    /** 校验回答引用、原子写助手消息与成功终态，再清除临时证据。 */
    private boolean completeRunWithAssistant(String tenantId, String userId, String runId,
                                             String content, String traceId, List<RagContextEvidence> evidence) {
        if (AgentOrchestrationContextHolder.isSummaryOnly() && (content == null || content.isBlank())) {
            throw new AppException("PARENT_RESUME_EMPTY_OUTPUT", "主 Agent 恢复未生成有效汇总，将保留任务并重试");
        }
        if (parentWaitAllFinalizationService != null
                && parentWaitAllFinalizationService.completeAsDraftIfWaiting(tenantId, userId, runId, content)) {
            clearEvidence(runControlService.require(tenantId, userId, runId));
            return true;
        }
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        // 引用白名单校验先于落库，消息元数据保存可审计的接受/拒绝结果。
        RagAnswerCitationValidation validation = ragAnswerCitationValidator.validate(content, evidence);
        ChatMessageEntity message = runControlService.completeWithAssistantMessage(tenantId, userId, runId,
                content, traceId, citationMetadata(validation));
        ragInvocationEvidenceStore.clear(run.getTenantId(), run.getUserId(), run.getSessionId(), runId);
        if (message != null) {
            conversationMemoryService.onAssistantMessageSaved(message);
        }
        return false;
    }

    /** 防止响应完成与取消/异常竞态重复写助手消息。 */
    private boolean completeRunWithAssistantOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                                 String content, String traceId) {
        if (!saved.compareAndSet(false, true)) return false;
        try {
            return completeRunWithAssistant(tenantId, userId, runId, content, traceId);
        } catch (RuntimeException exception) {
            saved.set(false);
            throw exception;
        }
    }

    /**
     * 将非取消异常和已生成片段写入助手错误消息，并清除不可再引用的证据。
     */
    private boolean failRunWithAssistantError(String tenantId, String userId, String runId, String traceId,
                                              Throwable throwable, String partialContent) {
        String failureContent = errorContent(throwable, partialContent);
        String failureReason = safeMessage(throwable);
        if (parentWaitAllFinalizationService != null
                && parentWaitAllFinalizationService.failAsDraftIfWaiting(
                tenantId, userId, runId, failureContent, failureReason)) {
            clearEvidence(runControlService.require(tenantId, userId, runId));
            return true;
        }
        // WAIT_ALL 的稳定恢复 run 首次失败只记录运行终态，禁止把中间错误写进用户会话。
        if (AgentOrchestrationContextHolder.isSummaryOnly()) {
            runControlService.fail(tenantId, userId, runId, failureReason);
            clearEvidence(runControlService.require(tenantId, userId, runId));
            return true;
        }
        ChatMessageEntity message = runControlService.failWithAssistantMessage(tenantId, userId, runId,
                failureContent, traceId, failureReason);
        if (message != null) {
            conversationMemoryService.onMessageSaved(message);
        }
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        ragInvocationEvidenceStore.clear(run.getTenantId(), run.getUserId(), run.getSessionId(), runId);
        return false;
    }

    /** 将引用校验结果序列化为带 schema 的稳定消息元数据。 */
    private String citationMetadata(RagAnswerCitationValidation validation) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "schema", "rag-citations/v1",
                    "validation", validation));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG引用元数据序列化失败", exception);
        }
    }

    /** 清除指定运行的临时 RAG 证据；空运行直接忽略。 */
    private void clearEvidence(ChatRunEntity run) {
        if (run != null) {
            ragInvocationEvidenceStore.clear(run.getTenantId(), run.getUserId(), run.getSessionId(), run.getRunId());
        }
    }

    /** 保证流式异常终态最多落库一次。 */
    private boolean failRunWithAssistantErrorOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                                  String traceId, Throwable throwable, String partialContent) {
        if (!saved.compareAndSet(false, true)) return false;
        try {
            return failRunWithAssistantError(tenantId, userId, runId, traceId, throwable, partialContent);
        } catch (RuntimeException exception) {
            saved.set(false);
            throw exception;
        }
    }

    /** SSE 断开时若已进入 WAIT_ALL，保存当前草稿并打开父侧屏障，而不是取消整条编排。 */
    private boolean completeParentDraftOnDisconnectOnce(AtomicBoolean saved, String tenantId, String userId,
                                                        String runId, String parentDraft) {
        if (parentWaitAllFinalizationService == null
                || !parentWaitAllFinalizationService.isAwaitingSummary(tenantId, runId)
                || !saved.compareAndSet(false, true)) return false;
        try {
            return parentWaitAllFinalizationService.completeAsDraftIfWaiting(
                    tenantId, userId, runId, parentDraft);
        } catch (RuntimeException exception) {
            saved.set(false);
            throw exception;
        }
    }

    /** 生成适合运行表的短错误摘要，限制长度避免持久化异常正文失控。 */
    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "运行失败" : throwable.getClass().getSimpleName();
        }
        String message = throwable.getMessage();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    /**
     * 构造可审计的助手错误正文，保留异常类型与已输出片段。
     */
    private String errorContent(Throwable throwable, String partialContent) {
        String errorType = throwable == null ? "UnknownError" : throwable.getClass().getSimpleName();
        String errorMessage = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
        String partial = partialContent == null || partialContent.isBlank() ? "" : "\npartialContent=" + partialContent;
        return "[assistant_error] type=" + errorType + " message=" + errorMessage + partial;
    }

    /**
     * 兼容增量分片和累计快照两类供应商输出，避免重复拼接。
     */
    private void appendContent(StringBuilder assistantContent, String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        String current = assistantContent.toString();
        if (content.equals(current)) {
            // 完整快照与当前缓存一致时无需追加。
            return;
        }
        if (!current.isBlank() && content.startsWith(current)) {
            // 供应商返回累计快照时只追加新增后缀。
            assistantContent.append(content.substring(current.length()));
            return;
        }
        assistantContent.append(content);
    }

    /** 兼容增量和累计快照两类模型事件，返回本次尚未展示的后缀。 */
    private String contentDelta(String current, String content) {
        if (content == null || content.isBlank() || content.equals(current)) {
            return "";
        }
        if (current != null && !current.isBlank() && content.startsWith(current)) {
            return content.substring(current.length());
        }
        return content;
    }

    /** 把一帧 ADK 内容拆成思考与正文两条通道，并分别追加到持久事件账本。 */
    private void observeAgentEvent(ChatRunEntity run, Event event, StringBuilder thinking,
                                   StringBuilder answer, String nodeExecutionId, boolean publishAnswer) {
        AgentEventContent.Snapshot snapshot = AgentEventContent.snapshot(event);
        String thinkingDelta = contentDelta(thinking.toString(), snapshot.thinking());
        if (!thinkingDelta.isEmpty()) {
            int publishedBefore = Math.min(thinking.length(), Math.max(0, thinkingMaxChars));
            appendContent(thinking, snapshot.thinking());
            int remaining = Math.max(0, thinkingMaxChars - publishedBefore);
            if (remaining > 0) publishThinkingDelta(run,
                    thinkingDelta.substring(0, Math.min(remaining, thinkingDelta.length())), nodeExecutionId);
        }
        String answerDelta = contentDelta(answer.toString(), snapshot.answer());
        if (!answerDelta.isEmpty()) {
            appendContent(answer, snapshot.answer());
            if (publishAnswer) publishAgentEvent(run, "ANSWER_DELTA", nodeExecutionId, Map.of("delta", answerDelta));
        }
    }

    private void publishThinkingDelta(ChatRunEntity run, String delta, String nodeExecutionId) {
        if (!thinkingVisible || !thinkingPersist || delta == null || delta.isEmpty()) return;
        publishAgentEvent(run, "THINKING_DELTA", nodeExecutionId, Map.of("delta", delta, "mode", "medium"));
    }

    private void publishAgentTerminal(ChatRunEntity run, boolean deferred, Throwable error, String explicitStatus) {
        if (deferred) {
            publishAgentEvent(run, "WAITING_ALL", Map.of("message", "等待全部子 Agent 完成后统一汇总"));
            return;
        }
        if ("cancelled".equals(explicitStatus)) {
            publishAgentEvent(run, "WORKFLOW_CANCELLED", Map.of("message", "运行已取消"));
            return;
        }
        if (error != null) {
            publishAgentEvent(run, "WORKFLOW_FAILED", Map.of("message", safeMessage(error),
                    "errorCode", error instanceof AppException app ? app.getCode() : "AGENT_EXECUTION_FAILED"));
            return;
        }
        publishAgentEvent(run, "FINAL_ANSWER_COMPLETED", Map.of());
        publishAgentEvent(run, "WORKFLOW_COMPLETED", Map.of("sourceType", "agent"));
    }

    private void publishAgentEvent(ChatRunEntity run, String eventType, Map<String, ?> payload) {
        publishAgentEvent(run, eventType, null, payload);
    }

    private void publishAgentEvent(ChatRunEntity run, String eventType, String nodeExecutionId, Map<String, ?> payload) {
        if (workflowEventStreamService == null || run == null || run.getTraceId() == null) return;
        workflowEventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(),
                eventType, nodeExecutionId, null, jsonPayload(payload));
        String rootRunId = AgentOrchestrationContextHolder.getRootRunId();
        if (!AgentOrchestrationContextHolder.isSummaryOnly() || rootRunId == null
                || rootRunId.isBlank() || rootRunId.equals(run.getRunId()) || !resumeVisibleEvent(eventType)) return;
        Map<String, Object> mirrored = new LinkedHashMap<>();
        if (payload != null) mirrored.putAll(payload);
        mirrored.put("sourceRunId", run.getRunId());
        if ("AGENT_STARTED".equals(eventType)) mirrored.put("label", "主 Agent 正在汇总子任务结果");
        workflowEventStreamService.publish(run.getTenantId(), run.getUserId(), rootRunId, run.getTraceId(),
                eventType, nodeExecutionId, null, jsonPayload(mirrored));
    }

    private boolean resumeVisibleEvent(String eventType) {
        return "AGENT_STARTED".equals(eventType) || "THINKING_DELTA".equals(eventType)
                || "ANSWER_DELTA".equals(eventType);
    }

    /** 将普通 DAG 事件写入统一账本；序号和根 Trace 由事件服务校验。 */
    private void publishWorkflowEvent(ChatRunEntity run, String eventType, String nodeExecutionId,
                                      String nodeId, Map<String, ?> payload) {
        workflowEventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(),
                eventType, nodeExecutionId, nodeId, jsonPayload(payload));
    }

    /** 把节点展示字段序列化为稳定 JSON，不允许静默丢失事件正文。 */
    private String jsonPayload(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流事件序列化失败", exception);
        }
    }

    /**
     * 将多模态命令转成可检索的审计文本；二进制正文不落消息表。
     */
    private String describeContent(ChatCommandEntity chatCommandEntity) {
        List<String> contentList = new ArrayList<>();
        if (chatCommandEntity.getTexts() != null) {
            chatCommandEntity.getTexts().forEach(text -> contentList.add(text.getMessage()));
        }
        if (chatCommandEntity.getFiles() != null) {
            chatCommandEntity.getFiles().forEach(file -> contentList.add("[file] " + file.getFileUri()));
        }
        if (chatCommandEntity.getInlineDatas() != null) {
            chatCommandEntity.getInlineDatas().forEach(inlineData -> contentList.add("[inline_data] " + inlineData.getMimeType()));
        }
        return String.join("\n", contentList);
    }

    /**
     * 引导运行复用前序原始问题并追加用户指令，不篡改前序消息。
     */
    private String steerResumeMessage(ChatRunEntity run, String requestMessage) {
        if (run == null || run.getPredecessorRunId() == null || run.getPredecessorRunId().isBlank()) {
            return requestMessage;
        }
        // 从权威消息表读取前序用户输入，客户端重传内容仅作缺失兜底。
        String originalMessage = sessionDomain.queryRunMessages(run.getTenantId(), run.getUserId(), run.getSessionId(),
                        run.getPredecessorRunId()).stream()
                .filter(message -> SessionDomain.ROLE_USER.equals(message.getRole()))
                .map(ChatMessageEntity::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse(requestMessage == null ? "" : requestMessage);
        return originalMessage + "\n\n[用户执行中引导]\n" + run.getSteerInstruction();
    }

    /**
     * 节点最终输出及其累积祖先证据。
     */
    private record NodeRunResult(String nodeId, String output, List<RagContextEvidence> evidence) {
    }

    /** 单个节点一次模型调用的输出及其实际注入证据。 */
    private record NodeExecutionResult(String output, List<RagContextEvidence> evidence) { }

    /** 工作流终点输出及其祖先证据并集。 */
    private record WorkflowExecutionResult(String output, List<RagContextEvidence> evidence) { }

    /**
     * 只从认证上下文读取可信租户。
     */
    private String currentTenantId() {
        return TenantContextHolder.getTenantId();
    }

}
