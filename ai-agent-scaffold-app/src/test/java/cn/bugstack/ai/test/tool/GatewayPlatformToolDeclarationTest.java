package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.GatewayAdkTool;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import cn.bugstack.ai.domain.tool.service.ToolGateway;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GatewayPlatformToolDeclarationTest {

    @Test
    public void declaresFixedPlatformFunctionNameAndCompleteSchema() {
        ToolCatalogEntity tool = ToolCatalogEntity.builder()
                .toolType("platform").toolCode("platform").functionName("rag_retrieve")
                .toolName("RAG").description("retrieve trusted context")
                .schemaJson("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"routeKey\"],\"properties\":{" +
                        "\"routeKey\":{\"type\":\"string\",\"enum\":[\"正确\",\"错误\"],\"minLength\":1,\"maxLength\":128}," +
                        "\"maxContextTokens\":{\"type\":\"integer\",\"minimum\":128,\"maximum\":8000}}}")
                .build();

        GatewayAdkTool adkTool = new GatewayAdkTool(tool, mock(cn.bugstack.ai.domain.tool.service.ToolGateway.class),
                ToolInvokeContextEntity.builder().tenantId("tenant").userId("user").runId("run").build());
        FunctionDeclaration declaration = adkTool.declaration().orElseThrow();

        Assert.assertEquals("rag_retrieve", declaration.name().orElseThrow());
        Assert.assertEquals("object", declaration.parameters().orElseThrow().type().orElseThrow().toString().toLowerCase());
        Schema parameters = declaration.parameters().orElseThrow();
        Schema routeKey = parameters.properties().orElseThrow().get("routeKey");
        Schema maxContextTokens = parameters.properties().orElseThrow().get("maxContextTokens");
        Assert.assertEquals(List.of("正确", "错误"), routeKey.enum_().orElseThrow());
        Assert.assertEquals(Long.valueOf(1), routeKey.minLength().orElseThrow());
        Assert.assertEquals(Long.valueOf(128), routeKey.maxLength().orElseThrow());
        Assert.assertEquals(Double.valueOf(128), maxContextTokens.minimum().orElseThrow());
        Assert.assertEquals(Double.valueOf(8000), maxContextTokens.maximum().orElseThrow());
    }

    @Test
    public void declaresNestedArrayAndObjectSchemaForSubagentDelegation() {
        ToolInvokeContextEntity supervisor = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run")
                .agentId("supervisor").orchestrationRole("SUPERVISOR")
                .allowedSubAgentIds(List.of("100001", "100002")).build();
        ToolCatalogEntity delegate = new PlatformToolResolver(false, false, false, true).resolve(supervisor).stream()
                .filter(tool -> "create_subagent_instances".equals(tool.getFunctionName()))
                .findFirst().orElseThrow();

        Schema parameters = new GatewayAdkTool(delegate, mock(ToolGateway.class), supervisor)
                .declaration().orElseThrow().parameters().orElseThrow();
        Schema tasks = parameters.properties().orElseThrow().get("tasks");
        Schema task = tasks.items().orElseThrow();

        Assert.assertEquals("array", tasks.type().orElseThrow().toString().toLowerCase());
        Assert.assertEquals(Long.valueOf(1), tasks.minItems().orElseThrow());
        Assert.assertEquals(Long.valueOf(20), tasks.maxItems().orElseThrow());
        Assert.assertEquals("object", task.type().orElseThrow().toString().toLowerCase());
        Assert.assertEquals(List.of("agentId", "instruction"), task.required().orElseThrow());
        Assert.assertEquals(List.of("100001", "100002"), task.properties().orElseThrow()
                .get("agentId").enum_().orElseThrow());
        Assert.assertEquals(Long.valueOf(12000), task.properties().orElseThrow()
                .get("instruction").maxLength().orElseThrow());
    }

    @Test
    public void declaresEverySupervisorPlatformToolSchema() {
        ToolInvokeContextEntity supervisor = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run")
                .agentId("supervisor").orchestrationRole("SUPERVISOR")
                .allowedSubAgentIds(List.of("100001")).build();

        for (ToolCatalogEntity tool : new PlatformToolResolver(false, false, false, true).resolve(supervisor)) {
            Assert.assertTrue(new GatewayAdkTool(tool, mock(ToolGateway.class), supervisor)
                    .declaration().orElseThrow().parameters().isPresent());
        }
    }

    @Test
    public void doesNotAcceptModelIdentityAsTrustedContext() {
        ToolCatalogEntity tool = ToolCatalogEntity.builder().toolType("platform").functionName("rag_retrieve").toolId("rag")
                .schemaJson("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}").build();
        cn.bugstack.ai.domain.tool.service.ToolGateway gateway = mock(cn.bugstack.ai.domain.tool.service.ToolGateway.class);
        when(gateway.invoke(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("success", true));
        GatewayAdkTool adkTool = new GatewayAdkTool(tool, gateway,
                ToolInvokeContextEntity.builder().tenantId("trusted").userId("user").runId("run").build());

        Map<String, Object> result = adkTool.runAsync(Map.of("tenantId", "attacker"), null).blockingGet();

        Assert.assertTrue(result.containsKey("success"));
    }

    @Test
    public void generatesServerFunctionCallIdWhenProviderOmitsIt() {
        ToolCatalogEntity tool = ToolCatalogEntity.builder().toolType("platform").functionName("rag_retrieve").toolId("rag")
                .schemaJson("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}").build();
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.invoke(any(), any(), any())).thenReturn(Map.of("success", true));
        GatewayAdkTool adkTool = new GatewayAdkTool(tool, gateway,
                ToolInvokeContextEntity.builder().tenantId("tenant").userId("user")
                        .sessionId("session").runId("run").invocationId("invocation").build());

        adkTool.runAsync(Map.of("query", "question"), null).blockingGet();

        ArgumentCaptor<ToolInvokeContextEntity> context = ArgumentCaptor.forClass(ToolInvokeContextEntity.class);
        verify(gateway).invoke(any(), any(), context.capture());
        Assert.assertNotNull(context.getValue().getFunctionCallId());
        Assert.assertTrue(context.getValue().getFunctionCallId().startsWith("server_call_"));
    }

    @Test
    public void runAsyncCopiesEveryTrustedPlatformFieldWithoutAcceptingModelOverrides() {
        ToolCatalogEntity tool = ToolCatalogEntity.builder().toolType("platform").functionName("rag_retrieve").toolId("rag")
                .schemaJson("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}").build();
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.invoke(any(), any(), any())).thenReturn(Map.of("success", true));
        List<String> bindingIds = List.of("binding-1", "binding-2");
        List<PlatformToolResolver.RouteDescriptor> routes = List.of(
                new PlatformToolResolver.RouteDescriptor("正确", "edge-1", "node-2"));
        ToolInvokeContextEntity fallback = ToolInvokeContextEntity.builder()
                .tenantId("trusted-tenant").userId("trusted-user").sessionId("session-1")
                .workflowId("workflow-1").invocationId("invocation-1").runId("run-1")
                .contextRevision(7L).functionCallId("call-1").traceId("trace-1")
                .ragInvocationMode("AGENT_TOOL").ragMode("MANUAL")
                .ragEvidenceInvocationId("evidence-1").ragTargetType("WORKFLOW")
                .ragTargetId("workflow-1").ragBindingIds(bindingIds)
                .workflowKind("INTELLIGENT").routingProtocolVersion("TOOL_V2")
                .nodeExecutionId("node-execution-1").definitionHash("hash-1")
                .workflowVersion("12").terminalNode(false).routeDescriptors(routes)
                .build();
        GatewayAdkTool adkTool = new GatewayAdkTool(tool, gateway, fallback);

        adkTool.runAsync(Map.of(
                "query", "question",
                "tenantId", "attacker",
                "workflowVersion", "999",
                "ragBindingIds", List.of("attacker-binding")), null).blockingGet();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ToolInvokeContextEntity> contextCaptor = ArgumentCaptor.forClass(ToolInvokeContextEntity.class);
        verify(gateway).invoke(any(), inputCaptor.capture(), contextCaptor.capture());
        ToolInvokeContextEntity context = contextCaptor.getValue();
        Assert.assertEquals("trusted-tenant", context.getTenantId());
        Assert.assertEquals("trusted-user", context.getUserId());
        Assert.assertEquals("session-1", context.getSessionId());
        Assert.assertEquals("workflow-1", context.getWorkflowId());
        Assert.assertEquals("invocation-1", context.getInvocationId());
        Assert.assertEquals("run-1", context.getRunId());
        Assert.assertEquals(Long.valueOf(7), context.getContextRevision());
        Assert.assertEquals("call-1", context.getFunctionCallId());
        Assert.assertEquals("trace-1", context.getTraceId());
        Assert.assertEquals("AGENT_TOOL", context.getRagInvocationMode());
        Assert.assertEquals("MANUAL", context.getRagMode());
        Assert.assertEquals("evidence-1", context.getRagEvidenceInvocationId());
        Assert.assertEquals("WORKFLOW", context.getRagTargetType());
        Assert.assertEquals("workflow-1", context.getRagTargetId());
        Assert.assertEquals(bindingIds, context.getRagBindingIds());
        Assert.assertEquals("INTELLIGENT", context.getWorkflowKind());
        Assert.assertEquals("TOOL_V2", context.getRoutingProtocolVersion());
        Assert.assertEquals("node-execution-1", context.getNodeExecutionId());
        Assert.assertEquals("hash-1", context.getDefinitionHash());
        Assert.assertEquals("12", context.getWorkflowVersion());
        Assert.assertEquals(Boolean.FALSE, context.getTerminalNode());
        Assert.assertEquals(routes, context.getRouteDescriptors());
        Assert.assertEquals("attacker", inputCaptor.getValue().get("tenantId"));
    }
}
