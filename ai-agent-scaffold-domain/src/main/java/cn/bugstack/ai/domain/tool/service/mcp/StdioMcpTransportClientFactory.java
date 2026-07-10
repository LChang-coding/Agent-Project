package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * stdio MCP 传输客户端工厂。
 * <p>负责解析命令、参数和环境变量，并创建通过标准输入输出通信的 MCP 客户端。</p>
 */
@Component
public class StdioMcpTransportClientFactory implements McpTransportClientFactory {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断是否支持 stdio；参数是传输类型；返回是否支持。
     */
    @Override
    public boolean supports(String transportType) {
        return "stdio".equalsIgnoreCase(transportType);
    }

    /**
     * 校验 stdio 配置；参数是连接配置；无返回值。
     */
    @Override
    public void validate(McpConnectionConfigEntity configuration) {
        toServerParameters(configuration);
    }

    /**
     * 创建 stdio MCP 客户端；参数是连接配置；返回同步客户端。
     */
    @Override
    public McpSyncClient create(McpConnectionConfigEntity configuration) {
        ServerParameters parameters = toServerParameters(configuration);
        Duration timeout = Duration.ofSeconds(timeoutSeconds(configuration));
        return McpClient.sync(new StdioClientTransport(parameters, new JacksonMcpJsonMapper(objectMapper)))
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    /**
     * 构建 stdio 进程参数；参数是连接配置；返回 MCP 进程参数。
     */
    private ServerParameters toServerParameters(McpConnectionConfigEntity configuration) {
        if (configuration == null || blank(configuration.getCommand())) {
            throw new AppException("TOOL_MCP_STDIO_COMMAND_EMPTY", "stdio MCP command 不能为空");
        }
        return ServerParameters.builder(configuration.getCommand().trim())
                .args(parseArgs(configuration.getArgs()))
                .env(parseEnv(configuration.getEnv()))
                .build();
    }

    /**
     * 解析 stdio 参数；参数是 JSON 字符串数组；返回命令参数列表。
     */
    private List<String> parseArgs(String args) {
        if (blank(args)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!node.isArray()) {
                throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须是 JSON 字符串数组");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须全部为字符串");
                }
                result.add(item.asText());
            }
            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须是合法 JSON 字符串数组");
        }
    }

    /**
     * 解析 stdio 环境变量；参数是 JSON 字符串对象；返回环境变量表。
     */
    private Map<String, String> parseEnv(String env) {
        if (blank(env)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(env);
            if (!node.isObject()) {
                throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 必须是 JSON 字符串对象");
            }
            Map<String, String> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual()) {
                    throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 的值必须为字符串");
                }
                result.put(field.getKey(), field.getValue().asText());
            }
            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 必须是合法 JSON 字符串对象");
        }
    }

    /**
     * 获取超时秒数；参数是连接配置；返回大于零的超时秒数。
     */
    private int timeoutSeconds(McpConnectionConfigEntity configuration) {
        return configuration == null || configuration.getTimeoutSeconds() == null || configuration.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_SECONDS : configuration.getTimeoutSeconds();
    }

    /**
     * 判断文本是否为空；参数是文本；返回是否为空。
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
