package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ADK 网关工具包装器。
 * <p>负责把工具目录中的 Skill/MCP 包装成 ADK 可识别的函数工具。</p>
 */
public class GatewayAdkTool extends BaseTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;

    private final ToolCatalogEntity tool;
    private final ToolGateway toolGateway;
    private final ToolInvokeContextEntity fallbackContext;
    private final FunctionDeclaration declaration;

    /**
     * 创建 ADK 工具；参数是工具目录和工具网关；返回工具实例。
     */
    public GatewayAdkTool(ToolCatalogEntity tool, ToolGateway toolGateway, ToolInvokeContextEntity fallbackContext) {
        super(toolName(tool), toolDescription(tool));
        this.tool = tool;
        this.toolGateway = toolGateway;
        this.fallbackContext = fallbackContext == null
                ? ToolInvokeContextEntity.builder().traceId(TraceContext.currentOrNewTraceId()).build()
                : fallbackContext;
        this.declaration = buildDeclaration(name(), description(), tool);
        setCustomMetadata("toolId", tool.getToolId());
        setCustomMetadata("toolType", tool.getToolType());
        setCustomMetadata("version", tool.getVersion());
    }

    /**
     * 获取函数声明；无参数；返回 ADK 函数声明。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
    }

    /**
     * 执行工具；参数是模型传入参数和 ADK 工具上下文；返回工具结果 Map。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        Map<String, Object> input = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        ToolInvokeContextEntity context = invokeContext(toolContext);
        if (blank(context.getTenantId()) || blank(context.getUserId())) {
            return Single.just(Map.of("success", false, "error", "工具调用缺少可信身份上下文，已拒绝执行"));
        }
        return Single.fromCallable(() -> toolGateway.invoke(tool, input, context));
    }

    /**
     * 构建调用上下文；参数是 ADK 工具上下文；返回工具网关上下文。
     */
    private ToolInvokeContextEntity invokeContext(ToolContext toolContext) {
        if (toolContext == null) {
            return copyFallbackContext();
        }
        Map<String, Object> state = toolContext.state() == null ? Map.of() : toolContext.state();
        return ToolInvokeContextEntity.builder()
                .tenantId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID)), fallbackContext.getTenantId()))
                .userId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), toolContext.userId()), fallbackContext.getUserId()))
                .sessionId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID)), toolContext.sessionId()), fallbackContext.getSessionId()))
                .workflowId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_ID)), fallbackContext.getWorkflowId()))
                .invocationId(defaultString(toolContext.invocationId(), fallbackContext.getInvocationId()))
                .runId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RUN_ID)), fallbackContext.getRunId()))
                .contextRevision(defaultLong(longValue(state.get(ToolRuntimeContextKeys.CONTEXT_REVISION)), fallbackContext.getContextRevision()))
                .functionCallId(toolContext.functionCallId().orElse(null))
                .traceId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID)), fallbackContext.getTraceId()), TraceContext.currentOrNewTraceId()))
                .build();
    }

    /**
     * 复制兜底上下文；无参数；返回工具调用上下文。
     */
    private ToolInvokeContextEntity copyFallbackContext() {
        return ToolInvokeContextEntity.builder()
                .tenantId(fallbackContext.getTenantId())
                .userId(fallbackContext.getUserId())
                .sessionId(fallbackContext.getSessionId())
                .workflowId(fallbackContext.getWorkflowId())
                .invocationId(fallbackContext.getInvocationId())
                .runId(fallbackContext.getRunId())
                .contextRevision(fallbackContext.getContextRevision())
                .functionCallId(fallbackContext.getFunctionCallId())
                .traceId(defaultString(fallbackContext.getTraceId(), TraceContext.currentOrNewTraceId()))
                .build();
    }

    /**
     * 构建函数声明；参数是名称、描述和工具目录；返回函数声明。
     */
    private static FunctionDeclaration buildDeclaration(String name, String description, ToolCatalogEntity tool) {
        return FunctionDeclaration.builder()
                .name(name)
                .description(description)
                .parameters(parameterSchema(tool))
                .build();
    }

    /**
     * 构建入参 Schema；参数是工具目录；返回 JSON Schema。
     */
    private static Schema parameterSchema(ToolCatalogEntity tool) {
        Map<String, Schema> properties = new LinkedHashMap<>();
        if (ToolType.SKILL.equals(tool.getToolType())) {
            properties.put("task", Schema.builder()
                    .type(Type.Known.STRING)
                    .description("本次希望 Skill 帮助完成的任务，可以为空。")
                    .build());
            return Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(properties)
                    .build();
        }
        properties.put("argumentsJson", Schema.builder()
                .type(Type.Known.STRING)
                .description("传给远程 MCP 具体工具的 JSON 参数文本，例如 {\"origin\":\"北京\",\"destination\":\"南昌\"}。")
                .build());
        properties.put("toolName", Schema.builder()
                .type(Type.Known.STRING)
                .description("远程 MCP 的具体工具名，必须来自当前 MCP 工具清单。")
                .build());
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required(List.of("toolName", "argumentsJson"))
                .build();
    }

    /**
     * 生成工具函数名；参数是工具目录；返回 ADK 可用函数名。
     */
    private static String toolName(ToolCatalogEntity tool) {
        String prefix = ToolType.MCP.equals(tool.getToolType()) ? "mcp_" : "skill_";
        String raw = defaultString(tool.getToolCode(), tool.getToolId());
        String value = (prefix + raw).replaceAll("[^a-zA-Z0-9_]", "_");
        if (!value.matches("^[a-zA-Z_].*")) {
            value = "tool_" + value;
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /**
     * 生成工具描述；参数是工具目录；返回模型可读描述。
     */
    private static String toolDescription(ToolCatalogEntity tool) {
        String typeName = ToolType.MCP.equals(tool.getToolType()) ? "MCP" : "Skill";
        String description = typeName + "：" + defaultString(tool.getToolName(), tool.getToolId()) + "。"
                + defaultString(tool.getDescription(), "当前用户有权限调用的工具。");
        if (ToolType.MCP.equals(tool.getToolType())) {
            description += " 调用时必须提供 toolName 和 argumentsJson。可用远程工具：" + mcpSchemaSummary(tool.getSchemaJson());
        }
        return description.length() > MAX_DESCRIPTION_LENGTH ? description.substring(0, MAX_DESCRIPTION_LENGTH) : description;
    }

    /**
     * 提取 MCP 工具摘要；参数是 Schema JSON；返回模型可读摘要。
     */
    @SuppressWarnings("unchecked")
    private static String mcpSchemaSummary(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return "未测试，暂无远程工具清单。请先在 MCP 中心点击测试。";
        }
        try {
            Map<String, Object> schema = OBJECT_MAPPER.readValue(schemaJson, new TypeReference<>() {
            });
            Object tools = schema.get("tools");
            if (tools instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .map(tool -> {
                            String name = String.valueOf(tool.get("name"));
                            Object description = tool.get("description");
                            return description == null ? name : name + "：" + description;
                        })
                        .collect(Collectors.joining("；"));
            }
        } catch (Exception ignored) {
            return "Schema 解析失败，请重新测试 MCP。";
        }
        return "暂无远程工具清单。";
    }

    /**
     * 转字符串；参数是对象；返回字符串。
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 默认字符串；参数是候选值和默认值；返回非空值。
     */
    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 转长整数；参数是对象；返回可空长整数。
     */
    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 默认长整数；参数是候选值和默认值；返回非空优先值。
     */
    private static Long defaultLong(Long value, Long defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 判断字符串是否为空；参数是字符串；返回是否为空。
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
