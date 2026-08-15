package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.DeepSeekReasoningAdapter;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.GeminiReasoningAdapter;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningAwareMessageConverter;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningEnvelope;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

public class ReasoningModelAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void deepSeekShouldReplayReasoningAlongsideToolCall() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"model":"deepseek-reasoner","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"call-1"}]}]}
                """);
        ((ObjectNode) request.path("messages").get(0))
                .put("content", ReasoningEnvelope.wire("先检查库存", "", ""));

        new DeepSeekReasoningAdapter().prepareRequest(request, ReasoningMode.MEDIUM);

        Assert.assertEquals("enabled", request.path("thinking").path("type").asText());
        Assert.assertEquals("medium", request.path("reasoning_effort").asText());
        Assert.assertEquals("先检查库存", request.path("messages").get(0).path("reasoning_content").asText());
        Assert.assertEquals("", request.path("messages").get(0).path("content").asText());
        Assert.assertEquals("call-1", request.path("messages").get(0).path("tool_calls").get(0).path("id").asText());
    }

    @Test
    public void geminiShouldRoundTripThoughtSignature() throws Exception {
        ObjectNode response = (ObjectNode) objectMapper.readTree("""
                {"choices":[{"delta":{"role":"assistant","reasoning_content":"核对参数", "content":"答案", "thought_signature":"sig-1"}}]}
                """);
        GeminiReasoningAdapter adapter = new GeminiReasoningAdapter();
        adapter.normalizeResponse(response);
        ReasoningEnvelope.Frame frame = ReasoningEnvelope.splitWire(
                response.path("choices").get(0).path("delta").path("content").asText());
        Assert.assertEquals("核对参数", frame.reasoning());
        Assert.assertEquals("sig-1", frame.signature());
        Assert.assertEquals("答案", frame.answer());

        ObjectNode request = (ObjectNode) objectMapper.readTree("{\"messages\":[{\"role\":\"assistant\"}]} ");
        ((ObjectNode) request.path("messages").get(0)).put("content",
                ReasoningEnvelope.wire(frame.reasoning(), frame.signature(), frame.answer()));
        adapter.prepareRequest(request, ReasoningMode.MEDIUM);
        Assert.assertEquals("sig-1", request.path("messages").get(0).path("thought_signature").asText());
        Assert.assertEquals("sig-1", request.path("messages").get(0).path("extra_content").path("google")
                .path("thought_signature").asText());
    }

    @Test
    public void adkConverterShouldKeepThoughtSeparateFromAnswerAndReplayIt() {
        ReasoningAwareMessageConverter converter = new ReasoningAwareMessageConverter(objectMapper);
        AssistantMessage output = AssistantMessage.builder()
                .content(ReasoningEnvelope.wire("分析过程", "sig-x", "最终答案"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "search", "{}"))).build();
        LlmResponse response = converter.toLlmResponse(new ChatResponse(List.of(new Generation(output))), true);
        List<Part> parts = response.content().orElseThrow().parts().orElseThrow();
        Assert.assertTrue(parts.get(0).thought().orElse(false));
        Assert.assertEquals("分析过程", ReasoningEnvelope.splitThought(parts.get(0).text().orElseThrow()).reasoning());
        Assert.assertEquals("最终答案", parts.get(1).text().orElseThrow());
        Assert.assertTrue(parts.stream().anyMatch(part -> part.functionCall().isPresent()));

        Content history = Content.builder().role("model").parts(parts).build();
        LlmRequest request = LlmRequest.builder().model("deepseek-reasoner").contents(List.of(history))
                .tools(Map.of()).build();
        Prompt prompt = converter.toLlmPrompt(request);
        AssistantMessage replay = (AssistantMessage) prompt.getInstructions().get(0);
        ReasoningEnvelope.Frame replayFrame = ReasoningEnvelope.splitWire(replay.getText());
        Assert.assertEquals("分析过程", replayFrame.reasoning());
        Assert.assertEquals("sig-x", replayFrame.signature());
        Assert.assertEquals("最终答案", replayFrame.answer());
        Assert.assertEquals("call-1", replay.getToolCalls().get(0).id());
    }
}
