package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 不支持推理扩展字段的兼容端点，显式禁用后继续提供普通流式回答。 */
public final class NoopReasoningAdapter extends AbstractOpenAiCompatibleReasoningAdapter {
    @Override public String provider() { return "compatible"; }

    @Override
    protected void configure(ObjectNode request, ReasoningMode mode) {
        request.remove("thinking");
        request.remove("reasoning_effort");
    }
}
