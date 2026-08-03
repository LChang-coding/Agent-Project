package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeInvocationResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 单活动路径智能工作流运行时；节点执行、路由裁决和最终回答均由服务端推进。 */
@Service
public class IntelligentWorkflowRuntimeService {

    private static final Pattern ROUTE_MARKER = Pattern.compile("(?im)^\\s*\\[route:([A-Za-z0-9_.-]{1,64})]\\s*$");

    private final IWorkflowService workflowService;
    private final IChatService chatService;
    private final RunControlService runControlService;
    private final IIntelligentWorkflowRunRepository intelligentRunRepository;
    private final WorkflowEventStreamService eventStreamService;
    private final IntelligentWorkflowRouter router;
    private final ModelUsageService modelUsageService;
    private final WorkflowInvocationGuardService invocationGuardService;
    private final ExecutorService coordinatorExecutor;
    private final ObjectMapper objectMapper;

    public IntelligentWorkflowRuntimeService(IWorkflowService workflowService,
                                             IChatService chatService,
                                             RunControlService runControlService,
                                             IIntelligentWorkflowRunRepository intelligentRunRepository,
                                             WorkflowEventStreamService eventStreamService,
                                             IntelligentWorkflowRouter router,
                                             ModelUsageService modelUsageService,
                                             WorkflowInvocationGuardService invocationGuardService,
                                             @Qualifier("workflowCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                             ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.chatService = chatService;
        this.runControlService = runControlService;
        this.intelligentRunRepository = intelligentRunRepository;
        this.eventStreamService = eventStreamService;
        this.router = router;
        this.modelUsageService = modelUsageService;
        this.invocationGuardService = invocationGuardService;
        this.coordinatorExecutor = coordinatorExecutor;
        this.objectMapper = objectMapper;
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
                .definitionHash(hash(plan)).traceId(activeRun.getTraceId()).status("RUNNING")
                .currentNodeId(plan.getRootNodeId()).nextSequence(1L).executedSteps(0).usedTokens(0L)
                .maxSteps(plan.getMaxSteps()).tokenBudget(plan.getTokenBudget()).variablesJson("{}")
                .revision(0L).startedAt(LocalDateTime.now()).build();
        if (intelligentRunRepository.insert(intelligentRun) != 1) {
            throw new AppException("WORKFLOW_RUN_CREATE_FAILED", "智能工作流运行状态创建失败");
        }
        publish(activeRun, "WORKFLOW_STARTED", null, null, Map.of(
                "workflowId", command.getWorkflowId(), "workflowVersion", runtime.getVersion(),
                "rootNodeId", plan.getRootNodeId(), "definitionHash", intelligentRun.getDefinitionHash()));

        Runnable execution = TraceContext.wrap(() -> execute(runtime, plan, activeRun, sessionId, command.getMessage(),
                command.getRoleCode(), binding.getMessage().getSequenceNo()));
        afterCommit(() -> coordinatorExecutor.submit(execution));
        return intelligentRun;
    }

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
                publish(run, "NODE_STARTED", nodeExecutionId, currentNodeId,
                        Map.of("nodeName", safe(node.getNodeName()), "executionIndex", executionIndex, "attempt", 1));
                String prompt = intelligentPrompt(userMessage, previousOutput, node.getRouteInstruction(), executionIndex);
                WorkflowInvocationEntity invocation = invocationGuardService.modelInvocation(run, nodeExecutionId);
                if (!invocationGuardService.register(invocation, run.getUserId())) {
                    throw new AppException("WORKFLOW_INVOCATION_DUPLICATE", "节点模型调用已登记，拒绝重复执行");
                }
                WorkflowNodeInvocationResultEntity result;
                try {
                    result = chatService.invokeCompiledWorkflowNode(node, run, sessionId,
                            runtime.getWorkflowId(), prompt, run.getTraceId(), roleCode, historyCutoffSequence, previousOutput);
                    invocationGuardService.success(run.getTenantId(), invocation.getInvocationId(), null);
                } catch (RuntimeException invocationError) {
                    invocationGuardService.failed(run.getTenantId(), invocation.getInvocationId(), null);
                    throw invocationError;
                }
                String output = safe(result.getOutput());
                if (result.getEvidence() != null) evidence.addAll(result.getEvidence());
                if (!output.isEmpty()) publish(run, "NODE_OUTPUT_DELTA", nodeExecutionId, currentNodeId, Map.of("delta", output));
                long usedTokens = modelUsageService.summarizeSession(run.getTenantId(), run.getUserId(), sessionId,
                        run.getRunId()).getTotalTokens();
                publish(run, "NODE_COMPLETED", nodeExecutionId, currentNodeId,
                        Map.of("displayOutput", output, "executionIndex", executionIndex, "totalTokens", usedTokens));
                steps++;
                String suggestion = routeMarker(output);
                IntelligentWorkflowRouter.RouteDecision decision = router.decide(
                        new IntelligentWorkflowRouter.RouteContext(false, false, output, suggestion, suggestion,
                                steps, plan.getMaxSteps(), usedTokens, plan.getTokenBudget()),
                        node, outgoing.getOrDefault(currentNodeId, List.of()));
                publish(run, "ROUTE_DECIDED", nodeExecutionId, currentNodeId,
                        Map.of("strategy", decision.strategy(), "targetNodeId", decision.targetNodeId(),
                                "terminal", decision.terminal(), "reason", decision.reason()));
                updateState(run, decision.targetNodeId(), "RUNNING", steps, usedTokens, null);
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
        } catch (RuntimeException exception) {
            if (!runControlService.cancelled(run.getTenantId(), run.getUserId(), run.getRunId())) {
                runControlService.failWithAssistantMessage(run.getTenantId(), run.getUserId(), run.getRunId(),
                        "[assistant_error] " + safe(exception.getMessage()), run.getTraceId(), safe(exception.getMessage()));
                publish(run, "WORKFLOW_FAILED", null, currentNodeId,
                        Map.of("errorCode", exception instanceof AppException app ? app.getCode() : "WORKFLOW_EXECUTION_FAILED",
                                "message", safe(exception.getMessage()), "executedSteps", steps));
                updateState(run, currentNodeId, "FAILED", steps, 0L, LocalDateTime.now());
            } else {
                publish(run, "WORKFLOW_CANCELLED", null, currentNodeId, Map.of("executedSteps", steps));
                updateState(run, currentNodeId, "CANCELLED", steps, 0L, LocalDateTime.now());
            }
        }
    }

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

    private void publish(ChatRunEntity run, String type, String executionId, String nodeId, Map<String, ?> payload) {
        eventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(), type,
                executionId, nodeId, json(payload));
    }

    private String intelligentPrompt(String userMessage, String previousOutput, String routeInstruction, int executionIndex) {
        return "用户本轮输入：\n" + safe(userMessage) + "\n\n上一节点输出：\n" + safe(previousOutput)
                + "\n\n当前执行次数：" + executionIndex
                + "\n\n路由要求：\n" + safe(routeInstruction)
                + "\n如需建议路由，只能在正文末尾单独输出 [route:路由键]；请完成当前节点任务，不要直接调用其他节点。";
    }

    private String routeMarker(String output) {
        Matcher matcher = ROUTE_MARKER.matcher(safe(output));
        String result = null;
        while (matcher.find()) result = matcher.group(1);
        return result;
    }

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

    private String json(Map<String, ?> payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException exception) { throw new AppException("WORKFLOW_EVENT_INVALID", "工作流事件编码失败"); }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run(); return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private void validate(IntelligentWorkflowStartCommandEntity command) {
        if (command == null || blank(command.getTenantId()) || blank(command.getUserId())
                || blank(command.getWorkflowId()) || blank(command.getMessage())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户、工作流和消息不能为空");
        }
    }

    private int safeMaxVisits(Integer value) { return value == null ? 3 : Math.max(1, Math.min(value, 50)); }
    private <T> List<T> safeList(List<T> value) { return value == null ? List.of() : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
}
