package cn.bugstack.ai.domain.context.model;

/**
 * 上下文可注入预算。
 * <p>所有字段均以 token 为单位。</p>
 *
 * @param availableTokens 模型窗口扣除输出预留和安全余量后的总预算
 * @param longTermMemoryTokens 长期摘要上限
 * @param recentConversationTokens 最近对话上限
 * @param attachmentTokens 附件文本上限
 * @param upstreamTokens 工作流上游输出上限
 * @param ragTokens RAG 证据上限
 */
public record ContextBudget(int availableTokens,
                            int longTermMemoryTokens,
                            int recentConversationTokens,
                            int attachmentTokens,
                            int upstreamTokens,
                            int ragTokens) {

    /**
     * 校验预算；无参数；非法预算抛出异常。
     */
    public ContextBudget {
        if (availableTokens < 0 || longTermMemoryTokens < 0
                || recentConversationTokens < 0 || attachmentTokens < 0 || upstreamTokens < 0 || ragTokens < 0) {
            throw new IllegalArgumentException("上下文预算不能为负数");
        }
    }

    /** 兼容无附件预算的旧调用。 */
    public ContextBudget(int availableTokens, int longTermMemoryTokens, int recentConversationTokens,
                         int upstreamTokens, int ragTokens) {
        this(availableTokens, longTermMemoryTokens, recentConversationTokens, 0, upstreamTokens, ragTokens);
    }
}
