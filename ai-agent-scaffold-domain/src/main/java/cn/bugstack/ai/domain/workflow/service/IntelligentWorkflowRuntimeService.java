package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowExecutionAuditRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRouteIntentRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeInvocationResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;
import cn.bugstack.ai.domain.rag.service.RagToolCapabilityService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/** 单活动路径智能工作流运行时；节点执行、路由裁决和最终回答均由服务端推进。 */
@Service
public class IntelligentWorkflowRuntimeService {

    /** 加载已经发布并编译完成的工作流运行快照。 */
    private final IWorkflowService workflowService;
    /** 创建工作流会话并持久化最终助手消息。 */
    private final IChatService chatService;
    /** 创建、校验和终结权威 Chat Run。 */
    private final RunControlService runControlService;
    /** 保存智能工作流当前节点、预算和乐观锁版本。 */
    private final IIntelligentWorkflowRunRepository intelligentRunRepository;
    /** 持久化并实时发布节点、工具、路由和终态事件。 */
    private final WorkflowEventStreamService eventStreamService;
    /** 根据冻结出边和节点结果执行确定性路由裁决。 */
    private final IntelligentWorkflowRouter router;
    /** 汇总节点模型调用的 Token 用量。 */
    private final ModelUsageService modelUsageService;
    /** 在模型和工具调用前后检查取消、定义版本和执行权。 */
    private final WorkflowInvocationGuardService invocationGuardService;
    /** 持久化节点执行与权威路由决定。 */
    private final IWorkflowExecutionAuditRepository executionAuditRepository;
    /** 读取并消费路由工具登记的唯一意图。 */
    private final IWorkflowRouteIntentRepository routeIntentRepository;
    /** 在事务提交后异步协调节点执行，避免占用请求线程。 */
    private final ExecutorService coordinatorExecutor;
    /** 编码工作流事件和节点输出 JSON。 */
    private final ObjectMapper objectMapper;
    /** 将意图消费、路由决定和状态推进包在同一个事务中。 */
    private final TransactionTemplate transitionTransaction;
    /** 可选的 RAG 能力说明服务；未装配时节点不获得 RAG 工具提示。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RagToolCapabilityService ragToolCapabilityService;

    /** 创建无需真实事务管理器的运行时，供隔离测试直接执行路由推进。 */
    public IntelligentWorkflowRuntimeService(IWorkflowService workflowService,
                                             IChatService chatService,
                                             RunControlService runControlService,
                                             IIntelligentWorkflowRunRepository intelligentRunRepository,
                                             WorkflowEventStreamService eventStreamService,
                                             IntelligentWorkflowRouter router,
                                             ModelUsageService modelUsageService,
                                              WorkflowInvocationGuardService invocationGuardService,
                                             IWorkflowExecutionAuditRepository executionAuditRepository,
                                             IWorkflowRouteIntentRepository routeIntentRepository,
                                             @Qualifier("workflowCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                             ObjectMapper objectMapper) {
        this(workflowService, chatService, runControlService, intelligentRunRepository, eventStreamService, router,
                modelUsageService, invocationGuardService, executionAuditRepository, routeIntentRepository,
                coordinatorExecutor, objectMapper, null);
    }

    /** 生产运行时使用事务模板把一次路由推进作为单个持久化闭环。 */
    @org.springframework.beans.factory.annotation.Autowired
    public IntelligentWorkflowRuntimeService(IWorkflowService workflowService,
                                             IChatService chatService,
                                             RunControlService runControlService,
                                             IIntelligentWorkflowRunRepository intelligentRunRepository,
                                             WorkflowEventStreamService eventStreamService,
                                             IntelligentWorkflowRouter router,
                                             ModelUsageService modelUsageService,
                                             WorkflowInvocationGuardService invocationGuardService,
                                             IWorkflowExecutionAuditRepository executionAuditRepository,
                                             IWorkflowRouteIntentRepository routeIntentRepository,
                                             @Qualifier("workflowCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                             ObjectMapper objectMapper,
                                             PlatformTransactionManager transactionManager) {
        this.workflowService = workflowService;
        this.chatService = chatService;
        this.runControlService = runControlService;
        this.intelligentRunRepository = intelligentRunRepository;
        this.eventStreamService = eventStreamService;
        this.router = router;
        this.modelUsageService = modelUsageService;
        this.invocationGuardService = invocationGuardService;
        this.executionAuditRepository = executionAuditRepository;
        this.routeIntentRepository = routeIntentRepository;
        this.coordinatorExecutor = coordinatorExecutor;
        this.objectMapper = objectMapper;
        this.transitionTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /** 事务内创建 chat_run、扩展状态、用户消息和首事件；提交后才启动后台执行。 */
    @Transactional(rollbackFor = Exception.class)
    public IntelligentWorkflowRunEntity start(IntelligentWorkflowStartCommandEntity command) {
        validate(command);
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(command.getTenantId(), command.getUserId(),
                command.getRoleCode(), command.getWorkflowId(), command.getWorkflowVersion(), command.getModelCode());
        WorkflowDagPlanEntity plan = runtime.getDagPlan();
        if (plan == null || !"INTELLIGENT".equalsIgnoreCase(plan.getWorkflowKind())) {
            throw new AppException("WORKFLOW_NOT_INTELLIGENT", "所选发布版本不是智能工作流");
        }
        if (blank(plan.getDefinitionHash())) plan.setDefinitionHash(hash(plan));
        String sessionId = blank(command.getSessionId())
                ? chatService.createWorkflowSession(command.getWorkflowId(), runtime.getVersion(),
                runtime.getEffectiveModelCode(), command.getUserId()) : command.getSessionId();
        ChatRunEntity run = runControlService.startOrResume(command.getTenantId(), command.getUserId(), sessionId,
                "workflow", command.getWorkflowId(), command.getRequestedRunId());
        IntelligentWorkflowRunEntity existing = intelligentRunRepository.query(command.getTenantId(), command.getUserId(), run.getRunId());
        if (existing != null) return existing;

        RunMessageBindingEntity binding = runControlService.appendUserMessage(command.getTenantId(), command.getUserId(),
                run.getRunId(), command.getMessage(), run.getTraceId(), safeList(command.getAttachmentIds()));
        ChatRunEntity activeRun = binding.getRun();
        IntelligentWorkflowRunEntity intelligentRun = IntelligentWorkflowRunEntity.builder()
                .tenantId(activeRun.getTenantId()).userId(activeRun.getUserId()).runId(activeRun.getRunId())
                .workflowId(command.getWorkflowId()).workflowVersion(runtime.getVersion())
                .definitionHash(plan.getDefinitionHash()).traceId(activeRun.getTraceId()).status("RUNNING")
                .currentNodeId(plan.getRootNodeId()).nextSequence(1L).executedSteps(0).usedTokens(0L)
                .maxSteps(plan.getMaxSteps()).tokenBudget(plan.getTokenBudget()).variablesJson("{}")
                .revision(0L).startedAt(LocalDateTime.now()).build();
        if (intelligentRunRepository.insert(intelligentRun) != 1) {
            throw new AppException("WORKFLOW_RUN_CREATE_FAILED", "智能工作流运行状态创建失败");
        }
        publish(activeRun, "WORKFLOW_STARTED", null, null, Map.of(
                "workflowId", command.getWorkflowId(), "workflowVersion", runtime.getVersion(),
                "rootNodeId", plan.getRootNodeId(), "definitionHash", intelligentRun.getDefinitionHash()));

        Runnable execution = TraceContext.wrap(activeRun.getTraceId(), () -> execute(runtime, plan, activeRun, sessionId, command.getMessage(),
                command.getRoleCode(), binding.getMessage().getSequenceNo()));
        afterCommit(() -> coordinatorExecutor.submit(execution));
        return intelligentRun;
    }

    /** 沿单条活动路径执行节点，逐步检查取消与预算，并在每个节点后完成权威路由。 */
    private void execute(WorkflowRuntimeEntity runtime, WorkflowDagPlanEntity plan, ChatRunEntity run,
                         String sessionId, String userMessage, String roleCode, Integer historyCutoffSequence) {
        Map<String, WorkflowDagPlanEntity.Node> nodes = plan.getNodes().stream()
                .collect(Collectors.toMap(WorkflowDagPlanEntity.Node::getNodeId, node -> node,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<WorkflowDagPlanEntity.Edge>> outgoing = plan.getEdges() == null ? Collections.emptyMap()
                : plan.getEdges().stream().collect(Collectors.groupingBy(WorkflowDagPlanEntity.Edge::getSourceNodeId,
                LinkedHashMap::new, Collectors.toList()));
        Map<String, Integer> visits = new LinkedHashMap<>();
        List<RagContextEvidence> evidence = new ArrayList<>();
        String currentNodeId = plan.getRootNodeId();
        String previousOutput = "";
        int steps = 0;
        try {
            while (!"END".equalsIgnoreCase(currentNodeId)) {
                runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);
                WorkflowDagPlanEntity.Node node = nodes.get(currentNodeId);
                if (node == null) throw new AppException("WORKFLOW_NODE_NOT_FOUND", "智能工作流节点不存在: " + currentNodeId);
                int executionIndex = visits.merge(currentNodeId, 1, Integer::sum);
                if (executionIndex > safeMaxVisits(node.getMaxVisits())) {
                    throw new AppException("WORKFLOW_NODE_VISIT_LIMIT", "节点访问次数超过上限: " + currentNodeId);
                }
                String nodeExecutionId = "wne_" + java.util.UUID.randomUUID();
                long nodeStartedNanos = System.nanoTime();
                WorkflowNodeExecutionEntity executionAudit = WorkflowNodeExecutionEntity.builder()
                        .tenantId(run.getTenantId()).runId(run.getRunId()).nodeExecutionId(nodeExecutionId)
                        .nodeId(currentNodeId).executionIndex(executionIndex).attempt(1).status("RUNNING")
                        .traceId(run.getTraceId()).startedAt(LocalDateTime.now()).build();
                executionAuditRepository.startNode(executionAudit);
                AiLog.info(AiLog.workflow().nodeStarted(run.getTenantId(), run.getUserId(), sessionId, run.getRunId(),
                        runtime.getWorkflowId(), currentNodeId, executionIndex, safeMaxVisits(node.getMaxVisits()), 1));
                publish(run, "NODE_STARTED", nodeExecutionId, currentNodeId,
                        Map.of("nodeName", safe(node.getNodeName()), "executionIndex", executionIndex, "attempt", 1));
                boolean toolV2 = "TOOL_V2".equalsIgnoreCase(plan.getRoutingProtocolVersion());
                String prompt = intelligentPrompt(userMessage, previousOutput, node.getRouteInstruction(),
                        routeProtocol(outgoing.getOrDefault(currentNodeId, List.of()), nodes), executionIndex, toolV2,
                        ragGuidance(run, node));
                WorkflowInvocationEntity invocation = invocationGuardService.modelInvocation(run, nodeExecutionId);
                if (!invocationGuardService.register(invocation, run.getUserId())) {
                    throw new AppException("WORKFLOW_INVOCATION_DUPLICATE", "节点模型调用已登记，拒绝重复执行");
                }
                WorkflowNodeInvocationResultEntity result;
                try {
                    result = chatService.invokeCompiledWorkflowNode(node, plan, run, nodeExecutionId, false, sessionId,
                            runtime.getWorkflowId(), prompt, run.getTraceId(), roleCode, historyCutoffSequence, previousOutput);
                    invocationGuardService.success(run.getTenantId(), invocation.getInvocationId(), null);
                } catch (RuntimeException invocationError) {
                    invocationGuardService.failed(run.getTenantId(), invocation.getInvocationId(), null);
                    boolean cancelledDuringInvocation = runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId());
                    executionAudit.setStatus(cancelledDuringInvocation ? "CANCELLED" : "FAILED");
                    executionAudit.setErrorCode(cancelledDuringInvocation ? "RUN_CANCELLED" : errorCode(invocationError));
                    executionAudit.setErrorMessage(safe(invocationError.getMessage())); executionAudit.setFinishedAt(LocalDateTime.now());
                    executionAuditRepository.completeNode(executionAudit);
                    if (cancelledDuringInvocation) {
                        AiLog.info(AiLog.workflow().nodeCancelled(run.getTenantId(), run.getUserId(), sessionId,
                                run.getRunId(), runtime.getWorkflowId(), currentNodeId, executionIndex,
                                safeMaxVisits(node.getMaxVisits()), elapsedMs(nodeStartedNanos))
                                .field(AiLogFields.TRACE_ID, run.getTraceId()));
                    } else {
                        AiLog.error(AiLog.workflow().nodeFailed(run.getTenantId(), run.getUserId(), sessionId, run.getRunId(),
                                runtime.getWorkflowId(), currentNodeId, executionIndex, safeMaxVisits(node.getMaxVisits()),
                                elapsedMs(nodeStartedNanos), invocationError));
                        publish(run, "NODE_FAILED", nodeExecutionId, currentNodeId, Map.of(
                                "nodeName", safe(node.getNodeName()),
                                "errorCode", errorCode(invocationError),
                                "message", safe(invocationError.getMessage()),
                                "executionIndex", executionIndex));
                    }
                    if (cancelledDuringInvocation) throw invocationError;
                    IntelligentWorkflowRouter.RouteDecision failure = router.decide(
                            new IntelligentWorkflowRouter.RouteContext(false, true, "", null, null,
                                    steps, plan.getMaxSteps(), 0L, plan.getTokenBudget()), node,
                            outgoing.getOrDefault(currentNodeId, List.of()));
                    final String failedNodeId = currentNodeId;
                    final int failedSteps = steps;
                    inTransition(() -> {
                        persistDecision(run, runtime, nodeExecutionId, failedNodeId, nodes, failure,
                                null, "RUNTIME_FAILURE");
                        updateState(run, failure.targetNodeId(), "RUNNING", failedSteps, 0L, null);
                    });
                    currentNodeId = failure.targetNodeId();
                    continue;
                }
                String output = safe(result.getOutput());
                if (result.getEvidence() != null) evidence.addAll(result.getEvidence());
                if (!output.isEmpty()) publish(run, "NODE_OUTPUT_DELTA", nodeExecutionId, currentNodeId, Map.of("delta", output));
                long usedTokens = modelUsageService.summarizeSession(run.getTenantId(), run.getUserId(), sessionId,
                        run.getRunId()).getTotalTokens();
                steps++;
                if (Boolean.TRUE.equals(node.getTerminal())) {
                    completeExecution(run, runtime, executionAudit, node, nodeExecutionId, currentNodeId,
                            executionIndex, result, output, usedTokens, nodeStartedNanos);
                    previousOutput = output;
                    currentNodeId = "END";
                    updateState(run, currentNodeId, "RUNNING", steps, usedTokens, null);
                    continue;
                }
                WorkflowRouteIntentEntity intent = toolV2 ? routeIntentRepository.queryByNode(
                        run.getTenantId(), run.getRunId(), nodeExecutionId) : null;
                String source = toolV2 ? "ROUTE_TOOL" : "MARKER_V1";
                if (toolV2 && intent == null && !Boolean.TRUE.equals(node.getTerminal())) {
                    publish(run, "ROUTE_REPAIR_STARTED", nodeExecutionId, currentNodeId, Map.of("attempt", 1));
                    runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);
                    try {
                        chatService.invokeCompiledWorkflowNode(node, plan, run, nodeExecutionId, true, sessionId,
                                runtime.getWorkflowId(), repairPrompt(output, node), run.getTraceId(), roleCode,
                                historyCutoffSequence, previousOutput);
                    } catch (RuntimeException repairError) {
                        if (runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId())) throw repairError;
                        publish(run, "ROUTE_REPAIR_COMPLETED", nodeExecutionId, currentNodeId,
                                Map.of("success", false, "errorCode", errorCode(repairError)));
                        IntelligentWorkflowRouter.RouteDecision failure = router.decide(
                                new IntelligentWorkflowRouter.RouteContext(false, true, output, null, null,
                                        steps, plan.getMaxSteps(), usedTokens, plan.getTokenBudget()), node,
                                outgoing.getOrDefault(currentNodeId, List.of()));
                        final String repairNodeId = currentNodeId;
                        final int repairSteps = steps;
                        final String repairOutput = output;
                        inTransition(() -> {
                            persistDecision(run, runtime, nodeExecutionId, repairNodeId, nodes, failure,
                                    null, "RUNTIME_FAILURE");
                            completeExecution(run, runtime, executionAudit, node, nodeExecutionId, repairNodeId,
                                    executionIndex, result, repairOutput, usedTokens, nodeStartedNanos);
                            updateState(run, failure.targetNodeId(), "RUNNING", repairSteps, usedTokens, null);
                        });
                        previousOutput = output;
                        currentNodeId = failure.targetNodeId();
                        continue;
                    }
                    intent = routeIntentRepository.queryByNode(run.getTenantId(), run.getRunId(), nodeExecutionId);
                    publish(run, "ROUTE_REPAIR_COMPLETED", nodeExecutionId, currentNodeId,
                            intent == null ? Map.of("success", false) : Map.of("success", true,
                                    "routeKey", intent.getRouteKey(), "functionCallId", intent.getFunctionCallId()));
                    source = "ROUTE_REPAIR";
                }
                String routeKey = toolV2 ? intent == null ? null : intent.getRouteKey() : routeMarker(output);
                IntelligentWorkflowRouter.RouteDecision decision = router.decide(
                        new IntelligentWorkflowRouter.RouteContext(false, false, output, routeKey, routeKey,
                                steps, plan.getMaxSteps(), usedTokens, plan.getTokenBudget()),
                        node, outgoing.getOrDefault(currentNodeId, List.of()));
                if (toolV2 && intent == null && !"DEFAULT".equals(decision.strategy())) {
                    throw new AppException("WORKFLOW_ROUTE_REQUIRED", "智能节点必须选择合法路由");
                }
                if ("BUDGET_GUARD".equals(decision.strategy())) {
                    throw new AppException("WORKFLOW_BUDGET_EXCEEDED", "智能工作流执行预算已耗尽");
                }
                if (intent != null) output = appendRouteExplanation(output,
                        nodes.get(decision.targetNodeId()) == null ? decision.targetNodeId()
                                : nodes.get(decision.targetNodeId()).getNodeName(), intent.getRouteKey());
                final String transitionOutput = output;
                final WorkflowRouteIntentEntity transitionIntent = intent;
                final String transitionSource = source;
                final String transitionNodeId = currentNodeId;
                final int transitionSteps = steps;
                inTransition(() -> {
                    if (transitionIntent != null) {
                        if (!same(transitionIntent.getDefinitionHash(), plan.getDefinitionHash())
                                || !same(transitionIntent.getWorkflowId(), plan.getWorkflowId())
                                || !same(transitionIntent.getResolvedEdgeId(), decision.edgeId())
                                || routeIntentRepository.consume(run.getTenantId(), run.getRunId(), nodeExecutionId,
                                LocalDateTime.now()) != 1) {
                            throw new AppException("WORKFLOW_ROUTE_INTENT_INVALID", "路由意图与冻结定义不一致或已消费");
                        }
                    }
                    persistDecision(run, runtime, nodeExecutionId, transitionNodeId, nodes, decision, transitionIntent, transitionSource);
                    completeExecution(run, runtime, executionAudit, node, nodeExecutionId, transitionNodeId,
                            executionIndex, result, transitionOutput, usedTokens, nodeStartedNanos);
                    updateState(run, decision.targetNodeId(), "RUNNING", transitionSteps, usedTokens, null);
                });
                previousOutput = output;
                currentNodeId = decision.targetNodeId();
            }
            runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);
            if (!previousOutput.isEmpty()) publish(run, "FINAL_ANSWER_DELTA", null, null, Map.of("delta", previousOutput));
            chatService.completeCompiledWorkflowRun(run, previousOutput, run.getTraceId(), evidence);
            publish(run, "FINAL_ANSWER_COMPLETED", null, null, Map.of("content", previousOutput));
            publish(run, "WORKFLOW_COMPLETED", null, null, Map.of("executedSteps", steps));
            updateState(run, "END", "COMPLETED", steps,
                    modelUsageService.summarizeSession(run.getTenantId(), run.getUserId(), sessionId, run.getRunId()).getTotalTokens(),
                    LocalDateTime.now());
        } catch (Throwable exception) {
            if (!runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId())) {
                runControlService.failWithAssistantMessage(run.getTenantId(), run.getUserId(), run.getRunId(),
                        "[assistant_error] " + safe(exception.getMessage()), run.getTraceId(), safe(exception.getMessage()));
                publish(run, "WORKFLOW_FAILED", null, currentNodeId,
                        Map.of("errorCode", exception instanceof AppException app ? app.getCode() : "WORKFLOW_EXECUTION_FAILED",
                                "message", safe(exception.getMessage()), "executedSteps", steps));
                updateState(run, currentNodeId, "FAILED", steps, 0L, LocalDateTime.now());
            } else {
                reconcileCancellation(run);
            }
            if (exception instanceof Error error) throw error;
        }
    }

    /** 取消请求成功后同步收口智能扩展状态；可重复调用，终态 Run 不重复写事件。 */
    @Transactional(rollbackFor = Exception.class)
    public void reconcileCancellation(ChatRunEntity run) {
        LocalDateTime finishedAt = LocalDateTime.now();
        // 取消与后台节点推进会竞争 revision；以非终态条件更新作为唯一线性化点，避免旧 revision 误报失败。
        if (intelligentRunRepository.cancelActive(run.getTenantId(), run.getUserId(), run.getRunId(), finishedAt) == 0) return;
        IntelligentWorkflowRunEntity state = intelligentRunRepository.query(run.getTenantId(), run.getUserId(), run.getRunId());
        if (state == null) throw new AppException("WORKFLOW_RUN_NOT_FOUND", "取消后无法读取智能工作流运行");
        int cancelledNodes = executionAuditRepository.cancelRunningNodes(run.getTenantId(), run.getRunId(), finishedAt);
        if (cancelledNodes > 0) {
            AiLog.info(AiLog.workflow().nodeCancelled(run.getTenantId(), run.getUserId(), run.getSessionId(),
                    run.getRunId(), state.getWorkflowId(), state.getCurrentNodeId(), null, null,
                    elapsed(run.getStartedAt()))
                    .field(AiLogFields.TRACE_ID, run.getTraceId()));
        }
        publish(run, "WORKFLOW_CANCELLED", null, state.getCurrentNodeId(),
                Map.of("executedSteps", state.getExecutedSteps(), "cancelledNodes", cancelledNodes));
    }

    /** 以 revision 乐观锁更新智能运行状态，并对短暂并发冲突最多重试三次。 */
    private void updateState(ChatRunEntity run, String nodeId, String status, int steps, long tokens, LocalDateTime finishedAt) {
        for (int attempt = 0; attempt < 3; attempt++) {
            IntelligentWorkflowRunEntity state = intelligentRunRepository.query(run.getTenantId(), run.getUserId(), run.getRunId());
            if (state == null) return;
            long revision = state.getRevision();
            state.setCurrentNodeId(nodeId); state.setStatus(status); state.setExecutedSteps(steps);
            state.setUsedTokens(tokens); state.setFinishedAt(finishedAt);
            if (intelligentRunRepository.updateState(state, revision) == 1) return;
        }
        throw new AppException("WORKFLOW_RUN_CONCURRENT_MODIFICATION", "智能工作流运行状态并发更新失败");
    }

    /** 将运行身份和结构化负载交给统一事件流持久化与推送。 */
    private void publish(ChatRunEntity run, String type, String executionId, String nodeId, Map<String, ?> payload) {
        eventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(), type,
                executionId, nodeId, json(payload));
    }

    /** 在测试环境可直接执行；生产环境将一次路由推进绑定到同一数据库事务。 */
    private void inTransition(Runnable transition) {
        if (transitionTransaction == null) {
            transition.run();
            return;
        }
        transitionTransaction.executeWithoutResult(status -> transition.run());
    }

    /** 持久化唯一权威路由决定，并发布包含目标名称和来源的 ROUTE_DECIDED 事件。 */
    private void persistDecision(ChatRunEntity run, WorkflowRuntimeEntity runtime, String nodeExecutionId,
                                 String sourceNodeId, Map<String, WorkflowDagPlanEntity.Node> nodes,
                                 IntelligentWorkflowRouter.RouteDecision decision,
                                 WorkflowRouteIntentEntity intent, String source) {
        executionAuditRepository.decideRoute(WorkflowRouteDecisionEntity.builder()
                .tenantId(run.getTenantId()).runId(run.getRunId()).routeDecisionId("wrd_" + java.util.UUID.randomUUID())
                .nodeExecutionId(nodeExecutionId).sourceNodeId(sourceNodeId).targetNodeId(decision.targetNodeId())
                .edgeId(decision.edgeId()).strategy(decision.strategy()).reason(
                        intent == null ? decision.reason() : intent.getReason())
                .terminal(decision.terminal()).traceId(run.getTraceId()).build());
        AiLog.info(AiLog.workflow().routeDecided(run.getTenantId(), run.getUserId(), run.getSessionId(), run.getRunId(),
                runtime.getWorkflowId(), nodeExecutionId, sourceNodeId, decision.targetNodeId(),
                decision.strategy(), intent == null ? decision.reason() : intent.getReason(), decision.terminal()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy", decision.strategy());
        payload.put("source", source);
        payload.put("targetNodeId", decision.targetNodeId());
        WorkflowDagPlanEntity.Node target = nodes.get(decision.targetNodeId());
        payload.put("targetNodeName", target == null ? decision.targetNodeId() : safe(target.getNodeName()));
        payload.put("terminal", decision.terminal());
        payload.put("reason", intent == null ? decision.reason() : safe(intent.getReason()));
        if (intent != null) {
            payload.put("routeKey", intent.getRouteKey());
            payload.put("functionCallId", intent.getFunctionCallId());
        }
        publish(run, "ROUTE_DECIDED", nodeExecutionId, sourceNodeId, payload);
    }

    /** 完成节点执行审计，记录输出与用量并发布 NODE_COMPLETED。 */
    private void completeExecution(ChatRunEntity run, WorkflowRuntimeEntity runtime,
                                   WorkflowNodeExecutionEntity executionAudit, WorkflowDagPlanEntity.Node node,
                                   String nodeExecutionId, String nodeId, int executionIndex,
                                   WorkflowNodeInvocationResultEntity result, String output, long usedTokens,
                                   long nodeStartedNanos) {
        executionAudit.setStatus("COMPLETED");
        executionAudit.setDisplayOutput(output);
        executionAudit.setOutputJson(json(Map.of("output", output)));
        executionAudit.setPromptTokens(0L);
        executionAudit.setCandidateTokens(0L);
        executionAudit.setTotalTokens(usedTokens);
        executionAudit.setFinishedAt(LocalDateTime.now());
        executionAuditRepository.completeNode(executionAudit);
        AiLog.info(AiLog.workflow().nodeCompleted(run.getTenantId(), run.getUserId(), run.getSessionId(), run.getRunId(),
                runtime.getWorkflowId(), nodeId, executionIndex, safeMaxVisits(node.getMaxVisits()),
                output.length(), result.getEvidence() == null ? 0 : result.getEvidence().size(),
                elapsedMs(nodeStartedNanos)));
        publish(run, "NODE_COMPLETED", nodeExecutionId, nodeId,
                Map.of("displayOutput", output, "executionIndex", executionIndex, "totalTokens", usedTokens));
    }

    /** 构造只允许补调一次路由工具的修复提示，不重新执行原节点任务。 */
    private String repairPrompt(String output, WorkflowDagPlanEntity.Node node) {
        return "原节点输出摘要：\n" + safe(output) + "\n\n本轮只允许调用一次 select_workflow_route。"
                + "必须从当前工具 schema 的合法 routeKey 中选择，不得调用其他工具，也不要重新执行原任务。";
    }

    /** 在最终展示文本中幂等追加中文路由说明和旧协议兼容标记。 */
    private String appendRouteExplanation(String output, String targetNodeName, String routeKey) {
        String explanation = "经判断，路由到「" + safe(targetNodeName) + "」节点。";
        String marker = "[route:" + safe(routeKey) + "]";
        String value = safe(output);
        if (!value.contains(explanation)) value = value.isBlank() ? explanation : value + "\n" + explanation;
        if (!value.contains(marker)) value = value + "\n" + marker;
        return value;
    }

    /** 对可选文本执行空值安全的精确比较。 */
    private boolean same(String left, String right) {
        return safe(left).equals(safe(right));
    }

    /** 保留领域错误码，其他运行时异常统一映射为节点执行失败。 */
    private String errorCode(RuntimeException exception) {
        return exception instanceof AppException app ? app.getCode() : "WORKFLOW_NODE_EXECUTION_FAILED";
    }

    /** 使用单调时钟计算节点耗时，避免系统时钟调整影响指标。 */
    private long elapsedMs(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** 组装当前节点输入、上游输出和冻结路由协议，不暴露未允许的目标节点。 */
    private String intelligentPrompt(String userMessage, String previousOutput, String routeInstruction,
                                     String routeProtocol, int executionIndex, boolean toolV2,
                                     String ragGuidance) {
        return "用户本轮输入：\n" + safe(userMessage) + "\n\n上一节点输出：\n" + safe(previousOutput)
                + "\n\n当前执行次数：" + executionIndex
                + "\n\n业务路由判断要求：\n" + safe(routeInstruction)
                + "\n\n系统允许的精确路由键：\n" + safe(routeProtocol)
                 + (toolV2
                 ? "\n必须调用 select_workflow_route(routeKey, reason) 登记唯一选择；不要在正文猜测目标节点。"
                 : "\n如需建议路由，只能在正文末尾单独输出 [route:路由键]；请完成当前节点任务，不要直接调用其他节点。")
                + safe(ragGuidance);
    }

    /** 按运行快照和节点策略生成模型可见的 RAG 能力说明。 */
    private String ragGuidance(ChatRunEntity run, WorkflowDagPlanEntity.Node node) {
        if (ragToolCapabilityService == null) return "";
        return ragToolCapabilityService.guidance(run.getTenantId(), run.getRagInvocationMode(),
                run.getRagBindingIds(), node.getRagToolEnabled());
    }

    /** 从冻结出边生成不可省略的精确路由协议，避免提示词和 graph_json 漂移。 */
    private String routeProtocol(List<WorkflowDagPlanEntity.Edge> edges,
                                 Map<String, WorkflowDagPlanEntity.Node> nodes) {
        List<String> lines = new ArrayList<>();
        for (WorkflowDagPlanEntity.Edge edge : edges == null ? List.<WorkflowDagPlanEntity.Edge>of() : edges) {
            String type = safe(edge.getRouteType()).toUpperCase(Locale.ROOT);
            if ("NODE_SUGGESTION".equals(type) || "AI_ROUTER".equals(type)) {
                WorkflowDagPlanEntity.Node target = nodes.get(edge.getTargetNodeId());
                String targetName = target == null ? edge.getTargetNodeId() : safe(target.getNodeName());
                String aliases = edge.getRouteAliases() == null || edge.getRouteAliases().isEmpty()
                        ? "" : "；兼容别名：" + String.join("、", edge.getRouteAliases());
                lines.add("- " + edge.getRouteKey() + " -> " + targetName + "（" + edge.getTargetNodeId()
                        + "）；精确输出 [route:" + edge.getRouteKey() + "]" + aliases);
            } else if ("DEFAULT".equals(type)) {
                lines.add("- 未命中上述键 -> DEFAULT -> " + edge.getTargetNodeId());
            }
        }
        return lines.isEmpty() ? "- 当前节点没有可建议的路由键。" : String.join("\n", lines);
    }

    /** 仅为旧 MARKER_V1 工作流提取正文末尾的兼容路由标记。 */
    private String routeMarker(String output) {
        return WorkflowRouteKey.markerAtEnd(output);
    }

    /** 为缺少历史 definitionHash 的运行计划计算稳定兼容摘要。 */
    private String hash(WorkflowDagPlanEntity plan) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(plan));
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item));
            return value.toString();
        } catch (Exception exception) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "智能工作流定义哈希失败");
        }
    }

    /** 将事件负载编码为 JSON，编码失败时显式终止当前推进。 */
    private String json(Map<String, ?> payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException exception) { throw new AppException("WORKFLOW_EVENT_INVALID", "工作流事件编码失败"); }
    }

    /** 有事务时在提交后执行副作用，无事务测试环境中立即执行。 */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run(); return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    /** 校验启动智能工作流所需的可信身份、工作流和用户输入。 */
    private void validate(IntelligentWorkflowStartCommandEntity command) {
        if (command == null || blank(command.getTenantId()) || blank(command.getUserId())
                || blank(command.getWorkflowId()) || blank(command.getMessage())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户、工作流和消息不能为空");
        }
    }

    /** 将节点访问上限限制在 1 到 50，旧定义缺失时使用 3。 */
    private int safeMaxVisits(Integer value) { return value == null ? 3 : Math.max(1, Math.min(value, 50)); }
    /** 将可选列表归一为空列表，避免运行时分支反复处理 null。 */
    private <T> List<T> safeList(List<T> value) { return value == null ? List.of() : value; }
    /** 判断必填文本是否缺失。 */
    private boolean blank(String value) { return value == null || value.isBlank(); }
    /** 将可选展示文本归一为空串。 */
    private String safe(String value) { return value == null ? "" : value; }

    /** 根据运行开始时间计算非负耗时。 */
    private long elapsed(LocalDateTime startedAt) {
        return startedAt == null ? 0L : Math.max(0L, java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis());
    }
}
