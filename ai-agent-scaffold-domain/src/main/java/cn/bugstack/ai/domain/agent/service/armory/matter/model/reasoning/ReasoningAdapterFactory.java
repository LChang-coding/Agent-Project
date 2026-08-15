package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import java.util.Locale;

/** 只在装配期选择一次供应商适配器，运行期不再散落模型判断。 */
public final class ReasoningAdapterFactory {
    private ReasoningAdapterFactory() {
    }

    public static ReasoningModelAdapter resolve(String baseUrl, String model, ReasoningMode mode) {
        String identity = ((baseUrl == null ? "" : baseUrl) + ' ' + (model == null ? "" : model)).toLowerCase(Locale.ROOT);
        if (identity.contains("gemini") || identity.contains("generativelanguage.googleapis")) return new GeminiReasoningAdapter();
        if (identity.contains("deepseek")) return new DeepSeekReasoningAdapter();
        if (identity.contains("openai") || identity.matches(".*(?:^|[\\s/])(o1|o3|o4|gpt-5).*")) return new OpenAiReasoningAdapter();
        // 未知 OpenAI-compatible 端点不得冒充 DeepSeek 发送 thinking 私有字段；
        // 先用标准 reasoning_effort，400 时再由 API 边界退回纯标准请求。
        return mode == ReasoningMode.DISABLED ? new NoopReasoningAdapter() : new OpenAiReasoningAdapter();
    }
}
