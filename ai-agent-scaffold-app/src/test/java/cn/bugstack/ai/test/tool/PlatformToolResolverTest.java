package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import org.junit.Assert;
import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class PlatformToolResolverTest {

    @Test
    public void exposesRagToolOnlyForAgentToolModeWithoutRequiringBindings() {
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .ragInvocationMode("AGENT_TOOL").ragBindingIds(List.of())
                .ragTargetType("WORKFLOW").ragTargetId("wf")
                .build();

        List<?> tools = new PlatformToolResolver(true, true).resolve(context);

        Assert.assertTrue(tools.stream().anyMatch(tool ->
                ToolType.PLATFORM.equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool).getToolCode())
                        || "rag_retrieve".equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool).getFunctionName())));
    }

    @Test
    public void doesNotExposeRagForOffOrAutoContext() {
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .ragInvocationMode("AUTO_CONTEXT").ragBindingIds(List.of("binding"))
                .build();

        Assert.assertTrue(new PlatformToolResolver(true, true).resolve(context).stream()
                .noneMatch(tool -> "rag_retrieve".equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool).getFunctionName())));
    }

    @Test
    public void doesNotExposeRagWhenCurrentNodeExplicitlyDisablesIt() {
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .ragInvocationMode("AGENT_TOOL").ragMode("HYBRID").ragToolEnabled(false)
                .ragTargetType("WORKFLOW").ragTargetId("wf")
                .build();

        Assert.assertTrue(new PlatformToolResolver(true, true).resolve(context).stream()
                .noneMatch(tool -> "rag_retrieve".equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool).getFunctionName())));
    }

    @Test
    public void exposesRouteToolOnlyFromFrozenToolV2NonTerminalDescriptors() {
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .workflowKind("INTELLIGENT").routingProtocolVersion("TOOL_V2")
                .terminalNode(false).routeDescriptors(List.of(
                        new PlatformToolResolver.RouteDescriptor("yes", "edge-1", "node-2")))
                .build();

        List<?> tools = new PlatformToolResolver(true, true).resolve(context);

        Assert.assertTrue(tools.stream().anyMatch(tool ->
                "select_workflow_route".equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool).getFunctionName())));
    }

    @Test
    public void exposesTraceLogToolOnlyWhenEnabledAndCurrentTraceIsAvailable() {
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .traceId("trace-current")
                .build();

        List<?> tools = new PlatformToolResolver(false, false, true).resolve(context);

        Assert.assertTrue(tools.stream().anyMatch(tool ->
                "query_trace_logs".equals(((cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity) tool)
                        .getFunctionName())));
        Assert.assertTrue(new PlatformToolResolver(false, false, false).resolve(context).isEmpty());
        context.setTraceId(null);
        Assert.assertTrue(new PlatformToolResolver(false, false, true).resolve(context).isEmpty());
    }

    @Test
    public void exposesSupervisorToolsOnlyToTrustedSupervisorContext() {
        ToolInvokeContextEntity supervisor = ToolInvokeContextEntity.builder()
                .tenantId("tenant").userId("user").runId("run").functionCallId("call")
                .agentId("supervisor").orchestrationRole("SUPERVISOR")
                .allowedSubAgentIds(List.of("research-agent", "planning-agent"))
                .build();

        List<String> functions = new PlatformToolResolver(false, false, false, true).resolve(supervisor)
                .stream().map(tool -> tool.getFunctionName()).toList();

        Assert.assertTrue(functions.contains("search_agent_catalog"));
        Assert.assertTrue(functions.contains("create_subagent_instances"));
        Assert.assertTrue(functions.contains("read_subagent_result"));
        Assert.assertTrue(functions.contains("read_subagent_full_context"));
        Assert.assertTrue(functions.contains("cancel_subagent_instances"));

        supervisor.setOrchestrationRole("NORMAL");
        Assert.assertTrue(new PlatformToolResolver(false, false, false, true).resolve(supervisor).isEmpty());
    }

    @Test
    public void supervisorToolSchemasAreValidJson() throws Exception {
        ToolInvokeContextEntity supervisor = ToolInvokeContextEntity.builder().runId("run")
                .orchestrationRole("SUPERVISOR").allowedSubAgentIds(List.of("research")).build();
        ObjectMapper mapper = new ObjectMapper();
        for (cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity tool
                : new PlatformToolResolver(false, false, false, true).resolve(supervisor)) {
            Assert.assertEquals("object", mapper.readTree(tool.getSchemaJson()).path("type").asText());
        }
    }
}
