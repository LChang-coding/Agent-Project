package cn.bugstack.ai.test.run;

import cn.bugstack.ai.api.dto.CancelRunRequestDTO;
import cn.bugstack.ai.api.dto.RunControlResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRuntimeService;
import cn.bugstack.ai.trigger.http.RunControlController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运行控制接口的身份、终态协调和双 Trace 响应契约。 */
public class RunControlControllerTest {

    @After
    public void cleanup() {
        TenantContextHolder.clear();
        TraceContext.clear();
    }

    @Test
    public void shouldReturnRootAndOperationTraceWhenCancelling() {
        RunControlService runControlService = mock(RunControlService.class);
        IntelligentWorkflowRuntimeService runtimeService = mock(IntelligentWorkflowRuntimeService.class);
        RunControlController controller = new RunControlController(runControlService, runtimeService);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").build());
        TraceContext.setTraceId("operation-trace-1");
        ChatRunEntity cancelled = ChatRunEntity.builder()
                .tenantId("tenant-1").userId("user-1").sessionId("session-1").runId("run-1")
                .traceId("root-trace-1").status(RunStatus.CANCELLED).currentContextRevision(7L).build();
        when(runControlService.cancel("tenant-1", "user-1", "run-1", "用户取消")).thenReturn(cancelled);
        CancelRunRequestDTO request = new CancelRunRequestDTO();
        request.setReason("用户取消");

        Response<RunControlResponseDTO> response = controller.cancel("run-1", request);

        assertEquals("0000", response.getCode());
        assertEquals("root-trace-1", response.getData().getTraceId());
        assertEquals("operation-trace-1", response.getData().getOperationTraceId());
        verify(runtimeService).reconcileCancellation(cancelled);
    }
}
