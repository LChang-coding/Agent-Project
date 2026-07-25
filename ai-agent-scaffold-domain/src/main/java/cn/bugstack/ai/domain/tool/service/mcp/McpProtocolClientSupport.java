package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 标准 MCP 统一入口；每次操作创建客户端并在完成后关闭。 */
@Component
public class McpProtocolClientSupport {

    /** 防止远程结果无限占用模型上下文。 */
    private static final int MAX_RESULT_LENGTH = 16_000;

    /** 按小写传输类型索引唯一工厂。 */
    private final Map<String, McpTransportClientFactory> factories;
    /** 序列化工具 schema、结构化结果与参数。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 启动时构建 SSE/Stdio 工厂索引。 */
    public McpProtocolClientSupport(List<McpTransportClientFactory> factories) {
        this.factories = new LinkedHashMap<>();
        for (McpTransportClientFactory factory : factories) {
            if (factory.supports("sse")) {
                this.factories.put("sse", factory);
            }
            if (factory.supports("stdio")) {
                this.factories.put("stdio", factory);
            }
        }
    }

    /**
     * 校验 MCP 连接配置；参数是连接配置；无返回值。
     */
    public void validate(McpConnectionConfigEntity configuration) {
        factory(configuration).validate(configuration);
    }

    /** initialize 后执行 listTools，并返回可冻结到版本的 schema 快照。 */
    public String listToolsSchema(McpVersionEntity version) {
        McpConnectionConfigEntity configuration = fromVersion(version);
        try (McpSyncClient client = factory(configuration).create(configuration)) {
            // 连接或子进程只覆盖本次协议操作。
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("transportType", configuration.getTransportType());
            schema.put("endpoint", configuration.getEndpoint());
            schema.put("tools", toolsResult.tools());
            schema.put("nextCursor", toolsResult.nextCursor());
            return toJson(schema);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(listFailureCode(configuration), "MCP 工具列表获取失败：" + readableMessage(e), e);
        }
    }

    /** initialize 后调用指定远程工具，并将 MCP 错误标志转换为领域异常。 */
    public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new AppException("TOOL_MCP_TOOL_NAME_EMPTY", "MCP 调用必须提供 toolName");
        }
        McpConnectionConfigEntity configuration = fromCatalog(tool);
        try (McpSyncClient client = factory(configuration).create(configuration)) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, safeArguments(arguments)));
            String text = renderResult(result);
            if (Boolean.TRUE.equals(result.isError())) {
                throw new AppException("TOOL_MCP_REMOTE_ERROR", "MCP 远程工具执行失败：" + text);
            }
            return truncate(text);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(callFailureCode(configuration), "MCP 调用失败：" + readableMessage(e), e);
        }
    }

    /**
     * 提取可用工具名；参数是 tools/list 快照 JSON；返回工具名列表。
     */
    public List<String> toolNames(String schemaJson) {
        return tools(schemaJson).stream()
                .map(tool -> stringValue(tool.get("name")))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toList());
    }

    /** 按冻结传输类型选择工厂；未知类型失败关闭。 */
    private McpTransportClientFactory factory(McpConnectionConfigEntity configuration) {
        String transportType = configuration == null || configuration.getTransportType() == null
                ? "" : configuration.getTransportType().toLowerCase();
        McpTransportClientFactory factory = factories.get(transportType);
        if (factory == null) {
            throw new AppException("TOOL_MCP_TRANSPORT_UNSUPPORTED", "MCP 传输类型不支持：" + transportType);
        }
        return factory;
    }

    /**
     * 从 MCP 版本构建连接配置；参数是 MCP 版本；返回连接配置。
     */
    private McpConnectionConfigEntity fromVersion(McpVersionEntity version) {
        if (version == null) {
            throw new AppException("TOOL_MCP_VERSION_NOT_FOUND", "MCP 版本不存在");
        }
        return McpConnectionConfigEntity.builder()
                .transportType(version.getTransportType())
                .endpoint(version.getEndpoint())
                .command(version.getCommand())
                .args(version.getArgs())
                .env(version.getEnv())
                .build();
    }

    /**
     * 从工具目录构建连接配置；参数是工具目录；返回连接配置。
     */
    private McpConnectionConfigEntity fromCatalog(ToolCatalogEntity tool) {
        if (tool == null) {
            throw new AppException("TOOL_NOT_FOUND", "工具不存在");
        }
        return McpConnectionConfigEntity.builder()
                .transportType(tool.getTransportType())
                .endpoint(tool.getEndpoint())
                .command(tool.getCommand())
                .args(tool.getArgs())
                .env(tool.getEnv())
                .build();
    }

    /** 拼接结构化和文本内容；无可识别内容时使用协议对象文本。 */
    private String renderResult(McpSchema.CallToolResult result) {
        List<String> parts = new ArrayList<>();
        if (result.structuredContent() != null) {
            parts.add(toJson(result.structuredContent()));
        }
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    parts.add(textContent.text());
                } else {
                    parts.add(toJson(content));
                }
            }
        }
        return parts.isEmpty() ? result.toString() : parts.stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 解析工具列表；参数是 tools/list 快照 JSON；返回工具 Map 列表。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tools(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<>() {
            });
            Object tools = schema.get("tools");
            if (tools instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    /**
     * 返回安全参数；参数是候选参数；返回非空 Map。
     */
    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        return arguments == null ? new LinkedHashMap<>() : arguments;
    }

    /**
     * 生成工具列表失败编码；参数是连接配置；返回错误编码。
     */
    private String listFailureCode(McpConnectionConfigEntity configuration) {
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_LIST_FAILED" : "TOOL_MCP_SSE_LIST_FAILED";
    }

    /**
     * 生成工具调用失败编码；参数是连接配置；返回错误编码。
     */
    private String callFailureCode(McpConnectionConfigEntity configuration) {
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_CALL_FAILED" : "TOOL_MCP_SSE_CALL_FAILED";
    }

    /**
     * 转 JSON；参数是对象；返回 JSON 文本。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 读取异常消息；参数是异常；返回可读文本。
     */
    private String readableMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 转字符串；参数是对象；返回字符串。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 截断文本；参数是原文；返回限制长度后的文本。
     */
    private String truncate(String value) {
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESULT_LENGTH);
    }
}
