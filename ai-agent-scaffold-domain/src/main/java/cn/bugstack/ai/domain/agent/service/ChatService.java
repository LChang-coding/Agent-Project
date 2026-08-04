package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
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
 * 统一编排 Agent 与工作流对话；业务会话和运行状态落库，ADK 会话只服务单次模型调用。
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    /** 工作流只保留终点节点可达的 RAG 证据，避免旁路节点污染最终引用。 */
    private static final RagWorkflowEvidenceLineage RAG_LINEAGE = new RagWorkflowEvidenceLineage();

    /** 提供启动期已经装配完成的 Agent Runner。 */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /** 提供可展示的静态 Agent 配置，不参与租户授权判定。 */
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 持久化业务会话和消息，并校验会话归属。 */
    @Resource
    private SessionDomain sessionDomain;

    /** 将工作流定义解析为本次运行不可变的 DAG 快照。 */
    @Resource
    private IWorkflowService workflowService;

    /** 持久化并实时广播普通和智能工作流共用的节点事件。 */
    @Resource
    private WorkflowEventStreamService workflowEventStreamService;

    /** 原子保存普通 DAG 最终消息和工作流终态事件。 */
    @Resource
    private WorkflowRunFinalizationService workflowRunFinalizationService;

    /** 消息落库后推进上下文快照与压缩任务。 */
    @Resource
    private ConversationMemoryService conversationMemoryService;

    /** 原子管理运行、消息绑定、取消门禁和终态。 */
    @Resource
    private RunControlService runControlService;

    /** 合并平台状态与租户覆盖，决定公共 Agent 是否可调用。 */
    @Resource
    private AgentAvailabilityService agentAvailabilityService;

    /** 暂存每次模型调用实际使用的 RAG 证据。 */
    @Resource
    private RagInvocationEvidenceStore ragInvocationEvidenceStore;

    /** 只允许最终回答引用本次调用真实注入的证据。 */
    @Resource
    private RagAnswerCitationValidator ragAnswerCitationValidator;

    /** 将引用校验结果写成稳定的消息元数据。 */
    @Resource
    private ObjectMapper objectMapper;

    /** 承载工作流总协调，防止阻塞 HTTP/SSE 线程。 */
    @Resource(name = "workflowCoordinatorExecutor")
    private ExecutorService workflowCoordinatorExecutor;

    /** 同一拓扑层的节点提交到此线程池并行执行。 */
    @Resource(name = "workflowNodeExecutor")
    private ExecutorService workflowNodeExecutor;

    /**
     * 返回当前租户可见且可用的公共 Agent；工作流运行时 Agent 不在此列表暴露。
     */
    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    // 静态配置只提供候选，最终仍由平台状态与租户覆盖共同裁决。
                    if (agentAvailabilityService.isEnabled(currentTenantId(), vo.getAgent().getAgentId())) {
                        agentList.add(vo.getAgent());
                    }
                }
            }
        }

        return agentList;
    }

    /**
     * 为已授权公共 Agent 创建业务会话和对应 ADK 会话。
     */
    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        return createSession(agentId, userId, aiAgentRegisterVO);
    }

    /**
     * 先编译工作流运行时，再把实际版本和模型固化进新会话。
     */
    @Override
    public String createWorkflowSession(String workflowId, Integer workflowVersion, String modelCode, String userId) {
        String tenantId = currentTenantId();
        // 客户端提交的是选择条件；实际版本、模型和运行时 Agent 均由服务端解析。
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, TenantContextHolder.getRoleCode(),
                workflowId, workflowVersion, modelCode);
        AiAgentRegisterVO aiAgentRegisterVO = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        return createWorkflowSession(tenantId, workflowId, userId, aiAgentRegisterVO, runtime);
    }

    /** 使用当前可信租户创建普通 Agent 会话。 */
    private String createSession(String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        return createSession(currentTenantId(), sessionAgentId, userId, aiAgentRegisterVO);
    }

    /** 普通 Agent 会话不固化工作流版本和模型。 */
    private String createSession(String tenantId, String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        return createSession(tenantId, sessionAgentId, userId, aiAgentRegisterVO, "agent", null, null);
    }

    /** 创建工作流会话并固化服务端解析后的运行事实。 */
    private String createWorkflowSession(String tenantId, String workflowId, String userId,
                                         AiAgentRegisterVO aiAgentRegisterVO, WorkflowRuntimeEntity runtime) {
        return createSession(tenantId, workflowId, userId, aiAgentRegisterVO, "workflow",
                runtime.getVersion(), runtime.getEffectiveModelCode());
    }

    /** 创建业务会话，并同步建立 ADK 会话；数据库记录是后续访问控制的权威来源。 */
    private String createSession(String tenantId, String sessionAgentId, String userId,
                                 AiAgentRegisterVO aiAgentRegisterVO, String sourceType,
                                 Integer workflowVersion, String modelCode) {
        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // ADK 生成的会话 ID 同时作为平台会话 ID，避免双 ID 映射。
        Session session = runner.sessionService().createSession(appName, userId).blockingGet();
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(session.id());
        command.setAgentId(sessionAgentId);
        command.setAgentName(aiAgentRegisterVO.getAgentName());
        command.setSourceType(sourceType);
        command.setWorkflowVersion(workflowVersion);
        command.setModelCode(modelCode);
        command.setAppName(appName);
        command.setTitle(aiAgentRegisterVO.getAgentName());
        // 先准备完整事实再一次落库，不允许存在缺少运行目标的半成品会话。
        sessionDomain.createSession(command);
        return session.id();
    }

    /**
     * 创建临时会话并同步执行一次公共 Agent 对话。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        requirePublicAgent(agentId);

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    /**
     * 在指定业务会话中同步执行公共 Agent 对话。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);

        return doHandleMessage(agentId, userId, sessionId, message, aiAgentRegisterVO);
    }

    /**
     * 兼容同步调用：执行完整 DAG 后只返回唯一的最终文本。
     */
    @Override
    public List<String> handleWorkflowMessage(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return List.of(startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId,
                message, null).getStream().blockingFirst());
    }

    /**
     * 兼容旧接口：创建运行后只向调用方暴露 ADK 事件流。
     */
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return startMessageStream(agentId, userId, sessionId, message, null).getStream();
    }

    /**
     * 启动无附件的普通 Agent 流式运行。
     */
    @Override
    public RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                                     String requestedRunId) {
        return startMessageStream(agentId, userId, sessionId, message, requestedRunId, List.of());
    }

    /** 启动普通 Agent 流式运行；先固化运行快照，再构造惰性模型事件流。 */
    @Override
    public RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                                     String requestedRunId, List<String> attachmentIds) {
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(agentId);
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(agentId, userId, sessionId, aiAgentRegisterVO);
        // startOrResume 同时处理新运行、幂等重试和引导后继运行。
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "agent", agentId, requestedRunId);
        String effectiveMessage = steerResumeMessage(run, message);
        return RunStreamEntity.<Event>builder()
                .run(run)
                .stream(doHandleMessageStream(agentId, userId, actualSessionId, effectiveMessage, aiAgentRegisterVO,
                        run, attachmentIds))
                .build();
    }

    /**
     * 拒绝暴露工作流内部节点事件；调用方只能消费收敛后的最终文本。
     */
    @Override
    public Flowable<Event> handleWorkflowMessageStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return Flowable.error(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流流式输出请使用文本流接口"));
    }

    /**
     * 启动工作流并返回最终文本流。
     */
    @Override
    public Flowable<String> handleWorkflowMessageTextStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId, message, null)
                .getStream();
    }

    /**
     * 启动无附件的工作流运行。
     */
    @Override
    public RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                                   String modelCode, String userId, String sessionId,
                                                                   String message, String requestedRunId) {
        return startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId,
                message, requestedRunId, List.of());
    }

    /** 编译并调度工作流；返回运行快照和惰性最终文本流。 */
    @Override
    public RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                                   String modelCode, String userId, String sessionId,
                                                                   String message, String requestedRunId,
                                                                   List<String> attachmentIds) {
        String tenantId = currentTenantId();
        String traceId = TraceContext.currentOrNewTraceId();
        String roleCode = TenantContextHolder.getRoleCode();
        // 每次运行先解析并冻结 DAG、模型和内部 Agent，避免执行中配置漂移。
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, roleCode,
                workflowId, workflowVersion, modelCode);
        AiAgentRegisterVO rootAgent = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        String actualSessionId = ensureWorkflowSessionId(tenantId, workflowId, userId, sessionId, rootAgent, runtime);
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "workflow", workflowId, requestedRunId);
        String effectiveMessage = steerResumeMessage(run, message);
        // 真正的 DAG 执行发生在订阅后；HTTP 入口线程不直接跑节点。
        Flowable<String> stream = observeWorkflowCancellation(
                scheduleWorkflow(() -> doHandleWorkflowDagMessage(runtime, tenantId, userId,
                        actualSessionId, effectiveMessage, traceId, run, attachmentIds, roleCode)),
                tenantId, userId, run)
                .doFinally(() -> {
                    if (runControlService.cancelled(tenantId, userId, run.getRunId())) clearEvidence(run);
                });
        return RunStreamEntity.<String>builder()
                .run(run)
                .stream(stream)
                .build();
    }

    /** 轮询权威运行状态接收跨实例取消；客户端断流时反向写入取消。 */
    private <T> Flowable<T> observeWorkflowCancellation(Flowable<T> stream, String tenantId, String userId,
                                                         ChatRunEntity run) {
        return stream
                // 取消信号终止下游发射；各节点调用前还有 requireExecutable 二次门禁。
                .takeUntil(Flowable.interval(250, TimeUnit.MILLISECONDS)
                        .filter(tick -> runControlService.cancelled(tenantId, userId, run.getRunId())))
                .doOnCancel(() -> {
                    if (!runControlService.cancelled(tenantId, userId, run.getRunId())) {
                        runControlService.cancel(tenantId, userId, run.getRunId(), "流式连接已中断");
                    }
                });
    }

    /**
     * 把阻塞式 DAG 调用包装为背压敏感的单值流。
     */
    private <T> Flowable<T> scheduleWorkflow(Callable<T> action) {
        return Flowable.create(emitter -> {
            Future<?> future;
            try {
                // 跨线程显式传播 traceId，保证节点日志仍属于入口链路。
                Callable<T> tracedAction = TraceContext.wrap(action);
                future = workflowCoordinatorExecutor.submit(() -> {
                    // 订阅在任务取得线程前已取消时，不再产生任何外部调用。
                    if (emitter.isCancelled()) {
                        return;
                    }
                    try {
                        T result = tracedAction.call();
                        if (!emitter.isCancelled()) {
                            emitter.onNext(result);
                            emitter.onComplete();
                        }
                    } catch (Throwable throwable) {
                        emitter.tryOnError(throwable);
                    }
                });
            } catch (RejectedExecutionException exception) {
                emitter.tryOnError(exception);
                return;
            }
            // 断流中断协调线程；节点内部仍依赖运行门禁阻止后续工具/模型调用。
            emitter.setCancellable(() -> future.cancel(true));
        }, BackpressureStrategy.ERROR);
    }

    /**
     * 将文本、远程文件和内联二进制组装为一次同步多模态调用。
     */
    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = requirePublicAgent(chatCommandEntity.getAgentId());

        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());
        sessionDomain.assertSessionAccess(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId());

        // 保持客户端声明的三类内容顺序组装为 ADK Part。
        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String traceId = TraceContext.currentOrNewTraceId();
        // 先建立业务运行并落用户消息，模型永远不先于可审计事实执行。
        ChatRunEntity run = runControlService.start(tenantId, chatCommandEntity.getUserId(), actualSessionId,
                "agent", chatCommandEntity.getAgentId(), null, null);
        // 重发未发布的压缩任务，避免历史持久化成功但异步消息丢失。
        conversationMemoryService.republishUnfinished(tenantId, chatCommandEntity.getUserId(), actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, chatCommandEntity.getUserId(), run.getRunId(),
                describeContent(chatCommandEntity), traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        ChatRunEntity activeRun = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), adkSessionId);

        // message 在这里作为 content 交给 ADK Runner；state 同时注入可信运行身份和上下文切面。
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), adkSessionId, content, RunConfig.builder().build(),
                runtimeStateDelta(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId(), traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                        ragTargetType(activeRun, RagBindingTargetType.AGENT),
                        ragQuery(activeRun, describeContent(chatCommandEntity)),
                        activeRun.getRagMode(), activeRun.getRagBindingIds()));

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> {
                // 每个事件前复核取消状态，取消后不再接受输出或继续工具链。
                runControlService.requireExecutable(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), null);
                outputs.add(event.stringifyContent());
            });
            // 只有事件流正常结束才原子写助手消息并推进运行成功。
            completeRunWithAssistant(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(),
                    String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            if (!runControlService.cancelled(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId())) {
                // 非取消异常保留已生成片段，便于审计实际失败点。
                failRunWithAssistantError(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), traceId, e,
                        String.join("\n", outputs));
            } else {
                clearEvidence(activeRun);
            }
            throw e;
        }

        return outputs;
    }

    /**
     * 执行同步纯文本对话；业务状态处理与流式入口保持同一顺序。
     */
    private List<String> doHandleMessage(String sessionAgentId, String userId, String sessionId, String message, AiAgentRegisterVO aiAgentRegisterVO) {
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        String traceId = TraceContext.currentOrNewTraceId();
        // 运行和用户消息先落库，保证失败、取消和重试均有稳定锚点。
        ChatRunEntity run = runControlService.start(tenantId, userId, actualSessionId, "agent", sessionAgentId,
                null, null);
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        ChatRunEntity activeRun = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        // message 在此进入 ADK Agent；插件从 state 读取上下文、RAG 与工具身份。
        Flowable<Event> events = runner.runAsync(userId, adkSessionId, userMsg, RunConfig.builder().build(),
                runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                        ragTargetType(activeRun, RagBindingTargetType.AGENT), ragQuery(activeRun, message),
                        activeRun.getRagMode(), activeRun.getRagBindingIds()));

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> {
                runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                outputs.add(event.stringifyContent());
            });
            completeRunWithAssistant(tenantId, userId, activeRun.getRunId(), String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                failRunWithAssistantError(tenantId, userId, activeRun.getRunId(), traceId, e, String.join("\n", outputs));
            } else {
                clearEvidence(activeRun);
            }
            throw e;
        }

        return outputs;
    }

    /**
     * 构造普通 Agent 的 SSE 事件流；订阅后才调用模型。
     */
    private Flowable<Event> doHandleMessageStream(String sessionAgentId, String userId, String sessionId, String message,
                                                  AiAgentRegisterVO aiAgentRegisterVO, ChatRunEntity run,
                                                  List<String> attachmentIds) {
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        String traceId = TraceContext.currentOrNewTraceId();
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        // 用户消息与附件在同一运行事务中绑定，后续上下文只能看到本次已确认资产。
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId,
                attachmentIds);
        ChatMessageEntity userMessage = binding.getMessage();
        run = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        StringBuilder assistantContent = new StringBuilder();
        // 完成、异常和取消可能竞争，只允许一个分支写助手终态。
        AtomicBoolean assistantSaved = new AtomicBoolean(false);
        ChatRunEntity activeRun = run;
        // 这是普通会话真正调用 Agent 的位置；用户 message 作为 userMsg 输入。
        return runner.runAsync(userId, adkSessionId, userMsg, runConfig,
                        runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                                TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                                activeRun.getRunId(), activeRun.getCurrentContextRevision(),
                                ragTargetType(activeRun, RagBindingTargetType.AGENT), ragQuery(activeRun, message),
                                activeRun.getRagMode(), activeRun.getRagBindingIds()))
                // 跨实例取消靠数据库轮询，本实例事件处理靠 requireExecutable 即时拦截。
                .takeUntil(Flowable.interval(250, TimeUnit.MILLISECONDS)
                        .filter(tick -> runControlService.cancelled(tenantId, userId, activeRun.getRunId())))
                .doOnNext(event -> {
                    runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                    appendContent(assistantContent, event.stringifyContent());
                })
                .doOnComplete(() -> {
                    // 正常完成才保存聚合后的助手文本。
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        completeRunWithAssistantOnce(assistantSaved, tenantId, userId, activeRun.getRunId(),
                                assistantContent.toString(), traceId);
                    }
                })
                .doOnError(throwable -> {
                    // 取消不写错误消息；真实异常写入可审计终态。
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        failRunWithAssistantErrorOnce(assistantSaved, tenantId, userId, activeRun.getRunId(), traceId,
                                throwable, assistantContent.toString());
                    }
                })
                .doOnCancel(() -> {
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        runControlService.cancel(tenantId, userId, activeRun.getRunId(), "流式连接已中断");
                    }
                })
                .doFinally(() -> {
                    // 被取消的运行不能留下可被后续回答引用的 RAG 证据。
                    if (runControlService.cancelled(tenantId, userId, activeRun.getRunId())) clearEvidence(activeRun);
                });
    }

    /**
     * 落库工作流输入，执行冻结的 DAG，并只保存终点节点汇总结果。
     */
    private String doHandleWorkflowDagMessage(WorkflowRuntimeEntity runtime, String tenantId, String userId,
                                              String sessionId, String message, String traceId, ChatRunEntity run,
                                              List<String> attachmentIds, String roleCode) {
        // runtime 已在入口按权限编译；此处只接受非空、可执行的计划。
        WorkflowDagPlanEntity dagPlan = requireDagPlan(runtime);
        AiAgentRegisterVO rootAgent = requireWorkflowRuntimeAgent(runtime.getRuntimeAgentId());
        String actualSessionId = ensureWorkflowSessionId(tenantId, runtime.getWorkflowId(), userId, sessionId, rootAgent, runtime);
        ChatRunEntity activeRun = run;

        try {
            sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, runtime.getWorkflowId());
            conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
            RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId,
                    attachmentIds);
            ChatMessageEntity userMessage = binding.getMessage();
            activeRun = binding.getRun();
            publishWorkflowEvent(activeRun, "WORKFLOW_STARTED", null, null, Map.of(
                    "workflowId", dagPlan.getWorkflowId(),
                    "workflowVersion", dagPlan.getVersion(),
                    "workflowKind", "STATIC",
                    "rootNodeId", dagPlan.getRootNodeId()));
            // message 在 executeDagPlan 中被组合进每个就绪节点提示词，再交给节点 Agent。
            WorkflowExecutionResult execution = executeDagPlan(dagPlan, tenantId, userId, actualSessionId, message, traceId,
                    roleCode, historyCutoff(userMessage), activeRun);
            String finalOutput = execution.output();
            AiLog.info(AiLog.workflow().dagCompleted(tenantId, userId, dagPlan.getWorkflowId(), dagPlan.getVersion(),
                    dagPlan.getNodes().size(), dagPlan.getEdges() == null ? 0 : dagPlan.getEdges().size(),
                    String.join(",", terminalNodeIds(dagPlan, outgoingEdges(dagPlan))), finalOutput.length()));
            runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
            // 只携带终点祖先证据完成运行，未影响最终答案的旁路证据被排除。
            completeWorkflowRunWithAssistant(activeRun, finalOutput, execution.evidence(), dagPlan.getNodes().size());
            return finalOutput;
        } catch (RuntimeException e) {
            if (runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                workflowRunFinalizationService.reconcileCancellation(
                        runControlService.require(tenantId, userId, activeRun.getRunId()));
                clearEvidence(activeRun);
            } else {
                failWorkflowRunWithAssistantError(activeRun, e);
            }
            throw e;
        }
    }

    /**
     * 按拓扑层调度 DAG；同层并行、跨层等待全部依赖完成。
     */
    private WorkflowExecutionResult executeDagPlan(WorkflowDagPlanEntity dagPlan, String tenantId, String userId,
                                                   String sessionId, String userMessage, String traceId,
                                                   String roleCode, Integer historyCutoffSequence, ChatRunEntity run) {
        // 三张索引分别服务节点查找、依赖传递和 Kahn 拓扑推进。
        Map<String, WorkflowDagPlanEntity.Node> nodeMap = dagPlan.getNodes().stream()
                .collect(Collectors.toMap(WorkflowDagPlanEntity.Node::getNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<String>> outgoing = outgoingEdges(dagPlan);
        Map<String, List<String>> incoming = incomingEdges(dagPlan);
        Map<String, Integer> indegree = indegree(dagPlan);
        Set<String> selfLoopNodeIds = selfLoopNodeIds(dagPlan);
        List<String> ready = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> indegree.getOrDefault(nodeId, 0) == 0)
                .collect(Collectors.toCollection(ArrayList::new));
        if (ready.isEmpty() && dagPlan.getRootNodeId() != null) {
            // 兼容只有显式根节点、没有普通零入度节点的旧计划。
            ready.add(dagPlan.getRootNodeId());
        }

        // 输出和证据均以节点 ID 隔离，只有依赖节点能读取上游结果。
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, List<RagContextEvidence>> provenance = new LinkedHashMap<>();
        while (!ready.isEmpty()) {
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            List<String> currentLevel = new ArrayList<>(ready);
            ready.clear();
            // 当前层共享只读的上层输出；本层结果在全部 join 后才写回。
            List<CompletableFuture<NodeRunResult>> futures = currentLevel.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> runDagNode(nodeMap.get(nodeId), incoming.getOrDefault(nodeId, Collections.emptyList()),
                            selfLoopNodeIds.contains(nodeId), outputs, provenance, tenantId, userId, sessionId, dagPlan.getWorkflowId(),
                            userMessage, traceId, roleCode, historyCutoffSequence, run), workflowNodeExecutor))
                    .collect(Collectors.toList());
            List<NodeRunResult> levelResults = new ArrayList<>(futures.size());
            RuntimeException levelFailure = null;
            for (CompletableFuture<NodeRunResult> future : futures) {
                try {
                    levelResults.add(joinNodeResult(future));
                } catch (RuntimeException exception) {
                    // 同层分支必须全部收敛后才能发布工作流终态，防止兄弟节点在 FAILED/CANCELLED 后继续写事件。
                    if (levelFailure == null) levelFailure = exception;
                }
            }
            if (levelFailure != null) throw levelFailure;
            for (NodeRunResult result : levelResults) {
                outputs.put(result.nodeId(), result.output());
                provenance.put(result.nodeId(), result.evidence());
                for (String targetNodeId : outgoing.getOrDefault(result.nodeId(), Collections.emptyList())) {
                    // 每完成一个前置节点就扣减入度，归零后进入下一层。
                    int next = indegree.get(targetNodeId) - 1;
                    indegree.put(targetNodeId, next);
                    if (next == 0) {
                        ready.add(targetNodeId);
                    }
                }
            }
        }

        if (outputs.size() != nodeMap.size()) {
            // 未执行完意味着存在非自循环环路或无法满足的依赖。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 执行失败：存在无法满足依赖的节点");
        }
        List<String> terminalIds = terminalNodeIds(dagPlan, outgoing);
        List<RagContextEvidence> terminalEvidence = RAG_LINEAGE.terminal(terminalIds, provenance);
        return new WorkflowExecutionResult(terminalOutputs(dagPlan, outgoing, outputs), terminalEvidence);
    }

    /**
     * 执行一个节点及其受控自循环；每轮以上一轮输出作为增量输入。
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
                                     ChatRunEntity run) {
        if (node == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 节点不存在");
        }
        // 带节点标签拼接上游输出，避免多父节点内容失去来源。
        List<String> upstreamOutputs = upstreamNodeIds.stream()
                .map(upstreamNodeId -> "[" + upstreamNodeId + "]\n" + outputs.getOrDefault(upstreamNodeId, ""))
                .collect(Collectors.toList());
        List<RagContextEvidence> accumulatedEvidence = new ArrayList<>(
                RAG_LINEAGE.merge(upstreamNodeIds, provenance, List.of()));
        // 只有显式自循环边启用迭代，且次数受全局硬上限保护。
        int runTimes = selfLoop ? safeLoopTimes(node.getMaxIterations()) : 1;
        String previousOutput = "";
        for (int index = 1; index <= runTimes; index++) {
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            // 每轮调用前再次过取消门禁，防止取消后产生新的模型/工具消费。
            String prompt = buildDagNodePrompt(userMessage, upstreamOutputs, previousOutput, index, runTimes);
            String nodeExecutionId = "wne_" + UUID.randomUUID();
            long nodeStarted = System.nanoTime();
            AiLog.info(AiLog.workflow().nodeStarted(tenantId, userId, sessionId, run.getRunId(),
                    workflowId, node.getNodeId(), index, runTimes, upstreamNodeIds.size()));
            publishWorkflowEvent(run, "NODE_STARTED", nodeExecutionId, node.getNodeId(), Map.of(
                    "nodeName", node.getNodeName(),
                    "executionIndex", index,
                    "totalIterations", runTimes,
                    "upstreamCount", upstreamNodeIds.size()));
            try {
                NodeExecutionResult execution = runDagNodeOnce(node, tenantId, userId, sessionId, workflowId, prompt,
                        traceId, roleCode, historyCutoffSequence, String.join("\n\n", upstreamOutputs), run,
                        delta -> publishWorkflowEvent(run, "NODE_OUTPUT_DELTA", nodeExecutionId, node.getNodeId(),
                                Map.of("delta", delta)));
                previousOutput = execution.output();
                accumulatedEvidence.addAll(execution.evidence());
                AiLog.info(AiLog.workflow().nodeCompleted(tenantId, userId, sessionId, run.getRunId(),
                        workflowId, node.getNodeId(), index, runTimes, previousOutput.length(),
                        execution.evidence().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
                publishWorkflowEvent(run, "NODE_COMPLETED", nodeExecutionId, node.getNodeId(), Map.of(
                        "displayOutput", previousOutput,
                        "executionIndex", index,
                        "costMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
            } catch (RuntimeException exception) {
                AiLog.error(AiLog.workflow().nodeFailed(tenantId, userId, sessionId, run.getRunId(),
                        workflowId, node.getNodeId(), index, runTimes,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted), exception));
                boolean cancelled = runControlService.cancelled(tenantId, userId, run.getRunId());
                publishWorkflowEvent(run, cancelled ? "NODE_CANCELLED" : "NODE_FAILED", nodeExecutionId, node.getNodeId(), Map.of(
                        "executionIndex", index,
                        "errorCode", cancelled ? "RUN_CANCELLED"
                                : exception instanceof AppException app ? app.getCode() : "WORKFLOW_NODE_FAILED",
                        "message", safeMessage(exception),
                        "costMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nodeStarted)));
                throw exception;
            }
        }
        return new NodeRunResult(node.getNodeId(), previousOutput, List.copyOf(accumulatedEvidence));
    }

    /**
     * 调用一次节点 Agent，并取回该次调用实际注入的 RAG 证据。
     */
    private NodeExecutionResult runDagNodeOnce(WorkflowDagPlanEntity.Node node, String tenantId, String userId,
                                               String sessionId, String workflowId, String prompt, String traceId,
                                               String roleCode, Integer historyCutoffSequence,
                                               String upstreamOutput, ChatRunEntity run) {
        return runDagNodeOnce(node, tenantId, userId, sessionId, workflowId, prompt, traceId, roleCode,
                historyCutoffSequence, upstreamOutput, run, ignored -> { });
    }

    /** 执行节点并把供应商累计快照转换为可重放的安全文本增量。 */
    private NodeExecutionResult runDagNodeOnce(WorkflowDagPlanEntity.Node node, String tenantId, String userId,
                                               String sessionId, String workflowId, String prompt, String traceId,
                                               String roleCode, Integer historyCutoffSequence,
                                               String upstreamOutput, ChatRunEntity run, Consumer<String> outputDelta) {
        runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
        AiAgentRegisterVO agent = requireWorkflowRuntimeAgent(node.getRuntimeAgentId());
        InMemoryRunner runner = agent.getRunner();
        // 每个节点每次执行使用独立 ADK 会话，业务历史统一由 Context Manager 注入。
        String adkSessionId = invocationSessionId(sessionId + ":" + node.getNodeId());
        ensureAdkSession(runner, agent.getAppName(), userId, adkSessionId);
        Content content = Content.fromParts(Part.fromText(prompt));
        StringBuilder output = new StringBuilder();
        // invocationId 将上下文插件写入的证据精确绑定到本节点调用。
        String evidenceInvocationId = "wf_" + node.getNodeId() + "_" + UUID.randomUUID();
        Map<String, Object> state = runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                historyCutoffSequence, upstreamOutput, run.getRunId(), run.getCurrentContextRevision(),
                ragTargetType(run, RagBindingTargetType.WORKFLOW), ragQuery(run, prompt),
                run.getRagMode(), run.getRagBindingIds());
        state.put(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID, evidenceInvocationId);
        // prompt 在这里作为 Content 进入节点 Agent，state 提供可信工作流和运行身份。
        runner.runAsync(userId, adkSessionId, content, RunConfig.builder().build(),
                        state)
                .blockingForEach(event -> {
                    runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
                    String delta = contentDelta(output.toString(), event.stringifyContent());
                    if (!delta.isEmpty()) {
                        output.append(delta);
                        outputDelta.accept(delta);
                    }
                });
        return new NodeExecutionResult(output.toString(), ragInvocationEvidenceStore.snapshotInvocation(
                tenantId, userId, sessionId, run.getRunId(), evidenceInvocationId));
    }

    /** 独立智能运行时复用节点 Agent/RAG/Tool 上下文装配，不复用旧 Kahn DAG 调度器。 */
    @Override
    public WorkflowNodeInvocationResultEntity invokeCompiledWorkflowNode(WorkflowDagPlanEntity.Node node,
                                                                          ChatRunEntity run,
                                                                          String sessionId,
                                                                          String workflowId,
                                                                          String prompt,
                                                                          String traceId,
                                                                          String roleCode,
                                                                          Integer historyCutoffSequence,
                                                                          String upstreamOutput) {
        if (run == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "智能工作流运行不能为空");
        }
        NodeExecutionResult result = runDagNodeOnce(node, run.getTenantId(), run.getUserId(), sessionId,
                workflowId, prompt, traceId, roleCode, historyCutoffSequence, upstreamOutput, run);
        return WorkflowNodeInvocationResultEntity.builder().output(result.output()).evidence(result.evidence()).build();
    }

    /** 智能运行时与旧工作流共用同一最终消息、引用校验和 Run 终态事务。 */
    @Override
    public void completeCompiledWorkflowRun(ChatRunEntity run, String output, String traceId,
                                            List<RagContextEvidence> evidence) {
        completeRunWithAssistant(run.getTenantId(), run.getUserId(), run.getRunId(), output, traceId,
                evidence == null ? List.of() : evidence);
    }

    /**
     * 构造确定性的节点提示：用户输入、上游结果和循环反馈分区呈现。
     */
    private String buildDagNodePrompt(String userMessage, List<String> upstreamOutputs, String previousOutput, int loopIndex, int loopTotal) {
        List<String> lines = new ArrayList<>();
        lines.add("用户本轮输入：");
        lines.add(userMessage == null ? "" : userMessage);
        if (!upstreamOutputs.isEmpty()) {
            lines.add("\n上游节点输出：");
            lines.add(String.join("\n\n", upstreamOutputs));
        }
        if (loopTotal > 1) {
            lines.add("\n循环信息：");
            lines.add("当前是第 " + loopIndex + " / " + loopTotal + " 次执行。");
            if (previousOutput != null && !previousOutput.isBlank()) {
                lines.add("上一轮输出：");
                lines.add(previousOutput);
            }
        }
        // 收窄节点职责，避免模型自行越过图路由执行其他节点。
        lines.add("\n请只完成你这个节点的任务，并输出本节点结果。");
        return String.join("\n", lines);
    }

    /**
     * 拒绝缺少节点的运行时计划。
     */
    private WorkflowDagPlanEntity requireDagPlan(WorkflowRuntimeEntity runtime) {
        if (runtime == null || runtime.getDagPlan() == null || runtime.getDagPlan().getNodes() == null || runtime.getDagPlan().getNodes().isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 运行计划不存在");
        }
        return runtime.getDagPlan();
    }

    /**
     * 建立普通出边索引；自循环单独作为迭代语义，不参与拓扑入度。
     */
    private Map<String, List<String>> outgoingEdges(WorkflowDagPlanEntity dagPlan) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> outgoing.put(node.getNodeId(), new ArrayList<>()));
        if (dagPlan.getEdges() == null) {
            return outgoing;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            // 计划外端点和自循环都不能进入普通拓扑传播。
            if (isSelfLoop(edge) || !outgoing.containsKey(edge.getSourceNodeId()) || !outgoing.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            outgoing.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
        }
        return outgoing;
    }

    /**
     * 建立普通入边索引，供节点拼接全部直接上游输出。
     */
    private Map<String, List<String>> incomingEdges(WorkflowDagPlanEntity dagPlan) {
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> incoming.put(node.getNodeId(), new ArrayList<>()));
        if (dagPlan.getEdges() == null) {
            return incoming;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            if (isSelfLoop(edge) || !incoming.containsKey(edge.getSourceNodeId()) || !incoming.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            incoming.get(edge.getTargetNodeId()).add(edge.getSourceNodeId());
        }
        return incoming;
    }

    /**
     * 计算忽略自循环后的入度，供 Kahn 算法推进。
     */
    private Map<String, Integer> indegree(WorkflowDagPlanEntity dagPlan) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> indegree.put(node.getNodeId(), 0));
        if (dagPlan.getEdges() == null) {
            return indegree;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            if (isSelfLoop(edge) || !indegree.containsKey(edge.getSourceNodeId()) || !indegree.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            indegree.put(edge.getTargetNodeId(), indegree.get(edge.getTargetNodeId()) + 1);
        }
        return indegree;
    }

    /**
     * 收集配置了自循环的节点，节点执行器据此启用有限迭代。
     */
    private Set<String> selfLoopNodeIds(WorkflowDagPlanEntity dagPlan) {
        if (dagPlan.getEdges() == null) {
            return Collections.emptySet();
        }
        return dagPlan.getEdges().stream()
                .filter(this::isSelfLoop)
                .map(WorkflowDagPlanEntity.Edge::getSourceNodeId)
                .collect(Collectors.toSet());
    }

    /**
     * 仅源、目标均为同一非空 ID 时认定为自循环。
     */
    private boolean isSelfLoop(WorkflowDagPlanEntity.Edge edge) {
        return edge != null && edge.getSourceNodeId() != null && edge.getSourceNodeId().equals(edge.getTargetNodeId());
    }

    /**
     * 按计划节点顺序拼接所有非空终点输出。
     */
    private String terminalOutputs(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing, Map<String, String> outputs) {
        return terminalNodeIds(dagPlan, outgoing).stream()
                .map(nodeId -> outputs.getOrDefault(nodeId, ""))
                .filter(output -> output != null && !output.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 返回无普通后继的终点；异常旧计划兜底选择最后一个节点。
     */
    private List<String> terminalNodeIds(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing) {
        List<String> terminalNodeIds = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> outgoing.getOrDefault(nodeId, Collections.emptyList()).isEmpty())
                .collect(Collectors.toList());
        if (terminalNodeIds.isEmpty()) {
            return List.of(dagPlan.getNodes().get(dagPlan.getNodes().size() - 1).getNodeId());
        }
        return terminalNodeIds;
    }

    /**
     * 解包并行节点异常，保留领域异常而不泄漏 CompletionException。
     */
    private NodeRunResult joinNodeResult(CompletableFuture<NodeRunResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AppException(ResponseCode.UN_ERROR.getCode(), cause.getMessage());
        }
    }

    /**
     * 将循环次数钳制为 1 到 20，防止错误配置无限消耗模型。
     */
    private int safeLoopTimes(Integer maxIterations) {
        if (maxIterations == null || maxIterations < 1) {
            return 1;
        }
        return Math.min(maxIterations, 20);
    }

    /**
     * 为无业务运行的兼容入口构造最小 ADK 状态。
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput) {
        return runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                visibleThroughSequence, upstreamOutput, null, null, null, null, null, List.of());
    }

    /**
     * 构造插件唯一可信的运行状态；租户、用户、会话和运行 ID 均不取自模型文本。
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput,
                                                  String runId, Long contextRevision,
                                                  RagBindingTargetType ragTargetType, String ragQuery,
                                                  String ragMode, List<String> ragBindingIds) {
        Map<String, Object> state = new HashMap<>();
        // 同时写 ADK 链路键和项目工具键，兼容日志插件与工具网关。
        putStateIfPresent(state, TraceContext.TRACE_ID_STATE_KEY, traceId);
        putStateIfPresent(state, ToolRuntimeContextKeys.TRACE_ID, traceId);
        putStateIfPresent(state, ToolRuntimeContextKeys.TENANT_ID, tenantId);
        putStateIfPresent(state, ToolRuntimeContextKeys.USER_ID, userId);
        putStateIfPresent(state, ToolRuntimeContextKeys.SESSION_ID, sessionId);
        putStateIfPresent(state, ToolRuntimeContextKeys.WORKFLOW_ID, workflowId);
        putStateIfPresent(state, ToolRuntimeContextKeys.RUN_ID, runId);
        if (contextRevision != null) {
            // 上下文版本锁定本轮可见快照，压缩完成后也不能污染进行中的调用。
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, contextRevision);
        }
        putStateIfPresent(state, "roleCode", roleCode);
        putStateIfPresent(state, ToolRuntimeContextKeys.CONTEXT_UPSTREAM_OUTPUT, upstreamOutput);
        if (ragTargetType != null) {
            // 只有运行开始时固化为启用，才向上下文插件暴露 RAG 目标和绑定快照。
            state.put(ToolRuntimeContextKeys.RAG_TARGET_TYPE, ragTargetType.name());
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_TARGET_ID, workflowId);
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_MODE, ragMode);
            state.put(ToolRuntimeContextKeys.RAG_BINDING_IDS,
                    ragBindingIds == null ? List.of() : List.copyOf(ragBindingIds));
            putStateIfPresent(state, ToolRuntimeContextKeys.RAG_QUERY, ragQuery);
        }
        if (visibleThroughSequence != null) {
            state.put(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE, visibleThroughSequence);
            // 历史消息截止到本轮输入之前；附件必须包含刚绑定到本轮用户消息的资产。
            state.put(ToolRuntimeContextKeys.CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE,
                    visibleThroughSequence + 1);
        }
        return state;
    }

    /**
     * 空字符串不进入运行状态，避免插件把“存在但无值”误判为可信身份。
     */
    private void putStateIfPresent(Map<String, Object> state, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            state.put(key, value);
        }
    }

    /** 仅按运行快照决定 RAG，忽略会话设置的后续变更。 */
    private RagBindingTargetType ragTargetType(ChatRunEntity run, RagBindingTargetType targetType) {
        return run != null && Boolean.TRUE.equals(run.getRagEnabled()) ? targetType : null;
    }

    /** 禁用 RAG 时不传查询，确保插件不会误触发检索。 */
    private String ragQuery(ChatRunEntity run, String query) {
        return run != null && Boolean.TRUE.equals(run.getRagEnabled()) ? query : null;
    }

    /**
     * 历史上下文截止到本轮用户消息之前；本轮输入由 Runner 单独传入。
     */
    private Integer historyCutoff(ChatMessageEntity userMessage) {
        if (userMessage == null || userMessage.getSequenceNo() == null) {
            return 0;
        }
        return Math.max(0, userMessage.getSequenceNo() - 1);
    }

    /**
     * 为每次调用生成隔离 ADK 会话，避免 ADK 内存历史与数据库 Context Manager 重复。
     */
    private String invocationSessionId(String businessSessionId) {
        return businessSessionId + ":inv:" + UUID.randomUUID();
    }

    /**
     * 缺少业务会话时按公共 Agent 入口新建。
     */
    private String ensureSessionId(String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }
        return sessionId;
    }

    /**
     * 缺少业务会话时复用已校验的运行时 Agent 新建，避免重复查找。
     */
    private String ensureSessionId(String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(sessionAgentId, userId, aiAgentRegisterVO);
        }
        return sessionId;
    }

    /**
     * 在显式可信租户下确保业务会话存在。
     */
    private String ensureSessionId(String tenantId, String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(tenantId, sessionAgentId, userId, aiAgentRegisterVO);
        }
        return sessionId;
    }

    /** 工作流会话缺失时固化本次已解析的实际版本和模型。 */
    private String ensureWorkflowSessionId(String tenantId, String workflowId, String userId, String sessionId,
                                           AiAgentRegisterVO aiAgentRegisterVO, WorkflowRuntimeEntity runtime) {
        if (sessionId == null || sessionId.isBlank()) {
            return createWorkflowSession(tenantId, workflowId, userId, aiAgentRegisterVO, runtime);
        }
        return sessionId;
    }

    /**
     * 公共入口只接受静态 Agent，并执行租户可用性校验。
     */
    private AiAgentRegisterVO requirePublicAgent(String agentId) {
        if (!agentAvailabilityService.isStaticAgent(agentId)) {
            throw new AppException(ResponseCode.E0001.getCode(), ResponseCode.E0001.getInfo());
        }
        agentAvailabilityService.assertEnabled(currentTenantId(), agentId);
        return requireRegisteredAgent(agentId);
    }

    /** 获取已由工作流授权并编译的运行时 Agent；不得用于公共 Agent ID 入口。 */
    private AiAgentRegisterVO requireWorkflowRuntimeAgent(String agentId) {
        return requireRegisteredAgent(agentId);
    }

    /** 从装配仓读取运行体；缺失表示启动装配未完成或配置无效。 */
    private AiAgentRegisterVO requireRegisteredAgent(String agentId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }
        return aiAgentRegisterVO;
    }

    /**
     * 幂等确保本次调用使用的隔离 ADK 会话存在。
     */
    private void ensureAdkSession(InMemoryRunner runner, String appName, String userId, String sessionId) {
        Session session = runner.sessionService()
                .getSession(appName, userId, sessionId, Optional.empty())
                .blockingGet();
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
    private void completeRunWithAssistant(String tenantId, String userId, String runId,
                                          String content, String traceId) {
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        completeRunWithAssistant(tenantId, userId, runId, content, traceId,
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
    private void completeRunWithAssistant(String tenantId, String userId, String runId,
                                          String content, String traceId, List<RagContextEvidence> evidence) {
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        // 引用白名单校验先于落库，消息元数据保存可审计的接受/拒绝结果。
        RagAnswerCitationValidation validation = ragAnswerCitationValidator.validate(content, evidence);
        ChatMessageEntity message = runControlService.completeWithAssistantMessage(tenantId, userId, runId,
                content, traceId, citationMetadata(validation));
        ragInvocationEvidenceStore.clear(run.getTenantId(), run.getUserId(), run.getSessionId(), runId);
        if (message != null) {
            conversationMemoryService.onAssistantMessageSaved(message);
        }
    }

    /** 防止响应完成与取消/异常竞态重复写助手消息。 */
    private void completeRunWithAssistantOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                              String content, String traceId) {
        if (saved.compareAndSet(false, true)) {
            completeRunWithAssistant(tenantId, userId, runId, content, traceId);
        }
    }

    /**
     * 将非取消异常和已生成片段写入助手错误消息，并清除不可再引用的证据。
     */
    private void failRunWithAssistantError(String tenantId, String userId, String runId, String traceId,
                                           Throwable throwable, String partialContent) {
        ChatMessageEntity message = runControlService.failWithAssistantMessage(tenantId, userId, runId,
                errorContent(throwable, partialContent), traceId, safeMessage(throwable));
        if (message != null) {
            conversationMemoryService.onMessageSaved(message);
        }
        ChatRunEntity run = runControlService.require(tenantId, userId, runId);
        ragInvocationEvidenceStore.clear(run.getTenantId(), run.getUserId(), run.getSessionId(), runId);
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
    private void failRunWithAssistantErrorOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                               String traceId, Throwable throwable, String partialContent) {
        if (saved.compareAndSet(false, true)) {
            failRunWithAssistantError(tenantId, userId, runId, traceId, throwable, partialContent);
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
