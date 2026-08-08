package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据可信运行上下文生成本次模型调用可见的平台工具。
 *
 * <p>RAG 工具受会话调用方式、RAG 开关、节点权限和绑定范围共同限制；路由工具只在使用 TOOL_V2
 * 协议的智能工作流非终点节点中可见，并只暴露当前节点允许的路由键。</p>
 */
@Service
public class PlatformToolResolver {

    /** 是否允许向符合条件的运行暴露 RAG 平台工具。 */
    private final boolean ragEnabled;

    /** 是否允许向符合条件的智能工作流节点暴露路由平台工具。 */
    private final boolean routeEnabled;

    /**
     * 创建平台工具解析器。
     *
     * @param ragEnabled RAG 平台工具的全局开关
     * @param routeEnabled 智能路由平台工具的全局开关
     */
    public PlatformToolResolver(
            @Value("${ai.tools.platform.rag-enabled:true}") boolean ragEnabled,
            @Value("${ai.tools.platform.route-enabled:true}") boolean routeEnabled) {
        this.ragEnabled = ragEnabled;
        this.routeEnabled = routeEnabled;
    }

    /**
     * 计算当前模型调用实际可见的平台工具目录。
     *
     * @param context 服务端组装的可信运行上下文；为空时不暴露任何平台工具
     * @return 当前上下文允许使用的平台工具列表
     */
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

    /** 根据固定平台版本和 JSON Schema 构造仅用于本次运行的工具目录项。 */
    private ToolCatalogEntity platform(String functionName, String description, String schema) {
        return ToolCatalogEntity.builder().toolType(ToolType.PLATFORM).toolId(functionName)
                .toolCode(functionName).functionName(functionName).toolName(functionName)
                .description(description).schemaJson(schema).version("platform_v1").build();
    }

    /** 转义路由键中的反斜杠和引号，保证动态枚举仍是合法 JSON。 */
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    /**
     * 当前工作流节点允许模型选择的一条业务路由。
     *
     * @param routeKey 模型可见并提交的主路由键
     * @param edgeId 服务端用于定位工作流边的标识
     * @param targetNodeId 该路由对应的目标节点
     * @param aliases 只用于服务端精确兼容匹配的受控别名
     */
    public record RouteDescriptor(String routeKey, String edgeId, String targetNodeId, List<String> aliases) {

        /**
         * 创建不带兼容别名的路由描述。
         *
         * @param routeKey 模型可见的主路由键
         * @param edgeId 服务端工作流边标识
         * @param targetNodeId 路由命中的目标节点
         */
        public RouteDescriptor(String routeKey, String edgeId, String targetNodeId) {
            this(routeKey, edgeId, targetNodeId, List.of());
        }
    }
}
