package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.workflow.model.entity.StaticWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.service.IWorkflowService;
import cn.bugstack.ai.domain.workflow.service.StaticWorkflowRuntimeService;
import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 普通 DAG 启动必须校验类型，并让后台订阅独立于 HTTP 连接。 */
public class StaticWorkflowRuntimeServiceTest {

    @Test
    public void shouldStartPublishedStaticWorkflowInBackground() {
        IWorkflowService workflows = mock(IWorkflowService.class);
        IChatService chats = mock(IChatService.class);
        WorkflowRuntimeEntity runtime = runtime("STATIC");
        when(workflows.loadRuntime("tenant_1", "user_1", "admin", "wf_1", 2,
                "deepseek-v4-flash")).thenReturn(runtime);
        ChatRunEntity run = ChatRunEntity.builder().tenantId("tenant_1").userId("user_1")
                .sessionId("session_1").runId("run_1").sourceType("workflow").sourceId("wf_1")
                .traceId("trace_root").status(RunStatus.RUNNING).build();
        when(chats.startWorkflowMessageTextStream("wf_1", 2, "deepseek-v4-flash", "user_1",
                "session_1", "开始", "run_1", List.of("asset_1")))
                .thenReturn(RunStreamEntity.<String>builder().run(run).stream(Flowable.just("完成")).build());

        ChatRunEntity result = new StaticWorkflowRuntimeService(workflows, chats).start(command());

        Assert.assertEquals("run_1", result.getRunId());
        verify(chats).startWorkflowMessageTextStream("wf_1", 2, "deepseek-v4-flash", "user_1",
                "session_1", "开始", "run_1", List.of("asset_1"));
    }

    @Test
    public void shouldRejectIntelligentWorkflowAtStaticEndpoint() {
        IWorkflowService workflows = mock(IWorkflowService.class);
        IChatService chats = mock(IChatService.class);
        when(workflows.loadRuntime(any(), any(), any(), any(), any(), any())).thenReturn(runtime("INTELLIGENT"));

        try {
            new StaticWorkflowRuntimeService(workflows, chats).start(command());
            Assert.fail("应该拒绝智能工作流");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_NOT_STATIC", exception.getCode());
        }
        verify(chats, never()).startWorkflowMessageTextStream(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private StaticWorkflowStartCommandEntity command() {
        return StaticWorkflowStartCommandEntity.builder().tenantId("tenant_1").userId("user_1").roleCode("admin")
                .workflowId("wf_1").workflowVersion(2).modelCode("deepseek-v4-flash")
                .sessionId("session_1").message("开始").requestedRunId("run_1")
                .attachmentIds(List.of("asset_1")).build();
    }

    private WorkflowRuntimeEntity runtime(String kind) {
        return WorkflowRuntimeEntity.builder().workflowId("wf_1").version(2).effectiveModelCode("deepseek-v4-flash")
                .dagPlan(WorkflowDagPlanEntity.builder().workflowKind(kind).workflowId("wf_1").version(2)
                        .rootNodeId("node_1").nodes(List.of()).edges(List.of()).build())
                .build();
    }
}
