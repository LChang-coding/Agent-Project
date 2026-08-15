package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** DeepSeek 兼容端点：启用 thinking，并完整回传 reasoning_content。 */
public final class DeepSeekReasoningAdapter extends AbstractOpenAiCompatibleReasoningAdapter {
    @Override public String provider() { return "deepseek"; }

    @Override
    protected void configure(ObjectNode request, ReasoningMode mode) {
        ObjectNode thinking = request.putObject("thinking");
        thinking.put("type", mode == ReasoningMode.DISABLED ? "disabled" : "enabled");
        if (mode == ReasoningMode.MEDIUM) request.put("reasoning_effort", "medium");
    }
}
