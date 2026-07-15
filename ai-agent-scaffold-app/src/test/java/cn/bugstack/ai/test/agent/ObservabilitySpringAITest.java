package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.service.armory.matter.model.ObservabilitySpringAI;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Spring AI 可观测适配器测试。
 */
public class ObservabilitySpringAITest {

    /**
     * 校验流完成时补发唯一终态；无参数；验证模型用量插件能够观察到 partial=false。
     */
    @Test
    public void shouldEmitTerminalResponseAfterStreamingChunks() {
        ChatModel chatModel = Mockito.mock(ChatModel.class,
                Mockito.withSettings().extraInterfaces(StreamingChatModel.class));
        StreamingChatModel streamingModel = (StreamingChatModel) chatModel;
        Mockito.when(streamingModel.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(
                response("你"), response("好")));
        LlmRequest request = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试"))).build()))
                .build();

        List<LlmResponse> responses = new ObservabilitySpringAI(chatModel, "test-model")
                .generateContent(request, true).toList().blockingGet();

        Assert.assertEquals(3, responses.size());
        Assert.assertTrue(responses.get(0).partial().orElse(false));
        Assert.assertTrue(responses.get(1).partial().orElse(false));
        Assert.assertFalse(responses.get(2).partial().orElse(true));
        Assert.assertTrue(responses.get(2).turnComplete().orElse(false));
        Assert.assertEquals("你好", responses.get(2).content().orElseThrow().text());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
