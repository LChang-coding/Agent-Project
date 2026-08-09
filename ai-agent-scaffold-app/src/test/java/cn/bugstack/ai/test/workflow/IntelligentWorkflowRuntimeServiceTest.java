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
import cn.bugstack.ai.domain.workflow.service.WorkflowNodeRetryPolicy;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        when(guard.modelInvocation(eq(run), anyString(), anyInt())).thenReturn(invocation);
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

    @Test
    public void shouldRetryTransientAgentFailureWithIncreasingWaitAndComplete() {
        List<Long> waits = new ArrayList<>();
        WorkflowNodeRetryPolicy retryPolicy = new WorkflowNodeRetryPolicy(3, 10L, 100L, 100L, waits::add);
        RuntimeFixture fixture = new RuntimeFixture(new DirectExecutorService(), retryPolicy);
        when(fixture.chatService.invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(), eq(false),
                eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"), eq(7), anyString()))
                .thenThrow(new AppException("0001", "模型服务暂时不可用"))
                .thenThrow(new AppException("MODEL_TIMEOUT", "模型请求超时"))
                .thenReturn(WorkflowNodeInvocationResultEntity.builder().output("第三次成功").evidence(List.of()).build());

        fixture.start();

        Assert.assertEquals(List.of(10L, 20L), waits);
        Assert.assertEquals("COMPLETED", fixture.runs.value.get().getStatus());
        verify(fixture.chatService, times(3)).invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(),
                eq(false), eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"),
                eq(7), anyString());
        verify(fixture.audit, times(3)).startNode(any());
        verify(fixture.audit, times(3)).completeNode(any());
        verify(fixture.events, times(2)).publish(eq("tenant_1"), eq("user_1"), eq("run_1"),
                eq("trace_root_1234"), eq("NODE_RETRY_SCHEDULED"), anyString(), eq("review"), anyString());
    }

    @Test
    public void shouldNotRetryDeterministicAgentFailureAndShouldEndAsFailed() {
        List<Long> waits = new ArrayList<>();
        RuntimeFixture fixture = new RuntimeFixture(new DirectExecutorService(),
                new WorkflowNodeRetryPolicy(3, 10L, 100L, 100L, waits::add));
        when(fixture.chatService.invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(), eq(false),
                eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"), eq(7), anyString()))
                .thenThrow(new AppException("0002", "节点参数不合法"));

        fixture.start();

        Assert.assertTrue(waits.isEmpty());
        Assert.assertEquals("FAILED", fixture.runs.value.get().getStatus());
        Assert.assertEquals("END", fixture.runs.value.get().getCurrentNodeId());
        verify(fixture.chatService, times(1)).invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(),
                eq(false), eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"),
                eq(7), anyString());
        verify(fixture.runControl).failWithAssistantMessage(eq("tenant_1"), eq("user_1"), eq("run_1"),
                anyString(), eq("trace_root_1234"), eq("节点参数不合法"));
    }

    @Test
    public void shouldStopRetryWhenRunIsCancelledDuringWait() {
        List<Long> waits = new ArrayList<>();
        RuntimeFixture fixture = new RuntimeFixture(new DirectExecutorService(),
                new WorkflowNodeRetryPolicy(3, 10L, 100L, 100L, waits::add));
        when(fixture.chatService.invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(), eq(false),
                eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"), eq(7), anyString()))
                .thenThrow(new AppException("MODEL_TIMEOUT", "模型请求超时"));
        when(fixture.runControl.cancelled("tenant_1", "user_1", "run_1")).thenReturn(false, true);
        when(fixture.runControl.requireExecutable("tenant_1", "user_1", "run_1", null))
                .thenReturn(fixture.run, fixture.run)
                .thenThrow(new AppException("RUN_NOT_EXECUTABLE", "运行已取消"));

        fixture.start();

        Assert.assertEquals(List.of(10L), waits);
        Assert.assertEquals("CANCELLED", fixture.runs.value.get().getStatus());
        verify(fixture.chatService, times(1)).invokeCompiledWorkflowNode(any(), any(), eq(fixture.run), anyString(),
                eq(false), eq("session_1"), eq("wf_1"), anyString(), eq("trace_root_1234"), eq("member"),
                eq(7), anyString());
        verify(fixture.events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root_1234"),
                eq("WORKFLOW_CANCELLED"), isNull(), eq("review"), anyString());
    }

    @Test
    public void shouldFailRunWhenCoordinatorRejectsExecution() {
        RuntimeFixture fixture = new RuntimeFixture(new RejectingExecutorService(),
                new WorkflowNodeRetryPolicy(1, 0L, 0L, 1L, millis -> { }));

        fixture.start();

        Assert.assertEquals("FAILED", fixture.runs.value.get().getStatus());
        verify(fixture.runControl).failWithAssistantMessage(eq("tenant_1"), eq("user_1"), eq("run_1"),
                eq("[assistant_error] 工作流后台执行任务提交失败"), eq("trace_root_1234"),
                eq("工作流后台执行任务提交失败"));
        verify(fixture.events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root_1234"),
                eq("WORKFLOW_FAILED"), isNull(), isNull(), anyString());
        verify(fixture.chatService, times(0)).invokeCompiledWorkflowNode(any(), any(), any(), anyString(),
                eq(false), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
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

    /** 为重试和拒绝测试集中准备同一套可信运行上下文。 */
    private final class RuntimeFixture {
        private final IWorkflowService workflowService = mock(IWorkflowService.class);
        private final IChatService chatService = mock(IChatService.class);
        private final RunControlService runControl = mock(RunControlService.class);
        private final WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        private final ModelUsageService usage = mock(ModelUsageService.class);
        private final WorkflowInvocationGuardService guard = mock(WorkflowInvocationGuardService.class);
        private final IWorkflowExecutionAuditRepository audit = mock(IWorkflowExecutionAuditRepository.class);
        private final IWorkflowRouteIntentRepository routeIntents = mock(IWorkflowRouteIntentRepository.class);
        private final InMemoryRunRepository runs = new InMemoryRunRepository();
        private final ChatRunEntity run = ChatRunEntity.builder().tenantId("tenant_1").userId("user_1")
                .sessionId("session_1").sourceId("wf_1").runId("run_1").traceId("trace_root_1234")
                .status(RunStatus.RUNNING).version(0).currentContextRevision(0L).build();
        private final IntelligentWorkflowRuntimeService service;

        private RuntimeFixture(java.util.concurrent.ExecutorService executor, WorkflowNodeRetryPolicy retryPolicy) {
            when(workflowService.loadRuntime(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(runtime());
            when(chatService.createWorkflowSession(anyString(), any(), any(), anyString())).thenReturn("session_1");
            when(runControl.startOrResume("tenant_1", "user_1", "session_1", "workflow", "wf_1", null))
                    .thenReturn(run);
            when(runControl.appendUserMessage(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("请审核"),
                    eq("trace_root_1234"), any())).thenReturn(RunMessageBindingEntity.builder().run(run)
                    .message(ChatMessageEntity.builder().sequenceNo(7).build()).build());
            when(usage.summarizeSession("tenant_1", "user_1", "session_1", "run_1"))
                    .thenReturn(ModelUsageSummaryEntity.builder().totalTokens(42L).build());
            when(guard.modelInvocation(eq(run), anyString(), anyInt())).thenAnswer(invocation ->
                    WorkflowInvocationEntity.builder().tenantId("tenant_1").runId("run_1")
                            .invocationId("invocation_" + java.util.UUID.randomUUID())
                            .traceId("trace_root_1234").build());
            when(guard.register(any(), eq("user_1"))).thenReturn(true);
            service = new IntelligentWorkflowRuntimeService(workflowService, chatService, runControl, runs, events,
                    new IntelligentWorkflowRouter(), usage, guard, audit, routeIntents, executor,
                    new ObjectMapper(), retryPolicy);
        }

        private IntelligentWorkflowRunEntity start() {
            return service.start(IntelligentWorkflowStartCommandEntity.builder().tenantId("tenant_1")
                    .userId("user_1").roleCode("member").workflowId("wf_1").message("请审核")
                    .attachmentIds(List.of()).build());
        }
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

    private static class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static final class RejectingExecutorService extends DirectExecutorService {
        @Override public void execute(Runnable command) {
            throw new RejectedExecutionException("测试线程池已满");
        }
    }
}
