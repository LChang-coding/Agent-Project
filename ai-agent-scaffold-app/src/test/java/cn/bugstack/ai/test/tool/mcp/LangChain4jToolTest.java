package cn.bugstack.ai.test.tool.mcp;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * LangChain4j
 * <p>
 * 文档：<a href="https://docs.langchain4j.info/">langchain4j</a>
 * 案例：<a href="https://github.com/langchain4j/langchain4j-examples">langchain4j-examples</a>
 *
 * 2025/12/14 09:20
 */
@Slf4j
public class LangChain4jToolTest {

    interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://apis.itedus.cn/v1")
                .apiKey("sk-wtBOjyNviG9NtbYn7f2fF8A2203048Aa86Be6f0f0b824dB9")
                .modelName("gpt-4o-mini")
                .build();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(sseMcpClient())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String answer = assistant.chat("你哪有哪些工具能力");
        log.info("测试结果:{}", answer);
    }

    /**
     * 百度搜索MCP服务(url)；https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388
     * 百度搜索MCP服务(key)；https://console.bce.baidu.com/iam/?_=1753597622044#/iam/apikey/list
     */
    public static McpSyncClient sseMcpClient() {

        // 自己申请 api_key
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com/v2/ai_search/mcp/")
                .sseEndpoint("sse?api_key=bce-v3/ALTAK-JFZXXLpfxhAutDQvJ32Ei/4492c1879b8c2f0df4612ef5b4a52df1c1fba9f7")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(360)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

}
