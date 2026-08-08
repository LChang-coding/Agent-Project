package cn.bugstack.ai.domain.context.model;

/**
 * 上下文片段类型。
 * <p>优先级决定预算不足时的保留顺序。</p>
 */
public enum ContextFragmentType {

    /** 已验证长期摘要，预算不足时最优先保留。 */
    LONG_TERM_MEMORY(40),
    /** 摘要之后的有效消息。 */
    RECENT_CONVERSATION(30),
    /** 用户在可见消息中引用的附件内容。 */
    ATTACHMENT(25),
    /** 工作流上一节点输出。 */
    WORKFLOW_UPSTREAM(20),
    /** 可通过引用回查的外部知识。 */
    RAG(10);

    /** 数值越大越先占用总预算。 */
    private final int priority;

    /** 固化枚举预算优先级。 */
    ContextFragmentType(int priority) {
        this.priority = priority;
    }

    /**
     * 获取预算优先级；返回数值越大优先级越高。
     */
    public int getPriority() {
        return priority;
    }
}
