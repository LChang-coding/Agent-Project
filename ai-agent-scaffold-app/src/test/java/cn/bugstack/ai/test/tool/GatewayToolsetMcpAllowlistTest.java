package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.tool.service.GatewayAdkTool;
import cn.bugstack.ai.domain.tool.service.GatewayToolset;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import cn.bugstack.ai.domain.tool.service.ToolGateway;
import cn.bugstack.ai.domain.tool.service.ToolResolver;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GatewayToolsetMcpAllowlistTest {

    @Test
    public void workflowNodeWithoutMcpIdsCannotSeeExternalMcp() {
        List<String> names = namesFor(Map.of(
                ToolRuntimeContextKeys.TENANT_ID, "tenant",
                ToolRuntimeContextKeys.USER_ID, "user",
                ToolRuntimeContextKeys.WORKFLOW_KIND, "STATIC",
                ToolRuntimeContextKeys.WORKFLOW_MCP_IDS, List.of()));

        assertFalse(names.contains("mcp_bing"));
        assertTrue(names.contains("skill_internal"));
    }

    @Test
    public void workflowNodeCanSeeOnlyWhitelistedMcp() {
        List<String> names = namesFor(Map.of(
                ToolRuntimeContextKeys.TENANT_ID, "tenant",
                ToolRuntimeContextKeys.USER_ID, "user",
                ToolRuntimeContextKeys.WORKFLOW_KIND, "INTELLIGENT",
                ToolRuntimeContextKeys.WORKFLOW_MCP_IDS, List.of("mcp_allowed")));

        assertTrue(names.contains("mcp_allowed"));
        assertFalse(names.contains("mcp_bing"));
    }

    private List<String> namesFor(Map<String, Object> state) {
        ToolResolver resolver = mock(ToolResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ToolCatalogEntity.builder().toolType("mcp").toolId("mcp_bing").toolCode("bing").build(),
                ToolCatalogEntity.builder().toolType("mcp").toolId("mcp_allowed").toolCode("allowed").build(),
                ToolCatalogEntity.builder().toolType("skill").toolId("skill-1").toolCode("internal").build()));
        ReadonlyContext context = mock(ReadonlyContext.class);
        when(context.state()).thenReturn(state);
        when(context.userId()).thenReturn("user");
        when(context.sessionId()).thenReturn("session");
        when(context.invocationId()).thenReturn("invocation");
        return new GatewayToolset(resolver, mock(ToolGateway.class), new PlatformToolResolver(false, false))
                .getTools(context).map(BaseTool::name).toList().blockingGet();
    }
}
