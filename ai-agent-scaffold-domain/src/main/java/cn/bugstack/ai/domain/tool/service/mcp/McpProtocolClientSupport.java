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
 * 统一处理 MCP 工具清单读取和远程工具调用，并屏蔽 SSE、stdio 的连接差异。
 *
 * <p>每次操作独立创建并关闭客户端，避免不同租户或不同调用共享有状态会话。权限、重复调用保护和
 * 数据库记录由工具网关负责，本类只处理 MCP 协议交互和结果转换。</p>
 */
@Component
public class McpProtocolClientSupport {

    /** 返回模型的 MCP 结果上限，防止单次工具输出占满上下文。 */
    private static final int MAX_RESULT_LENGTH = 16_000;

    /** 按小写传输类型查找 MCP 客户端工厂。 */
    private final Map<String, McpTransportClientFactory> factories;
    /** 序列化工具清单和结构化调用结果。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 启动时登记当前支持的 MCP 传输实现。 */
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
     * 在不真正建连的前提下校验一份 MCP 连接配置。
     *
     * <p>先按传输类型选出工厂（类型不支持会立刻失败），再让工厂用自己的规则校验。
     * 用于创建 MCP 时的前置拦截，把错配置挡在入库之前。</p>
     */
    public void validate(McpConnectionConfigEntity configuration) {
        factory(configuration).validate(configuration);
    }

    /**
     * 连接远程 MCP，完成协议初始化并读取工具名称、说明和参数规则。
     *
     * <p>返回内容会随发布版本保存，供运行时向模型声明工具能力。本方法会访问外部服务但不写数据库，
     * 连接或子进程无论成功失败都会在方法结束时关闭。</p>
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
     * 调用已发布目录项对应的远程 MCP 工具。
     *
     * <p>远程明确返回错误时转成异常，让工具网关把数据库记录更新为失败；成功结果转换为文本并限制长度。
     * 本方法会产生外部调用，但不写数据库。</p>
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

    /** 从已保存的工具清单中提取非空工具名；快照无效时返回空列表。 */
    public List<String> toolNames(String schemaJson) {
        return tools(schemaJson).stream()
                .map(tool -> stringValue(tool.get("name")))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toList());
    }

    /** 按传输类型选择客户端工厂；不支持的类型直接失败。 */
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
     * 从 MCP 版本记录里摘出建连需要的那几个字段。
     *
     * <p>用版本（不可变快照）而不是定义（可随时改的草稿），保证测试出来的结论和将来发布运行的行为一致。
     * 版本为空说明上游没查到记录，直接失败而不是带着空配置去建连。</p>
     */
    private McpConnectionConfigEntity fromVersion(McpVersionEntity version) {
        // 版本不存在就没有任何可用参数，立刻失败。
        if (version == null) {
        // 版本查不到通常是数据被删或编号传错，直接失败让上层去核对参数。
            throw new AppException("TOOL_MCP_VERSION_NOT_FOUND", "MCP 版本不存在");
        }
        // 逐字段搬运：传输方式、地址、命令、参数、环境变量，正好覆盖两种传输方式各自需要的项。
        return McpConnectionConfigEntity.builder()
                .transportType(version.getTransportType())
                .endpoint(version.getEndpoint())
                .command(version.getCommand())
                .args(version.getArgs())
                .env(version.getEnv())
                .build();
    }

    /** 从已发布工具目录项读取连接参数，管理页草稿修改不会影响本次调用。 */
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

    /** 把 MCP 的结构化内容、文本和其他内容块统一转换为模型可读文本。 */
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

    /** 解析工具清单；单条格式异常被过滤，整体无法解析时返回空列表。 */
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

    /** 无参数调用使用空 Map，避免把 null 传入 MCP 协议层。 */
    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        return arguments == null ? new LinkedHashMap<>() : arguments;
    }

    /**
     * 给「拉工具清单失败」挑一个能区分接入方式的错误码。
     *
     * <p>分开统计很有必要：SSE 失败通常是网络或地址问题，stdio 失败通常是命令或环境变量问题，
     * 排查方向完全不同，用同一个码会把两类问题混在一起。</p>
     */
    private String listFailureCode(McpConnectionConfigEntity configuration) {
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_LIST_FAILED" : "TOOL_MCP_SSE_LIST_FAILED";
    }

    /**
     * 给「调用远程工具失败」挑一个能区分接入方式的错误码，理由与拉清单失败相同。
     */
    private String callFailureCode(McpConnectionConfigEntity configuration) {
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_CALL_FAILED" : "TOOL_MCP_SSE_CALL_FAILED";
    }

    /** 序列化失败时退回对象的字符串形式，避免结果渲染打断工具调用。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 从异常里取一句能给人看的原因，消息为空时退回异常类名。
     *
     * <p>网络类异常经常没有消息文本，直接拼出来会得到「调用失败：null」这种没信息量的提示；
     * 用类名至少能区分是超时、连接被拒还是解析错误。</p>
     */
    private String readableMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 把快照中的任意值转为字符串，同时保留 null。 */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 裁剪过长结果，避免工具输出占满下一轮模型上下文。 */
    private String truncate(String value) {
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESULT_LENGTH);
    }
}
