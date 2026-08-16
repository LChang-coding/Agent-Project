package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.service.AgentToolPermissionService;
import cn.bugstack.ai.domain.agent.service.ToolApprovalService;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.domain.tool.service.*;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ToolGatewayPlatformTest {

    @Test
    public void publishesStartedAndCompletedForClaimedWorkflowPlatformCallUsingAuditResultOnly() {
        ToolDispatchAuthorizationService authorization = mock(ToolDispatchAuthorizationService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) -> new PlatformToolResult(true,
                Map.of("context", "private model context", "retrievalId", "ret-1"),
                Map.of("retrievalId", "ret-1", "hits", 2), null));
        ToolCallLogEntity log = ToolCallLogEntity.builder().status(ToolStatus.STARTED).build();
        when(authorization.claim(any(), any(), any())).thenReturn(
                ToolDispatchClaimEntity.builder().claimed(true).callLog(log).build());
        ToolGateway gateway = gateway(authorization, registry, events);

        Map<String, Object> result = gateway.invoke(platformTool(), Map.of("query", "q"), workflowContext());

        Assert.assertEquals("private model context", ((Map<?, ?>) result.get("result")).get("context"));
        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nodeExecutionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(events, times(2)).publish(eq("tenant"), eq("user"), eq("run"), eq("trace-root"),
                eventType.capture(), nodeExecutionId.capture(), isNull(), payload.capture());
        Assert.assertEquals(List.of("TOOL_CALL_STARTED", "TOOL_CALL_COMPLETED"), eventType.getAllValues());
        Assert.assertEquals(List.of("node-execution", "node-execution"), nodeExecutionId.getAllValues());
        Assert.assertTrue(payload.getAllValues().get(0).contains("\"functionCallId\":\"call\""));
        Assert.assertTrue(payload.getAllValues().get(1).contains("\"retrievalId\":\"ret-1\""));
        Assert.assertTrue(payload.getAllValues().get(1).contains("\"hits\":2"));
        Assert.assertFalse(payload.getAllValues().get(1).contains("private model context"));
        Assert.assertFalse(payload.getAllValues().get(1).contains("\"context\""));
        ArgumentCaptor<String> auditOutput = ArgumentCaptor.forClass(String.class);
        verify(authorization).finish(eq(log), auditOutput.capture(), eq(ToolStatus.SUCCESS), isNull(), isNull(), anyLong());
        Assert.assertTrue(auditOutput.getValue().contains("\"modelResult\""));
        Assert.assertTrue(auditOutput.getValue().contains("\"auditResult\""));
    }

    @Test
    public void publishesFailedForClaimedWorkflowPlatformCall() {
        ToolDispatchAuthorizationService authorization = mock(ToolDispatchAuthorizationService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) ->
                PlatformToolResult.failure("RAG_TOOL_RETRIEVAL_FAILED:检索失败"));
        when(authorization.claim(any(), any(), any())).thenReturn(ToolDispatchClaimEntity.builder()
                .claimed(true).callLog(ToolCallLogEntity.builder().status(ToolStatus.STARTED).build()).build());

        Map<String, Object> result = gateway(authorization, registry, events)
                .invoke(platformTool(), Map.of("query", "q"), workflowContext());

        Assert.assertEquals(false, result.get("success"));
        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(events, times(2)).publish(eq("tenant"), eq("user"), eq("run"), eq("trace-root"),
                eventType.capture(), eq("node-execution"), isNull(), payload.capture());
        Assert.assertEquals(List.of("TOOL_CALL_STARTED", "TOOL_CALL_FAILED"), eventType.getAllValues());
        Assert.assertTrue(payload.getAllValues().get(1).contains("\"errorCode\":\"RAG_TOOL_RETRIEVAL_FAILED\""));
        Assert.assertTrue(payload.getAllValues().get(1).contains("\"functionCallId\":\"call\""));
        Assert.assertEquals("检索失败", result.get("error"));
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(authorization).finish(any(), isNull(), eq(ToolStatus.FAILED), eq("AppException"),
                errorMessage.capture(), anyLong());
        Assert.assertEquals("检索失败", errorMessage.getValue());
    }

    @Test
    public void dispatchesPlatformAfterClaimAndReplaysStructuredResult() {
        ToolDispatchAuthorizationService authorization = mock(ToolDispatchAuthorizationService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) ->
                PlatformToolResult.success(Map.of("retrievalId", "ret-1", "citations", 2)));
        ToolCallLogEntity log = ToolCallLogEntity.builder().status(ToolStatus.SUCCESS)
                .outputJson("{\"modelResult\":{\"retrievalId\":\"ret-1\",\"citations\":2},\"auditResult\":{\"summary\":\"ok\"}}")
                .build();
        when(authorization.claim(any(), any(), any())).thenReturn(ToolDispatchClaimEntity.builder().claimed(false).callLog(log).build());

        ToolGateway gateway = gateway(authorization, registry, events);
        Map<String, Object> result = gateway.invoke(ToolCatalogEntity.builder().toolType("platform").toolId("rag").functionName("rag_retrieve").build(),
                Map.of("query", "q"), ToolInvokeContextEntity.builder().tenantId("tenant").userId("user").runId("run").functionCallId("call").build());

        Assert.assertTrue(result.get("result") instanceof Map);
        Assert.assertEquals("ret-1", ((Map<?, ?>) result.get("result")).get("retrievalId"));
        Assert.assertNotEquals("{retrievalId=ret-1, citations=2}", result.get("result"));
        verify(authorization, never()).finish(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    public void publishesToolEventsForAgentRunsOutsideWorkflowNodes() {
        ToolDispatchAuthorizationService authorization = mock(ToolDispatchAuthorizationService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) -> PlatformToolResult.success(Map.of("ok", true)));
        when(authorization.claim(any(), any(), any())).thenReturn(ToolDispatchClaimEntity.builder()
                .claimed(true).callLog(ToolCallLogEntity.builder().status(ToolStatus.STARTED).build()).build());

        gateway(authorization, registry, events).invoke(platformTool(), Map.of(), ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .traceId("trace-root").build());

        verify(events, times(2)).publish(eq("tenant"), eq("user"), eq("run"),
                eq("trace-root"), anyString(), isNull(), isNull(), anyString());
    }

    @Test
    public void appliesConfiguredApprovalToPlatformToolBeforeClaim() {
        ToolDispatchAuthorizationService authorization = mock(ToolDispatchAuthorizationService.class);
        AgentToolPermissionService permissions = mock(AgentToolPermissionService.class);
        ToolApprovalService approvals = mock(ToolApprovalService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) -> PlatformToolResult.success(input));
        when(permissions.resolve("tenant", "agent-1", "rag_retrieve")).thenReturn(
                AgentToolPermissionEntity.builder().toolCode("rag_retrieve").mode("REQUIRE_APPROVAL")
                        .timeoutSeconds(60).timeoutDecision("REJECT").build());
        when(approvals.request(any(), eq("rag_retrieve"), anyMap(), any())).thenReturn(
                ToolApprovalRequestEntity.builder().approvalId("approval-1").build());
        when(approvals.awaitDecision(any(), any())).thenReturn(ToolApprovalRequestEntity.builder()
                .decision("APPROVE_WITH_CHANGES").amendedInput(Map.of("query", "approved query")).build());
        when(authorization.claim(any(), any(), any())).thenReturn(ToolDispatchClaimEntity.builder()
                .claimed(true).callLog(ToolCallLogEntity.builder().status(ToolStatus.STARTED).build()).build());
        ToolGateway gateway = new ToolGateway(mock(ObjectStorageService.class),
                mock(cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport.class), authorization,
                mock(cn.bugstack.ai.domain.tool.service.support.SkillPackageReader.class), registry,
                mock(WorkflowEventStreamService.class), permissions, approvals);
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder().tenantId("tenant").userId("user")
                .agentId("agent-1").sessionId("session").runId("run").functionCallId("call").build();

        Map<String, Object> result = gateway.invoke(platformTool(), Map.of("query", "original"), context);

        Assert.assertEquals("approved query", ((Map<?, ?>) result.get("result")).get("query"));
        verify(authorization).claim(any(), eq(context), contains("approved query"));
    }

    private ToolGateway gateway(ToolDispatchAuthorizationService authorization, PlatformToolRegistry registry,
                                WorkflowEventStreamService events) {
        return new ToolGateway(mock(ObjectStorageService.class),
                mock(cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport.class), authorization,
                mock(cn.bugstack.ai.domain.tool.service.support.SkillPackageReader.class), registry, events);
    }

    private ToolCatalogEntity platformTool() {
        return ToolCatalogEntity.builder().toolType("platform").toolId("rag")
                .toolCode("platform_rag_retrieve_v1").toolName("知识库检索")
                .functionName("rag_retrieve").build();
    }

    private ToolInvokeContextEntity workflowContext() {
        return ToolInvokeContextEntity.builder().tenantId("tenant").userId("user").runId("run")
                .workflowId("workflow").nodeExecutionId("node-execution").functionCallId("call")
                .traceId("trace-root").build();
    }
}
