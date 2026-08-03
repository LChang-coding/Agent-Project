package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowInvocationRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowInvocationGuardService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 取消与新外部调用登记的线性化门禁测试。 */
public class WorkflowInvocationGuardServiceTest {

    @Test
    public void shouldRegisterInvocationWhileRunRowIsLockedAndExecutable() {
        IChatRunRepository runs = mock(IChatRunRepository.class);
        IWorkflowInvocationRepository invocations = mock(IWorkflowInvocationRepository.class);
        WorkflowInvocationEntity invocation = invocation();
        when(runs.lock("tenant_1", "user_1", "run_1")).thenReturn(run(RunStatus.RUNNING, null));
        when(invocations.insertIgnore(invocation)).thenReturn(1);

        Assert.assertTrue(new WorkflowInvocationGuardService(runs, invocations).register(invocation, "user_1"));
        verify(runs).lock("tenant_1", "user_1", "run_1");
        verify(invocations).insertIgnore(invocation);
    }

    @Test
    public void shouldNotWriteInvocationAfterCancellationIsVisibleUnderLock() {
        IChatRunRepository runs = mock(IChatRunRepository.class);
        IWorkflowInvocationRepository invocations = mock(IWorkflowInvocationRepository.class);
        WorkflowInvocationEntity invocation = invocation();
        when(runs.lock("tenant_1", "user_1", "run_1"))
                .thenReturn(run(RunStatus.CANCELLED, LocalDateTime.now()));
        try {
            new WorkflowInvocationGuardService(runs, invocations).register(invocation, "user_1");
            Assert.fail("取消提交后不得登记新调用");
        } catch (AppException exception) {
            Assert.assertEquals("RUN_NOT_EXECUTABLE", exception.getCode());
        }
        verify(invocations, never()).insertIgnore(invocation);
    }

    private WorkflowInvocationEntity invocation() {
        return WorkflowInvocationEntity.builder().tenantId("tenant_1").runId("run_1")
                .invocationId("invocation_1").traceId("trace_root_1234").build();
    }

    private ChatRunEntity run(RunStatus status, LocalDateTime cancelRequestedAt) {
        return ChatRunEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .traceId("trace_root_1234").status(status).cancelRequestedAt(cancelRequestedAt).build();
    }
}
