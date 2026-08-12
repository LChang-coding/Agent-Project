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
 * 协议的智能工作流非终点节点中可见，并只暴露当前节点允许的路由键；日志工具只允许查看当前运行。</p>
 */
@Service
public class PlatformToolResolver {

    /** 是否允许向符合条件的运行暴露 RAG 平台工具。 */
    private final boolean ragEnabled;

    /** 是否允许向符合条件的智能工作流节点暴露路由平台工具。 */
    private final boolean routeEnabled;

    /** 是否允许 Agent 查询当前运行自己的 Trace 日志。 */
    private final boolean traceLogEnabled;

    /** 是否开放临时子 Agent 编排工具。 */
    private final boolean orchestrationEnabled;

    /**
     * 创建平台工具解析器。
     *
     * @param ragEnabled RAG 平台工具的全局开关
     * @param routeEnabled 智能路由平台工具的全局开关
     * @param traceLogEnabled 当前运行日志工具的全局开关
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformToolResolver(
            @Value("${ai.tools.platform.rag-enabled:true}") boolean ragEnabled,
            @Value("${ai.tools.platform.route-enabled:true}") boolean routeEnabled,
            @Value("${ai.tools.platform.trace-log-enabled:false}") boolean traceLogEnabled,
            @Value("${ai.tools.platform.orchestration-enabled:false}") boolean orchestrationEnabled) {
        this.ragEnabled = ragEnabled;
        this.routeEnabled = routeEnabled;
        this.traceLogEnabled = traceLogEnabled;
        this.orchestrationEnabled = orchestrationEnabled;
    }

    /** 保留历史三参数装配入口。 */
    public PlatformToolResolver(boolean ragEnabled, boolean routeEnabled, boolean traceLogEnabled) {
        this(ragEnabled, routeEnabled, traceLogEnabled, false);
    }

    /** 保留测试和历史装配入口；未显式配置时不开放日志查询工具。 */
    public PlatformToolResolver(boolean ragEnabled, boolean routeEnabled) {
        this(ragEnabled, routeEnabled, false, false);
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
        if (traceLogEnabled && context.getTraceId() != null && !context.getTraceId().isBlank()
                && context.getRunId() != null && !context.getRunId().isBlank()) {
            result.add(platform("query_trace_logs", "查询并分析当前这次运行的应用日志",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" +
                            "\"traceId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64}," +
                            "\"lookbackMinutes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":120}," +
                            "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}}}"));
        }
        if (orchestrationEnabled && "SUPERVISOR".equalsIgnoreCase(context.getOrchestrationRole())
                && context.getRunId() != null && !context.getRunId().isBlank()
                && context.getAllowedSubAgentIds() != null && !context.getAllowedSubAgentIds().isEmpty()) {
            result.add(platform("search_agent_catalog", "检索当前主 Agent 被授权使用的子 Agent 模板",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" +
                            "\"query\":{\"type\":\"string\",\"maxLength\":200}," +
                            "\"category\":{\"type\":\"string\",\"maxLength\":64}}}"));
            result.add(platform("create_subagent_instances", "批量创建临时子 Agent 运行实例并异步执行",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"tasks\"],\"properties\":{" +
                            "\"tasks\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":20,\"items\":{" +
                            "\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"agentId\",\"instruction\"],\"properties\":{" +
                            "\"agentId\":{\"type\":\"string\",\"minLength\":1},\"instruction\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":12000}}}}}}"));
            result.add(platform("read_subagent_result", "读取当前主运行已收到的子 Agent 结果",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" +
                            "\"taskIds\":{\"type\":\"array\",\"maxItems\":100,\"items\":{\"type\":\"string\"}}}}"));
            result.add(platform("read_subagent_full_context", "按需读取当前主运行所属子 Agent 的完整上下文",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"taskIds\"],\"properties\":{" +
                            "\"taskIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":20,\"items\":{\"type\":\"string\"}}}}"));
            result.add(platform("cancel_subagent_instances", "取消当前主运行尚未终结的子 Agent 运行实例",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"taskIds\"],\"properties\":{" +
                            "\"taskIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":100,\"items\":{\"type\":\"string\"}}}}"));
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
