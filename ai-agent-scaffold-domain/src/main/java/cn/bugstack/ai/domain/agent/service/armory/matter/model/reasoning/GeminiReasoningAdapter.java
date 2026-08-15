package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Gemini OpenAI 兼容端点：保留 thought signature，避免工具调用后的签名校验失败。 */
public final class GeminiReasoningAdapter extends AbstractOpenAiCompatibleReasoningAdapter {
    @Override public String provider() { return "gemini"; }

    @Override
    protected void configure(ObjectNode request, ReasoningMode mode) {
        request.put("reasoning_effort", mode == ReasoningMode.DISABLED ? "none" : mode == ReasoningMode.SUMMARY ? "low" : "medium");
    }

    @Override
    protected void writeHistory(ObjectNode message, ReasoningEnvelope.Frame frame) {
        super.writeHistory(message, frame);
        if (!frame.signature().isEmpty()) {
            message.put("thought_signature", frame.signature());
            ObjectNode extra = message.has("extra_content") && message.get("extra_content").isObject()
                    ? (ObjectNode) message.get("extra_content") : message.putObject("extra_content");
            ObjectNode google = extra.has("google") && extra.get("google").isObject()
                    ? (ObjectNode) extra.get("google") : extra.putObject("google");
            google.put("thought_signature", frame.signature());
        }
    }
}
