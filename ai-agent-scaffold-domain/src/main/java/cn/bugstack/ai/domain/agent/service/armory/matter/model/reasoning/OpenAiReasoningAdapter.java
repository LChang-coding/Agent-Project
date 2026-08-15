package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** OpenAI 兼容推理模型：统一映射 reasoning_effort，并兼容代理返回的 reasoning_content。 */
public final class OpenAiReasoningAdapter extends AbstractOpenAiCompatibleReasoningAdapter {
    @Override public String provider() { return "openai"; }

    @Override
    protected void configure(ObjectNode request, ReasoningMode mode) {
        if (mode == ReasoningMode.DISABLED) request.remove("reasoning_effort");
        else request.put("reasoning_effort", mode == ReasoningMode.SUMMARY ? "low" : "medium");
    }
}
