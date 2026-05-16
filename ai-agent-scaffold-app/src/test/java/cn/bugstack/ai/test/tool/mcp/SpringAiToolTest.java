package cn.bugstack.ai.test.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * Spring Ai Tool
 *
 * 2025/12/14 09:51
 */
@Slf4j
public class SpringAiToolTest {

    public static void main(String[] args) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://apis.itedus.cn")
                .apiKey("sk-efen7WX8Q8vGvBps3f7c9a34578d41BbBc508dC5Df33A9Fb")
                .completionsPath("v1/chat/completions")
                .embeddingsPath("v1/embeddings")
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .toolCallbacks(SyncMcpToolCallbackProvider.builder()
                                .mcpClients(sseMcpClient()).build()
                                .getToolCallbacks())
                        .build())
                .build();

        String call = chatModel.call("你哪有哪些工具能力");

        log.info("测试结果:{}", call);
    }

    /**
     * 百度搜索MCP服务(url)；https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388
     * 百度搜索MCP服务(key)；https://console.bce.baidu.com/iam/?_=1753597622044#/iam/apikey/list
     */
    public static McpSyncClient sseMcpClient() {

        // 自己申请 api_key
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com")
                .sseEndpoint("/v2/ai_search/mcp/sse?api_key=bce-v3/ALTAK-JFZXXLpfxhAutDQvJ32Ei/4492c1879b8c2f0df4612ef5b4a52df1c1fba9f7")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(360)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

    @Test
    public void test_url() throws MalformedURLException {
        String fullUrl = "http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=bce-v3/ALTAK-JFZXXLpfxhAutDQvJ32Ei/4492c1879b8c2f0df4612ef5b4a52df1c1fba9f7";

        fullUrl = "http://127.0.0.1:9999/sse?apiKey=xxxx";

        URL url = new URL(fullUrl);

        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();

        String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;
        String endpoint = "";

        int index = fullUrl.indexOf(baseUrl);
        if (index != -1) {
            endpoint = fullUrl.substring(index + baseUrl.length());
        }

        log.info("baseUrl:{}", baseUrl);
        log.info("endpoint:{}", endpoint);
    }

}
