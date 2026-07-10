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

/**
 * MCP 协议客户端支持组件。
 * <p>负责复用 initialize、tools/list、tools/call 和结果转换流程，并按传输类型选择客户端工厂。</p>
 */
@Component
public class McpProtocolClientSupport {

    private static final int MAX_RESULT_LENGTH = 16_000;

    private final Map<String, McpTransportClientFactory> factories;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 MCP 协议客户端支持组件；参数是传输客户端工厂列表；返回组件实例。
     */
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

    /**
     * 拉取 MCP 工具 Schema；参数是 MCP 版本；返回 tools/list 快照 JSON。
     */
    public String listToolsSchema(McpVersionEntity version) {
        McpConnectionConfigEntity configuration = fromVersion(version);
        try (McpSyncClient client = factory(configuration).create(configuration)) {
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

    /**
     * 调用 MCP 工具；参数是工具目录、远程工具名和参数；返回远程结果文本。
     */
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

    /**
     * 读取传输客户端工厂；参数是连接配置；返回匹配工厂。
     */
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

    /**
     * 渲染 MCP 调用结果；参数是调用结果；返回文本。
     */
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
