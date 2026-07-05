package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP SSE 客户端支持组件。
 * <p>负责建立 MCP SSE 连接、拉取工具列表、调用远程工具，并把结果转换成平台可记录的文本。</p>
 */
@Component
public class McpSseClientSupport {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RESULT_LENGTH = 16_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试 MCP SSE 并拉取工具 Schema；参数是 MCP 版本；返回 tools/list 快照 JSON。
     */
    public String listToolsSchema(McpVersionEntity version) {
        return listToolsSchema(version.getEndpoint());
    }

    /**
     * 测试 MCP SSE 并拉取工具 Schema；参数是 SSE endpoint；返回 tools/list 快照 JSON。
     */
    public String listToolsSchema(String endpoint) {
        try (McpSyncClient client = createClient(endpoint)) {
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("endpoint", endpoint);
            schema.put("tools", toolsResult.tools());
            schema.put("nextCursor", toolsResult.nextCursor());
            return toJson(schema);
        } catch (Exception e) {
            throw new AppException("TOOL_MCP_SSE_LIST_FAILED", "MCP SSE 工具列表获取失败：" + readableMessage(e), e);
        }
    }

    /**
     * 调用 MCP SSE 工具；参数是工具目录、远程工具名和参数；返回远程工具结果文本。
     */
    public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new AppException("TOOL_MCP_TOOL_NAME_EMPTY", "MCP SSE 调用必须提供 toolName");
        }
        try (McpSyncClient client = createClient(tool.getEndpoint())) {
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
            throw new AppException("TOOL_MCP_SSE_CALL_FAILED", "MCP SSE 调用失败：" + readableMessage(e), e);
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
     * 提取工具说明摘要；参数是 tools/list 快照 JSON；返回模型可读摘要。
     */
    public String toolSummary(String schemaJson) {
        List<Map<String, Object>> tools = tools(schemaJson);
        if (tools.isEmpty()) {
            return "当前 MCP 尚未完成测试，未拉取到远程工具清单。";
        }
        return tools.stream()
                .map(tool -> {
                    String name = stringValue(tool.get("name"));
                    String description = stringValue(tool.get("description"));
                    return description == null || description.isBlank() ? name : name + "：" + description;
                })
                .collect(Collectors.joining("；"));
    }

    /**
     * 创建 MCP SSE 客户端；参数是 SSE endpoint；返回已配置客户端。
     */
    private McpSyncClient createClient(String endpoint) {
        EndpointParts parts = endpointParts(endpoint);
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(parts.baseUri())
                .sseEndpoint(parts.sseEndpoint())
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(DEFAULT_TIMEOUT)
                .initializationTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    /**
     * 解析 SSE endpoint；参数是完整 endpoint；返回 baseUri 和 sseEndpoint。
     */
    private EndpointParts endpointParts(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new AppException("TOOL_MCP_ENDPOINT_EMPTY", "MCP endpoint 不能为空");
        }
        URI uri = URI.create(endpoint);
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        StringBuilder sseEndpoint = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/sse" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            sseEndpoint.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null && !uri.getRawFragment().isBlank()) {
            sseEndpoint.append('#').append(uri.getRawFragment());
        }
        return new EndpointParts(baseUri, sseEndpoint.toString());
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
        if (parts.isEmpty()) {
            return result.toString();
        }
        return parts.stream().filter(item -> item != null && !item.isBlank()).collect(Collectors.joining("\n"));
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
     * 返回安全参数；参数是候选参数；返回非空 Map。
     */
    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        return arguments == null ? new LinkedHashMap<>() : arguments;
    }

    /**
     * 转字符串；参数是对象；返回字符串。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 读取异常消息；参数是异常；返回可读文本。
     */
    private String readableMessage(Exception e) {
        if (e instanceof AppException appException && appException.getInfo() != null && !appException.getInfo().isBlank()) {
            return appException.getInfo();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
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

    /**
     * SSE endpoint 拆分结果。
     */
    private record EndpointParts(String baseUri, String sseEndpoint) {
    }
}
