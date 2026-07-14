package cn.bugstack.ai.test.run;

import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.ToolGateDecision;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.run.service.RunExecutionGate;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行闸门测试。
 */
public class RunExecutionGateTest {

    @Test
    public void shouldBlockCurrentToolCallWhenPreToolCompactionCompleted() {
        RunControlService runControlService = mock(RunControlService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        RunExecutionGate gate = new RunExecutionGate(runControlService, memoryService);
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .runId("run-1").contextRevision(5L).traceId("trace-1").build();
        when(memoryService.compactBeforeTool("tenant-1", "user-1", "session-1", "run-1", 18, "trace-1"))
                .thenReturn(true);
        when(runControlService.refreshContextRevision("tenant-1", "user-1", "run-1"))
                .thenReturn(ChatRunEntity.builder().runId("run-1").currentContextRevision(6L).build());

        ToolGateDecision decision = gate.beforeTool(context, 18);

        assertEquals(ToolGateDecision.RETRY_MODEL, decision);
        verify(runControlService).requireExecutable("tenant-1", "user-1", "run-1", 5L);
        verify(runControlService).refreshContextRevision("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldRecheckRunImmediatelyBeforeExternalDispatch() {
        RunControlService runControlService = mock(RunControlService.class);
        RunExecutionGate gate = new RunExecutionGate(runControlService, mock(ConversationMemoryService.class));
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant-1").userId("user-1").runId("run-1").contextRevision(9L).build();

        gate.beforeDispatch(context);

        verify(runControlService).authorizeToolDispatch("tenant-1", "user-1", "run-1", 9L);
    }
}
