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

/** 单路径智能工作流运行服务；节点执行、下一节点选择和最终回答均由服务端推进。 */
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
    /** 根据本次运行保存的合法连线和节点结果确定下一节点。 */
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
    /** 限制 Agent 节点尝试次数，并在逐次等待期间持续检查取消。 */
    private final WorkflowNodeRetryPolicy nodeRetryPolicy;
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
                coordinatorExecutor, objectMapper, null, WorkflowNodeRetryPolicy.defaults());
    }

    /** 测试可注入零等待或可观察的重试规则，不必真实休眠。 */
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
                                             ExecutorService coordinatorExecutor,
                                             ObjectMapper objectMapper,
                                             WorkflowNodeRetryPolicy nodeRetryPolicy) {
        this(workflowService, chatService, runControlService, intelligentRunRepository, eventStreamService, router,
                modelUsageService, invocationGuardService, executionAuditRepository, routeIntentRepository,
                coordinatorExecutor, objectMapper, null, nodeRetryPolicy);
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
                                             PlatformTransactionManager transactionManager,
                                             WorkflowNodeRetryPolicy nodeRetryPolicy) {
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
        this.nodeRetryPolicy = nodeRetryPolicy == null ? WorkflowNodeRetryPolicy.defaults() : nodeRetryPolicy;
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
        afterCommit(() -> submitExecution(activeRun, execution));
        return intelligentRun;
    }

    /**
     * 沿单条活动路径执行智能工作流，直到进入 {@code END}。
     *
     * <p>这个方法是智能工作流的核心状态机，负责节点执行、取消门禁、
     * 环路限制、Token/步数预算、RAG 证据收集、路径补选、服务端确定下一节点以及最终收尾。</p>
     *
     * <p>重要边界：{@code select_workflow_route} 工具只保存 Agent 的下一步建议，不能直接推进节点。
     * 服务端必须检查建议是否属于当前合法连线，并在同一事务中把建议标记为已使用、保存选择结果和更新当前节点。</p>
     *
     * @param runtime 已编译并装配好的工作流运行时
     * @param plan 根据本次发布版本编译出的 DAG 执行计划
     * @param run 本次运行的根记录，提供可信身份和根 Trace ID
     * @param sessionId 业务会话 ID，用于上下文和 Token 用量汇总
     * @param userMessage 本轮用户原始问题
     * @param roleCode 当前用户角色
     * @param historyCutoffSequence 本轮可见的历史消息最大序号
     */
    private void execute(WorkflowRuntimeEntity runtime, WorkflowDagPlanEntity plan, ChatRunEntity run,
                         String sessionId, String userMessage, String roleCode, Integer historyCutoffSequence) {
        // 将节点列表预处理为 nodeId -> Node 索引，避免循环中反复线性查找。
        // 保留原始顺序便于稳定调试；重复 nodeId 理论上应已被编译器拦截。
        Map<String, WorkflowDagPlanEntity.Node> nodes = plan.getNodes().stream()
                .collect(Collectors.toMap(WorkflowDagPlanEntity.Node::getNodeId, node -> node,
                        (left, right) -> left, LinkedHashMap::new));

        // 将边按源节点分组。服务端每次只能在本次运行保存的当前节点出边中选择，
        // 因此模型无法通过伪造 targetNodeId 跳转到任意节点。
        Map<String, List<WorkflowDagPlanEntity.Edge>> outgoing = plan.getEdges() == null ? Collections.emptyMap()
                : plan.getEdges().stream().collect(Collectors.groupingBy(WorkflowDagPlanEntity.Edge::getSourceNodeId,
                LinkedHashMap::new, Collectors.toList()));

        // 统计每个节点的访问次数：允许受控环路，但不允许无限循环。
        Map<String, Integer> visits = new LinkedHashMap<>();
        // 累积各节点实际调用 RAG 产生的证据，最终用于回答引用校验。
        List<RagContextEvidence> evidence = new ArrayList<>();
        // currentNodeId 表示下一步要执行的节点，从本次计划的起始节点开始。
        String currentNodeId = plan.getRootNodeId();
        // 保留上一节点输出，既是下一节点的上游上下文，也是进入 END 后的最终回答。
        String previousOutput = "";
        // 只统计已正常完成的节点步数，不是当前节点的重试次数。
        int steps = 0;
        try {
            // 每次循环只执行一个节点，再由服务端确定下一个节点。
            while (!"END".equalsIgnoreCase(currentNodeId)) {
                // 每个节点开始前都读取数据库运行状态。取消、失败或结束后不得继续调用模型和工具。
                runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);

                // 路由指向不存在节点时，说明本次运行配置或数据已经不一致，不能继续猜测执行。
                WorkflowDagPlanEntity.Node node = nodes.get(currentNodeId);
                if (node == null) throw new AppException("WORKFLOW_NODE_NOT_FOUND", "智能工作流节点不存在: " + currentNodeId);

                // merge 首次返回 1，后续递增。executionIndex 同时用于审计和前端展示。
                int executionIndex = visits.merge(currentNodeId, 1, Integer::sum);
                // 单节点访问次数门禁，防止 A -> B -> A 类环路无限运行。
                if (executionIndex > safeMaxVisits(node.getMaxVisits())) {
                    throw new AppException("WORKFLOW_NODE_VISIT_LIMIT", "节点访问次数超过上限: " + currentNodeId);
                }

                // 新协议通过 select_workflow_route 保存下一步建议；旧协议从模型正文末尾读取路径标记。
                boolean toolV2 = "TOOL_V2".equalsIgnoreCase(plan.getRoutingProtocolVersion());

                // 将用户问题、上游输出、当前合法路由、访问轮次和 RAG 指导合成本节点提示词。
                String prompt = intelligentPrompt(userMessage, previousOutput, node.getRouteInstruction(),
                        routeProtocol(outgoing.getOrDefault(currentNodeId, List.of()), nodes), executionIndex, toolV2,
                        ragGuidance(run, node));

                // 一个节点访问可以包含多次 Agent 尝试。每次尝试都有独立执行 ID、调用账本和审计记录。
                NodeInvocationOutcome outcome = invokeNodeWithRetry(runtime, plan, run, node, currentNodeId,
                        executionIndex, sessionId, prompt, roleCode, historyCutoffSequence, previousOutput);
                String nodeExecutionId = outcome.nodeExecutionId();
                long nodeStartedNanos = outcome.startedNanos();
                WorkflowNodeExecutionEntity executionAudit = outcome.executionAudit();
                WorkflowNodeInvocationResultEntity result = outcome.result();
                if (outcome.failure() != null) {
                    RuntimeException invocationError = outcome.failure();
                    // 次数用尽或错误不适合重试后，仍由服务端在当前节点合法出边中选择失败处理路径。
                    IntelligentWorkflowRouter.RouteDecision failure = router.decide(
                            new IntelligentWorkflowRouter.RouteContext(false, true, "", null, null,
                                    steps, plan.getMaxSteps(), 0L, plan.getTokenBudget()), node,
                            outgoing.getOrDefault(currentNodeId, List.of()));
                    final String failedNodeId = currentNodeId;
                    final int failedSteps = steps;
                    // 路径选择和当前节点在同一事务中更新，避免只成功保存其中一项。
                    inTransition(() -> {
                        persistDecision(run, runtime, nodeExecutionId, failedNodeId, nodes, failure,
                                null, "RUNTIME_FAILURE");
                        updateState(run, failure.targetNodeId(), "RUNNING", failedSteps, 0L, null);
                    });
                    currentNodeId = failure.targetNodeId();
                    // 失败路径直接指向 END 时，运行必须以失败结束，不能被后面的正常 END 收尾改成成功。
                    if ("END".equalsIgnoreCase(currentNodeId)) throw invocationError;
                    continue;
                }

                // 节点成功后收集文本输出和本次真实注入过的 RAG 证据。
                String output = safe(result.getOutput());
                if (result.getEvidence() != null) evidence.addAll(result.getEvidence());
                // 节点输出已由 ChatService 在模型分片到达时实时写入 NODE_OUTPUT_DELTA；
                // 这里不再补发整段内容，避免真流式与节点收口各追加一次。
                // 重新从用量账本汇总 Run 级 Token，不信任模型返回的估算值。
                long usedTokens = modelUsageService.summarizeSession(run.getTenantId(), run.getUserId(), sessionId,
                        run.getRunId()).getTotalTokens();
                // 节点正常返回才计入已执行步数。
                steps++;

                // terminal 节点自身就是业务终点，不要求调用路由工具，直接进入 END。
                if (Boolean.TRUE.equals(node.getTerminal())) {
                    completeExecution(run, runtime, executionAudit, node, nodeExecutionId, currentNodeId,
                            executionIndex, result, output, usedTokens, nodeStartedNanos);
                    previousOutput = output;
                    currentNodeId = "END";
                    updateState(run, currentNodeId, "RUNNING", steps, usedTokens, null);
                    continue;
                }

                // 新协议的路由工具只保存建议，Agent 返回后再按 runId + nodeExecutionId 查询。
                // 旧协议没有独立建议记录，稍后从输出正文解析路径标记。
                WorkflowRouteIntentEntity intent = toolV2 ? routeIntentRepository.queryByNode(
                        run.getTenantId(), run.getRunId(), nodeExecutionId) : null;
                // 保留路由信号来源，用于后续审计和协议迁移分析。
                String source = toolV2 ? "ROUTE_TOOL" : "MARKER_V1";

                // 新协议的非终点节点没有提交建议时，只追加一次专门的路径选择请求。
                // 这不是重新执行整个节点，也不会无限循环，只让 Agent 补调一次路由工具。
                if (toolV2 && intent == null && !Boolean.TRUE.equals(node.getTerminal())) {
                    publish(run, "ROUTE_REPAIR_STARTED", nodeExecutionId, currentNodeId, Map.of("attempt", 1));
                    runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);
                    try {
                        // routeRepairOnly=true 会收窄本次 Agent 能力面，要求它根据原输出选择合法路由。
                        chatService.invokeCompiledWorkflowNode(node, plan, run, nodeExecutionId, true, sessionId,
                                runtime.getWorkflowId(), repairPrompt(output, node), run.getTraceId(), roleCode,
                                historyCutoffSequence, previousOutput);
                    } catch (RuntimeException repairError) {
                        // 修复期间取消仍直接中断，不得走 FAILURE。
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
                        // 首次节点本身已成功，失败的是路由修复：因此在同一事务中
                        // 完成节点审计、保存 FAILURE 决策，并推进到失败出边目标。
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

                    // 补选请求正常返回也不代表一定调用了工具，必须重新查询建议记录。
                    intent = routeIntentRepository.queryByNode(run.getTenantId(), run.getRunId(), nodeExecutionId);
                    publish(run, "ROUTE_REPAIR_COMPLETED", nodeExecutionId, currentNodeId,
                            intent == null ? Map.of("success", false) : Map.of("success", true,
                                    "routeKey", intent.getRouteKey(), "functionCallId", intent.getFunctionCallId()));
                    source = "ROUTE_REPAIR";
                }

                // 新协议从数据库建议记录取路径键；旧协议从模型正文中解析。
                // 补选后仍没有建议时 routeKey 为空，通常由服务端选择默认出口。
                String routeKey = toolV2 ? intent == null ? null : intent.getRouteKey() : routeMarker(output);

                // 无论 Agent 是否调用路由工具，服务端都必须最终确定下一节点。
                // 这里是固定 Java 规则，不会再次调用模型；它负责检查预算、路径优先级、
                // 当前节点真实连线和目标节点是否合法。
                IntelligentWorkflowRouter.RouteDecision decision = router.decide(
                        new IntelligentWorkflowRouter.RouteContext(false, false, output, routeKey, routeKey,
                                steps, plan.getMaxSteps(), usedTokens, plan.getTokenBudget()),
                        node, outgoing.getOrDefault(currentNodeId, List.of()));
                // 新协议没有建议记录时只允许默认出口，防止模型通过正文绕过路由工具。
                if (toolV2 && intent == null && !"DEFAULT".equals(decision.strategy())) {
                    throw new AppException("WORKFLOW_ROUTE_REQUIRED", "智能节点必须选择合法路由");
                }
                // 预算耗尽不伪装成正常 END，而是产生可观测的稳定业务错误。
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

                // 检查并使用建议、保存路径选择、结束当前节点和更新下一节点必须在同一事务中完成。
                // 否则可能出现“建议已用但还停在旧节点”或“路径已保存但节点仍显示执行中”。
                inTransition(() -> {
                    if (transitionIntent != null) {
                        // 建议必须属于本次工作流配置，工具解析出的 edgeId 必须与服务端选择一致，
                        // 并且“等待使用 -> 已使用”只能成功更新一次。
                        if (!same(transitionIntent.getDefinitionHash(), plan.getDefinitionHash())
                                || !same(transitionIntent.getWorkflowId(), plan.getWorkflowId())
                                || !same(transitionIntent.getResolvedEdgeId(), decision.edgeId())
                                || routeIntentRepository.consume(run.getTenantId(), run.getRunId(), nodeExecutionId,
                                LocalDateTime.now()) != 1) {
                            throw new AppException("WORKFLOW_ROUTE_INTENT_INVALID", "下一步建议与本次工作流配置不一致或已经使用");
                        }
                    }
                    persistDecision(run, runtime, nodeExecutionId, transitionNodeId, nodes, decision, transitionIntent, transitionSource);
                    completeExecution(run, runtime, executionAudit, node, nodeExecutionId, transitionNodeId,
                            executionIndex, result, transitionOutput, usedTokens, nodeStartedNanos);
                    updateState(run, decision.targetNodeId(), "RUNNING", transitionSteps, usedTokens, null);
                });
                // 事务成功后再推进内存指针，下一轮将以当前输出作为上游上下文。
                previousOutput = output;
                currentNodeId = decision.targetNodeId();
            }

            // 跳出循环只表示指针已到 END。收尾前再检查一次取消，
            // 避免“刚到 END 就被取消”时仍错误写入 COMPLETED。
            runControlService.requireExecutable(run.getTenantId(), run.getUserId(), run.getRunId(), null);
            // 直达 END 的末端节点已随模型分片发布 FINAL_ANSWER_DELTA。
            // 收口只发完整快照做校准，避免再追加一遍整段回答。
            // 保存 assistant 消息并使用累积 evidence 校验回答中的 RAG 引用。
            chatService.completeCompiledWorkflowRun(run, previousOutput, run.getTraceId(), evidence);
            // 先完成最终回答，再发工作流终态，前端 reducer 按此顺序收口。
            publish(run, "FINAL_ANSWER_COMPLETED", null, null, Map.of("content", previousOutput));
            publish(run, "WORKFLOW_COMPLETED", null, null, Map.of("executedSteps", steps));
            updateState(run, "END", "COMPLETED", steps,
                    modelUsageService.summarizeSession(run.getTenantId(), run.getUserId(), sessionId, run.getRunId()).getTotalTokens(),
                    LocalDateTime.now());
        } catch (Throwable exception) {
            // 失败与取消必须分开收尾：取消不是技术失败，不写 assistant_error，也不发 WORKFLOW_FAILED。
            if (!runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId())) {
                // 非取消异常写入失败助手消息，持久化失败事件并把运行快照置为 FAILED。
                runControlService.failWithAssistantMessage(run.getTenantId(), run.getUserId(), run.getRunId(),
                        "[assistant_error] " + safe(exception.getMessage()), run.getTraceId(), safe(exception.getMessage()));
                publish(run, "WORKFLOW_FAILED", null, currentNodeId,
                        Map.of("errorCode", exception instanceof AppException app ? app.getCode() : "WORKFLOW_EXECUTION_FAILED",
                                "message", safe(exception.getMessage()), "executedSteps", steps));
                updateState(run, currentNodeId, "FAILED", steps, 0L, LocalDateTime.now());
            } else {
                // 专用取消收尾会修正执行中的节点审计并补齐取消事件。
                reconcileCancellation(run);
            }
            // 业务异常已完成运行层收尾；JVM Error 不应被业务代码吞掉，因此继续抛出。
            if (exception instanceof Error error) throw error;
        }
    }

    /**
     * 执行一个 Agent 节点，并把每次尝试分别写入审计账本。
     *
     * <p>只有网络超时、上游暂时不可用或通用未知错误会再次尝试；参数、权限、重复调用等
     * 确定性错误不会通过重复请求掩盖。最终失败作为返回值交给主循环选择合法失败路径。</p>
     */
    private NodeInvocationOutcome invokeNodeWithRetry(WorkflowRuntimeEntity runtime, WorkflowDagPlanEntity plan,
                                                       ChatRunEntity run, WorkflowDagPlanEntity.Node node,
                                                       String nodeId, int executionIndex, String sessionId,
                                                       String prompt, String roleCode, Integer historyCutoffSequence,
                                                       String previousOutput) {
        int maxAttempts = nodeRetryPolicy.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String nodeExecutionId = "wne_" + java.util.UUID.randomUUID();
            long startedNanos = System.nanoTime();
            WorkflowNodeExecutionEntity audit = WorkflowNodeExecutionEntity.builder()
                    .tenantId(run.getTenantId()).runId(run.getRunId()).nodeExecutionId(nodeExecutionId)
                    .nodeId(nodeId).executionIndex(executionIndex).attempt(attempt).status("RUNNING")
                    .traceId(run.getTraceId()).startedAt(LocalDateTime.now()).build();
            executionAuditRepository.startNode(audit);
            AiLog.info(AiLog.workflow().nodeStarted(run.getTenantId(), run.getUserId(), sessionId, run.getRunId(),
                    runtime.getWorkflowId(), nodeId, executionIndex, safeMaxVisits(node.getMaxVisits()), attempt));
            publish(run, "NODE_STARTED", nodeExecutionId, nodeId, Map.of(
                    "nodeName", safe(node.getNodeName()), "executionIndex", executionIndex,
                    "attempt", attempt, "maxAttempts", maxAttempts));

            WorkflowInvocationEntity invocation = invocationGuardService.modelInvocation(run, nodeExecutionId, attempt);
            boolean registered = false;
            try {
                if (!invocationGuardService.register(invocation, run.getUserId())) {
                    throw new AppException("WORKFLOW_INVOCATION_DUPLICATE", "节点模型调用已登记，拒绝重复执行");
                }
                registered = true;
                WorkflowNodeInvocationResultEntity result = chatService.invokeCompiledWorkflowNode(node, plan, run,
                        nodeExecutionId, false, sessionId, runtime.getWorkflowId(), prompt, run.getTraceId(), roleCode,
                        historyCutoffSequence, previousOutput);
                invocationGuardService.success(run.getTenantId(), invocation.getInvocationId(), null);
                return new NodeInvocationOutcome(nodeExecutionId, startedNanos, audit, result, null);
            } catch (RuntimeException invocationError) {
                if (registered) invocationGuardService.failed(run.getTenantId(), invocation.getInvocationId(), null);
                boolean cancelled = runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId());
                audit.setStatus(cancelled ? "CANCELLED" : "FAILED");
                audit.setErrorCode(cancelled ? "RUN_CANCELLED" : errorCode(invocationError));
                audit.setErrorMessage(safe(invocationError.getMessage()));
                audit.setFinishedAt(LocalDateTime.now());
                executionAuditRepository.completeNode(audit);

                if (cancelled) {
                    AiLog.info(AiLog.workflow().nodeCancelled(run.getTenantId(), run.getUserId(), sessionId,
                            run.getRunId(), runtime.getWorkflowId(), nodeId, executionIndex,
                            safeMaxVisits(node.getMaxVisits()), elapsedMs(startedNanos))
                            .field(AiLogFields.TRACE_ID, run.getTraceId()));
                    throw invocationError;
                }

                boolean retry = attempt < maxAttempts && retryableInvocationFailure(invocationError);
                AiLog.error(AiLog.workflow().nodeFailed(run.getTenantId(), run.getUserId(), sessionId, run.getRunId(),
                        runtime.getWorkflowId(), nodeId, executionIndex, safeMaxVisits(node.getMaxVisits()),
                        elapsedMs(startedNanos), invocationError)
                        .field("attempt", attempt).field("maxAttempts", maxAttempts).field("willRetry", retry));
                if (!retry) {
                    publish(run, "NODE_FAILED", nodeExecutionId, nodeId, Map.of(
                            "nodeName", safe(node.getNodeName()), "errorCode", errorCode(invocationError),
                            "message", safe(invocationError.getMessage()), "executionIndex", executionIndex,
                            "attempt", attempt, "maxAttempts", maxAttempts, "retryExhausted", attempt >= maxAttempts));
                    return new NodeInvocationOutcome(nodeExecutionId, startedNanos, audit, null, invocationError);
                }

                long waitMillis = nodeRetryPolicy.backoffMillis(attempt);
                publish(run, "NODE_RETRY_SCHEDULED", nodeExecutionId, nodeId, Map.of(
                        "executionIndex", executionIndex, "failedAttempt", attempt,
                        "nextAttempt", attempt + 1, "waitMillis", waitMillis,
                        "errorCode", errorCode(invocationError)));
                nodeRetryPolicy.awaitNextAttempt(attempt, () -> runControlService.requireExecutable(
                        run.getTenantId(), run.getUserId(), run.getRunId(), null));
            }
        }
        throw new IllegalStateException("Agent 节点尝试循环异常结束");
    }

    /** 只重试可能随时间恢复的错误，不重试参数、权限、取消和重复调用错误。 */
    private boolean retryableInvocationFailure(RuntimeException exception) {
        if (exception instanceof AppException app) {
            String code = safe(app.getCode()).toUpperCase(Locale.ROOT);
            return ResponseCode.UN_ERROR.getCode().equals(code)
                    || code.contains("TIMEOUT") || code.contains("TEMPORARY")
                    || code.contains("UNAVAILABLE") || code.contains("RATE_LIMIT");
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.util.concurrent.RejectedExecutionException) return true;
        }
        return false;
    }

    /**
     * 事务提交后再提交后台任务；线程池过载或关闭时，立即把两套运行状态和失败事件一起收口。
     */
    private void submitExecution(ChatRunEntity run, Runnable execution) {
        try {
            coordinatorExecutor.submit(execution);
        } catch (RuntimeException submissionError) {
            inTransition(() -> {
                String message = "工作流后台执行任务提交失败";
                runControlService.failWithAssistantMessage(run.getTenantId(), run.getUserId(), run.getRunId(),
                        "[assistant_error] " + message, run.getTraceId(), message);
                updateState(run, null, "FAILED", 0, 0L, LocalDateTime.now());
                publish(run, "WORKFLOW_FAILED", null, null, Map.of(
                        "errorCode", "WORKFLOW_EXECUTOR_REJECTED", "message", message, "executedSteps", 0));
            });
            AiLog.error(AiLog.workflow().runFailed(run.getTenantId(), run.getUserId(), run.getSourceId(), submissionError)
                    .field(AiLogFields.TRACE_ID, run.getTraceId()).field("runId", run.getRunId())
                    .field("errorCode", "WORKFLOW_EXECUTOR_REJECTED"));
        }
    }

    /** 一次节点尝试的完整结果；失败也保留对应执行 ID 和审计记录，供服务端保存路径决定。 */
    private record NodeInvocationOutcome(String nodeExecutionId, long startedNanos,
                                         WorkflowNodeExecutionEntity executionAudit,
                                         WorkflowNodeInvocationResultEntity result,
                                         RuntimeException failure) {
    }

    /** 取消请求成功后同步收口智能扩展状态；返回是否属于智能工作流。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean reconcileCancellation(ChatRunEntity run) {
        LocalDateTime finishedAt = LocalDateTime.now();
        // 取消与后台节点推进会竞争 revision；以非终态条件更新作为唯一线性化点，避免旧 revision 误报失败。
        if (intelligentRunRepository.cancelActive(run.getTenantId(), run.getUserId(), run.getRunId(), finishedAt) == 0) {
            // 已被后台收口的智能运行仍属于专用链路，不能再交给通用收口器重复发终态事件。
            return intelligentRunRepository.query(run.getTenantId(), run.getUserId(), run.getRunId()) != null;
        }
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
        return true;
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

    /** 保存服务端最终确定的路径，并发布包含目标名称和建议来源的 ROUTE_DECIDED 事件。 */
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

    /** 组装当前节点输入、上游输出和本次运行允许的路径，不暴露其他目标节点。 */
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

    /** 从本次运行保存的出边生成精确路径说明，避免提示词与工作流配置不一致。 */
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
