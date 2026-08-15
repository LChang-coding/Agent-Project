package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** OpenAI Chat Completions 兼容协议的公共处理：历史回传与响应字段归一化。 */
abstract class AbstractOpenAiCompatibleReasoningAdapter implements ReasoningModelAdapter {

    @Override
    public void prepareRequest(ObjectNode request, ReasoningMode mode) {
        configure(request, mode);
        JsonNode messages = request.path("messages");
        if (!(messages instanceof ArrayNode array)) return;
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode message) || !"assistant".equals(message.path("role").asText())) continue;
            ReasoningEnvelope.Frame frame = ReasoningEnvelope.splitWire(message.path("content").asText(""));
            if (frame.reasoning().isEmpty() && frame.signature().isEmpty()) continue;
            message.put("content", frame.answer());
            writeHistory(message, frame);
        }
    }

    @Override
    public void normalizeResponse(ObjectNode response) {
        JsonNode choices = response.path("choices");
        if (!(choices instanceof ArrayNode array)) return;
        for (JsonNode choiceNode : array) {
            if (!(choiceNode instanceof ObjectNode choice)) continue;
            JsonNode rawMessage = choice.has("delta") ? choice.get("delta") : choice.get("message");
            if (!(rawMessage instanceof ObjectNode message)) continue;
            String reasoning = firstText(message, "reasoning_content", "reasoning", "analysis");
            String signature = readSignature(message);
            if (reasoning.isEmpty() && signature.isEmpty()) continue;
            String answer = message.path("content").isTextual() ? message.path("content").asText() : "";
            message.put("content", ReasoningEnvelope.wire(reasoning, signature, answer));
        }
    }

    protected abstract void configure(ObjectNode request, ReasoningMode mode);

    protected void writeHistory(ObjectNode message, ReasoningEnvelope.Frame frame) {
        message.put("reasoning_content", frame.reasoning());
    }

    protected String readSignature(ObjectNode message) {
        String direct = firstText(message, "thought_signature", "reasoning_signature");
        if (!direct.isEmpty()) return direct;
        return message.path("extra_content").path("google").path("thought_signature").asText("");
    }

    protected String firstText(ObjectNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isEmpty()) return value.asText();
        }
        return "";
    }
}
