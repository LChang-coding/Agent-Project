package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformToolResolver {
    private final boolean ragEnabled;
    private final boolean routeEnabled;

    public PlatformToolResolver(
            @Value("${ai.tools.platform.rag-enabled:true}") boolean ragEnabled,
            @Value("${ai.tools.platform.route-enabled:true}") boolean routeEnabled) {
        this.ragEnabled = ragEnabled;
        this.routeEnabled = routeEnabled;
    }

    public List<ToolCatalogEntity> resolve(ToolInvokeContextEntity context) {
        List<ToolCatalogEntity> result = new ArrayList<>();
        if (context == null) return result;
        if (ragEnabled && "AGENT_TOOL".equalsIgnoreCase(context.getRagInvocationMode())
                && !"OFF".equalsIgnoreCase(context.getRagMode())
                && !Boolean.FALSE.equals(context.getRagToolEnabled())
                && context.getRagTargetType() != null && context.getRagTargetId() != null) {
            result.add(platform("rag_retrieve", "检索当前可信知识库上下文", "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1},\"maxContextTokens\":{\"type\":\"integer\",\"minimum\":128,\"maximum\":8000}}}"));
        }
        if (routeEnabled && "INTELLIGENT".equalsIgnoreCase(context.getWorkflowKind())
                && "TOOL_V2".equalsIgnoreCase(context.getRoutingProtocolVersion())
                && !Boolean.TRUE.equals(context.getTerminalNode())
                && context.getRouteDescriptors() != null && !context.getRouteDescriptors().isEmpty()) {
            String enumJson = context.getRouteDescriptors().stream().map(RouteDescriptor::routeKey)
                    .filter(value -> value != null && !value.isBlank()).distinct()
                    .map(value -> "\"" + escape(value) + "\"").reduce((a, b) -> a + "," + b).orElse("");
            result.add(platform("select_workflow_route", "只能选择一个当前节点已配置的路由键", "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"routeKey\",\"reason\"],\"properties\":{\"routeKey\":{\"type\":\"string\",\"enum\":[" + enumJson + "]},\"reason\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":500}}}"));
        }
        return result;
    }

    private ToolCatalogEntity platform(String functionName, String description, String schema) {
        return ToolCatalogEntity.builder().toolType(ToolType.PLATFORM).toolId(functionName)
                .toolCode(functionName).functionName(functionName).toolName(functionName)
                .description(description).schemaJson(schema).version("platform_v1").build();
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public record RouteDescriptor(String routeKey, String edgeId, String targetNodeId, List<String> aliases) {
        public RouteDescriptor(String routeKey, String edgeId, String targetNodeId) {
            this(routeKey, edgeId, targetNodeId, List.of());
        }
    }
}
