package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeInvocationResultEntity;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;

/**
 * 对话领域的唯一入口：建会话、发消息、拿事件流全走这里。
 *
 * <p>解决什么问题：一次对话背后有一长串动作——校验 Agent 能不能用、建或复用会话、创建运行记录、
 * 把用户消息落库、装配上下文、调用模型或执行工作流、收集输出、校验引用、写助手消息、推进运行终态。
 * 这个接口把这一整套编排收在领域层，让 trigger 层只管协议转换。</p>
 *
 * <p>所属层次：领域层的领域服务接口。</p>
 *
 * <p>谁会调用它：trigger 层的 HTTP 控制器（同步对话、流式对话、建会话），
 * 以及独立的智能工作流运行时（复用节点调用和运行收尾两个方法）。</p>
 *
 * <p>它向下调用什么：装配工厂取 Runner、会话领域落会话与消息、运行控制服务管运行状态与取消门禁、
 * 工作流服务编译 DAG、上下文服务推进历史快照、RAG 证据仓与引用校验器保证出处真实。</p>
 *
 * <p>它不负责什么：不做 HTTP 参数解析、不做 SSE 推送、不决定用户身份（身份必须由调用方以可信方式传入）。</p>
 */
public interface IChatService {

    /**
     * 列出当前租户可见且可用的公共 Agent。
     *
     * <p>只读静态配置再逐个过租户启停判断，因此不同租户看到的列表可能不同；
     * 工作流内部使用的运行时 Agent 不会出现在这里，避免用户直接拿它建会话绕过工作流授权。</p>
     *
     * <p>不写库、不改状态。</p>
     */
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    /**
     * 为一个公共 Agent 建立新会话。
     *
     * <p>会同时建两个东西：ADK 侧的会话和平台自己的会话记录，并复用同一个 ID 以免维护映射关系。
     * 平台的会话记录才是后续权限校验的权威依据。</p>
     *
     * <p>会写数据库。Agent 不是静态 Agent、被租户禁用、或启动装配没完成时都会抛业务异常，此时不产生会话。</p>
     */
    String createSession(String agentId, String userId);

    /** 创建不进入用户会话列表的临时子 Agent 会话。 */
    String createSubagentSession(String agentId, String userId);

    /** 删除临时子 Agent 在 ADK 中的运行会话。 */
    void deleteSubagentRuntimeSession(String agentId, String userId, String sessionId);

    /**
     * 为一个工作流建立新会话，并把这次解析出的版本和模型固化进会话记录。
     *
     * <p>为什么要固化：客户端传的版本和模型只是「选择条件」，真实生效值由服务端按权限和发布状态解析。
     * 把解析结果写进会话，后续这个会话就一直按同一个版本和模型跑，不会因为别人发布了新版本而中途变样。</p>
     *
     * <p>会写数据库，会先编译一次工作流运行时。无权访问该工作流或版本不可用时抛业务异常。</p>
     */
    String createWorkflowSession(String workflowId, Integer workflowVersion, String modelCode, String userId);

    /**
     * 建一个临时会话并立刻同步跑一轮对话，适合一次性调用的场景。
     *
     * <p>返回模型输出的原始事件文本列表，注意这些文本可能是「累计式」的，展示前需要去重合并。</p>
     *
     * <p>会写数据库（会话、运行、用户与助手消息），会调用大模型。</p>
     */
    List<String> handleMessage(String agentId, String userId, String message);

    /**
     * 在已有会话里同步跑一轮对话，阻塞到模型输出完毕。
     *
     * <p>执行顺序是刻意设计的：先落运行记录和用户消息，再调用模型。这样即使模型调用失败，
     * 数据库里也留有可审计的痕迹，而不是「用户明明发过消息但系统里查不到」。</p>
     *
     * <p>会写数据库、会调用大模型。会话不属于该用户时抛业务异常。</p>
     */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /** 在已有会话中处理平台内部恢复输入；输入可审计但不对用户展示。 */
    List<String> handleInternalMessage(String agentId, String userId, String sessionId, String message);

    /** 使用稳定运行编号处理平台内部恢复输入；Kafka 重投时重放既有终态而不重复发布答案。 */
    List<String> handleInternalMessage(String agentId, String userId, String sessionId, String message,
                                       String requestedRunId);

    /**
     * 执行由父 Agent 委派的子任务。子 Agent 使用自己的模板与工具权限，
     * 但 RAG 范围继承父运行创建时已经冻结的策略和绑定。
     */
    List<String> handleSubagentMessage(String agentId, String userId, String sessionId, String message,
                                       String parentRunId, String parentSessionId, String parentAgentId);

    /**
     * 同步执行一次完整工作流，只返回收敛后的最终文本。
     *
     * <p>内部走的是流式实现再取第一个（也是唯一一个）元素，因此行为与流式接口完全一致，
     * 不会出现两套编排逻辑对不上的问题。</p>
     *
     * <p>会写数据库、会执行 DAG 上的每个节点（每个节点都是一次模型调用）。</p>
     */
    List<String> handleWorkflowMessage(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 只拿事件流的旧入口，拿不到运行编号。
     *
     * <p>没有 runId 就无法取消、无法关联引用，所以新代码应使用 startMessageStream。
     * 保留它只为兼容还没改造完的调用方。</p>
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 启动一次不带附件的流式对话（附件列表按空处理）。
     *
     * <p>返回运行记录 + 事件流：有了运行记录调用方才能取消、查状态、查引用。</p>
     */
    RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                              String requestedRunId);

    /**
     * 启动一次带附件的流式对话，是流式 Agent 分支的正式入口。
     *
     * <p>关键输入：requestedRunId 用于幂等重试和「执行中引导」的后继运行；attachmentIds 会在
     * 同一个运行事务里绑定到本轮用户消息上，保证上下文只看得到本轮确认过的附件。</p>
     *
     * <p>返回的流是惰性的——只有被订阅时才真正调用模型，因此方法返回不代表对话已经开始。</p>
     *
     * <p>会写数据库、会调用大模型。取消、异常、正常完成三条路径都会把运行推进到对应终态。</p>
     */
    RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                              String requestedRunId, List<String> attachmentIds);

    /**
     * 已废弃的工作流事件流入口，调用它一定返回错误流。
     *
     * <p>工作流内部的节点事件不允许直接暴露给外部，否则调用方会依赖内部拓扑细节；
     * 需要看节点进度请走工作流事件账本，需要看结果请用文本流接口。</p>
     */
    Flowable<Event> handleWorkflowMessageStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 启动工作流并只返回最终文本流的旧入口，拿不到运行编号。
     *
     * <p>同样因为缺少 runId 而无法取消，新代码请用 startWorkflowMessageTextStream。</p>
     */
    Flowable<String> handleWorkflowMessageTextStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 启动一次不带附件的工作流运行（附件列表按空处理）。
     */
    RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                            String modelCode, String userId, String sessionId,
                                                            String message, String requestedRunId);

    /**
     * 启动一次带附件的工作流运行，是流式工作流分支的正式入口。
     *
     * <p>入口线程只做「编译 DAG + 建运行记录」这些轻活，真正跑节点被推到协调线程池里，
     * 这样 HTTP/SSE 线程不会被长时间阻塞。</p>
     *
     * <p>会写数据库、会逐个节点调用模型、会持续发布工作流节点事件。
     * 客户端断流会反向把运行标记为取消，取消后已产生的 RAG 证据会被清掉，不留下能被后续回答引用的脏数据。</p>
     */
    RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                            String modelCode, String userId, String sessionId,
                                                            String message, String requestedRunId,
                                                            List<String> attachmentIds);

    /**
     * 同步发送一条可包含文本、外部文件和内联二进制的复合消息。
     *
     * <p>三类内容会按声明顺序拼成模型输入；落库时二进制正文不入消息表，只记录类型摘要，
     * 避免把大块字节写进业务库。</p>
     *
     * <p>会写数据库、会调用大模型。</p>
     */
    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /**
     * 供独立的智能工作流运行时调用单个已编译节点。
     *
     * <p>为什么要单独开这个口：智能运行时有自己的调度器，但节点执行所需的 Agent 取用、
     * 上下文装配、RAG 证据绑定这套东西不该重复实现，所以复用这里的单节点执行逻辑。</p>
     *
     * <p>调用前仍会过一次权威运行取消门禁，因此运行被取消后不会再产生新的模型或工具消费。</p>
     *
     * <p>返回节点输出文本 + 本次调用真实注入的 RAG 证据；证据用于后续判断回答的引用是否合法。</p>
     */
    WorkflowNodeInvocationResultEntity invokeCompiledWorkflowNode(WorkflowDagPlanEntity.Node node,
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
                                                                   String upstreamOutput);

    /**
     * 用智能运行时真实累积的 RAG 证据完成最终回答，并做引用校验。
     *
     * <p>校验在落库之前完成：模型如果引用了本次没检索到的资料，会被记录为非法引用，
     * 而不是原样写进消息里冒充真实出处。</p>
     *
     * <p>会写数据库（助手消息 + 运行终态），并清除本次运行的临时证据。</p>
     */
    void completeCompiledWorkflowRun(ChatRunEntity run, String output, String traceId,
                                     List<RagContextEvidence> evidence);

}
