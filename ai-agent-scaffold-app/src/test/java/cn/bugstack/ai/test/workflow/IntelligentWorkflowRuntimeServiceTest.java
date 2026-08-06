package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowExecutionAuditRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRouteIntentRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeInvocationResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.service.IWorkflowService;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRouter;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRuntimeService;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.domain.workflow.service.WorkflowInvocationGuardService;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 智能工作流从根节点到显式 END 的领域闭环测试。 */
public class IntelligentWorkflowRuntimeServiceTest {

    @Test
    public void shouldExecuteNodeRouteToEndAndCompleteWithSameRootTrace() {
        IWorkflowService workflowService = mock(IWorkflowService.class);
        IChatService chatService = mock(IChatService.class);
        RunControlService runControl = mock(RunControlService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        ModelUsageService usage = mock(ModelUsageService.class);
        WorkflowInvocationGuardService guard = mock(WorkflowInvocationGuardService.class);
        IWorkflowExecutionAuditRepository audit = mock(IWorkflowExecutionAuditRepository.class);
        IWorkflowRouteIntentRepository routeIntents = mock(IWorkflowRouteIntentRepository.class);
        InMemoryRunRepository runs = new InMemoryRunRepository();
        WorkflowRuntimeEntity runtime = runtime();
        ChatRunEntity run = ChatRunEntity.builder().tenantId("tenant_1").userId("user_1").sessionId("session_1")
                .runId("run_1").traceId("trace_root_1234").status(RunStatus.RUNNING).version(0)
                .currentContextRevision(0L).build();
        when(workflowService.loadRuntime(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(runtime);
        when(chatService.createWorkflowSession(anyString(), any(), any(), anyString())).thenReturn("session_1");
        when(runControl.startOrResume("tenant_1", "user_1", "session_1", "workflow", "wf_1", null)).thenReturn(run);
        when(runControl.appendUserMessage(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("请审核"),
                eq("trace_root_1234"), any())).thenReturn(RunMessageBindingEntity.builder().run(run)
                .message(ChatMessageEntity.builder().sequenceNo(7).build()).build());
        when(chatService.invokeCompiledWorkflowNode(any(), any(), eq(run), anyString(), eq(false), eq("session_1"), eq("wf_1"), anyString(),
                eq("trace_root_1234"), eq("member"), eq(7), anyString()))
                .thenReturn(WorkflowNodeInvocationResultEntity.builder().output("审核通过").evidence(List.of()).build());
        when(usage.summarizeSession("tenant_1", "user_1", "session_1", "run_1"))
                .thenReturn(ModelUsageSummaryEntity.builder().totalTokens(42L).build());
        WorkflowInvocationEntity invocation = WorkflowInvocationEntity.builder().tenantId("tenant_1").runId("run_1")
                .invocationId("invocation_1").traceId("trace_root_1234").build();
        when(guard.modelInvocation(eq(run), anyString())).thenReturn(invocation);
        when(guard.register(eq(invocation), eq("user_1"))).thenReturn(true);

        IntelligentWorkflowRuntimeService service = new IntelligentWorkflowRuntimeService(workflowService, chatService,
                runControl, runs, events, new IntelligentWorkflowRouter(), usage, guard, audit, routeIntents,
                new DirectExecutorService(), new ObjectMapper());

        IntelligentWorkflowRunEntity result = service.start(IntelligentWorkflowStartCommandEntity.builder()
                .tenantId("tenant_1").userId("user_1").roleCode("member").workflowId("wf_1")
                .message("请审核").attachmentIds(List.of()).build());

        Assert.assertEquals("trace_root_1234", result.getTraceId());
        Assert.assertEquals("COMPLETED", runs.value.get().getStatus());
        Assert.assertEquals("END", runs.value.get().getCurrentNodeId());
        Assert.assertEquals(Long.valueOf(42), runs.value.get().getUsedTokens());
        verify(chatService).completeCompiledWorkflowRun(eq(run), eq("审核通过"), eq("trace_root_1234"), any());
        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root_1234"),
                eq("WORKFLOW_COMPLETED"), any(), any(), anyString());
        verify(audit).startNode(any());
        verify(audit).completeNode(any());
        verify(audit).decideRoute(any());
    }

    @Test
    public void shouldSynchronouslyReconcileCancelledRunAndRunningNode() {
        IWorkflowService workflowService = mock(IWorkflowService.class);
        IChatService chatService = mock(IChatService.class);
        RunControlService runControl = mock(RunControlService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        ModelUsageService usage = mock(ModelUsageService.class);
        WorkflowInvocationGuardService guard = mock(WorkflowInvocationGuardService.class);
        IWorkflowExecutionAuditRepository audit = mock(IWorkflowExecutionAuditRepository.class);
        IWorkflowRouteIntentRepository routeIntents = mock(IWorkflowRouteIntentRepository.class);
        InMemoryRunRepository runs = new InMemoryRunRepository();
        runs.insert(IntelligentWorkflowRunEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .workflowId("wf_1").workflowVersion(1).traceId("trace_root_1234").status("RUNNING")
                .currentNodeId("review").executedSteps(0).usedTokens(0L).revision(0L).build());
        when(audit.cancelRunningNodes(eq("tenant_1"), eq("run_1"), any())).thenReturn(1);
        IntelligentWorkflowRuntimeService service = new IntelligentWorkflowRuntimeService(workflowService, chatService,
                runControl, runs, events, new IntelligentWorkflowRouter(), usage, guard, audit, routeIntents,
                new DirectExecutorService(), new ObjectMapper());
        ChatRunEntity run = ChatRunEntity.builder().tenantId("tenant_1").userId("user_1").sessionId("session_1")
                .runId("run_1").traceId("trace_root_1234").status(RunStatus.CANCELLED).build();

        service.reconcileCancellation(run);
        service.reconcileCancellation(run);

        Assert.assertEquals("CANCELLED", runs.value.get().getStatus());
        verify(audit).cancelRunningNodes(eq("tenant_1"), eq("run_1"), any());
        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root_1234"),
                eq("WORKFLOW_CANCELLED"), isNull(), eq("review"), anyString());
    }

    private WorkflowRuntimeEntity runtime() {
        WorkflowDagPlanEntity.Node node = WorkflowDagPlanEntity.Node.builder().nodeId("review").nodeName("审核")
                .runtimeAgentId("agent_review").maxVisits(2).enabledStrategies(List.of("DEFAULT"))
                .allowedTargetNodeIds(List.of("END")).build();
        WorkflowDagPlanEntity.Edge edge = WorkflowDagPlanEntity.Edge.builder().edgeId("edge_end")
                .sourceNodeId("review").targetNodeId("END").routeType("DEFAULT").priority(1).build();
        WorkflowDagPlanEntity plan = WorkflowDagPlanEntity.builder().workflowKind("INTELLIGENT").workflowId("wf_1")
                .version(1).rootNodeId("review").maxSteps(10).tokenBudget(1000L)
                .nodes(List.of(node)).edges(List.of(edge)).build();
        return WorkflowRuntimeEntity.builder().workflowId("wf_1").version(1).effectiveModelCode("model")
                .dagPlan(plan).build();
    }

    private static final class InMemoryRunRepository implements IIntelligentWorkflowRunRepository {
        private final AtomicReference<IntelligentWorkflowRunEntity> value = new AtomicReference<>();
        @Override public int insert(IntelligentWorkflowRunEntity run) { value.set(run); return 1; }
        @Override public IntelligentWorkflowRunEntity query(String tenantId, String userId, String runId) { return value.get(); }
        @Override public int updateState(IntelligentWorkflowRunEntity run, long expectedRevision) {
            if (run.getRevision() != expectedRevision) return 0;
            run.setRevision(expectedRevision + 1); value.set(run); return 1;
        }
        @Override public int cancelActive(String tenantId, String userId, String runId, java.time.LocalDateTime finishedAt) {
            IntelligentWorkflowRunEntity current = value.get();
            if (current == null || "CANCELLED".equals(current.getStatus()) || "COMPLETED".equals(current.getStatus())
                    || "FAILED".equals(current.getStatus())) return 0;
            current.setStatus("CANCELLED"); current.setFinishedAt(finishedAt);
            current.setRevision(current.getRevision() + 1); value.set(current); return 1;
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }
}
