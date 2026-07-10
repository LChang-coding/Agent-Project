package cn.bugstack.ai.test.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * stdio MCP 测试服务。
 * <p>模拟 initialize、tools/list 和 tools/call 协议响应，用于验证平台的真实子进程通信。</p>
 */
public final class FakeStdioMcpServer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FakeStdioMcpServer() {
    }

    /**
     * 启动测试 MCP 服务；参数是命令行参数；无返回值。
     */
    public static void main(String[] args) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String requestLine;
            while ((requestLine = reader.readLine()) != null) {
                JsonNode request = OBJECT_MAPPER.readTree(requestLine);
                if (!request.hasNonNull("id")) {
                    continue;
                }
                writeResponse(writer, request, responseResult(request));
            }
        }
    }

    /**
     * 根据请求创建协议结果；参数是 JSON-RPC 请求；返回结果节点。
     */
    private static JsonNode responseResult(JsonNode request) {
        String method = request.path("method").asText();
        return switch (method) {
            case "initialize" -> initializeResult();
            case "tools/list" -> listToolsResult();
            case "tools/call" -> callToolResult(request.path("params").path("arguments").path("content").asText(""));
            default -> errorResult("不支持的测试方法：" + method);
        };
    }

    /**
     * 创建 initialize 响应；无参数；返回协议初始化结果。
     */
    private static ObjectNode initializeResult() {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "fake-stdio-mcp").put("version", "1.0.0");
        return result;
    }

    /**
     * 创建 tools/list 响应；无参数；返回一个 echo 工具定义。
     */
    private static ObjectNode listToolsResult() {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "echo");
        tool.put("description", "返回输入文本");
        ObjectNode inputSchema = tool.putObject("inputSchema");
        inputSchema.put("type", "object");
        inputSchema.putObject("properties").putObject("content").put("type", "string");
        return result;
    }

    /**
     * 创建 tools/call 响应；参数是输入文本；返回 echo 文本结果。
     */
    private static ObjectNode callToolResult(String content) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        ArrayNode responseContent = result.putArray("content");
        responseContent.addObject().put("type", "text").put("text", content);
        result.put("isError", false);
        return result;
    }

    /**
     * 创建未知方法结果；参数是错误文本；返回错误结果。
     */
    private static ObjectNode errorResult(String message) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("isError", true);
        result.putArray("content").addObject().put("type", "text").put("text", message);
        return result;
    }

    /**
     * 输出 JSON-RPC 响应；参数是输出流、请求和结果；无返回值。
     */
    private static void writeResponse(BufferedWriter writer, JsonNode request, JsonNode result) throws IOException {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        writer.write(OBJECT_MAPPER.writeValueAsString(response));
        writer.newLine();
        writer.flush();
    }
}
