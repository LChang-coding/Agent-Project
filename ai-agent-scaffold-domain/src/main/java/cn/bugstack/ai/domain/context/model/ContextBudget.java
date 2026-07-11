package cn.bugstack.ai.domain.context.model;

/**
 * 上下文可注入预算。
 * <p>所有字段均以 token 为单位。</p>
 */
public record ContextBudget(int availableTokens,
                            int longTermMemoryTokens,
                            int recentConversationTokens,
                            int upstreamTokens,
                            int ragTokens) {

    /**
     * 校验预算；无参数；非法预算抛出异常。
     */
    public ContextBudget {
        if (availableTokens < 0 || longTermMemoryTokens < 0
                || recentConversationTokens < 0 || upstreamTokens < 0 || ragTokens < 0) {
            throw new IllegalArgumentException("上下文预算不能为负数");
        }
    }
}
