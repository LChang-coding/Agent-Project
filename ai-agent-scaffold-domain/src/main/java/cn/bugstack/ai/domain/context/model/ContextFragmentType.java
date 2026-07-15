package cn.bugstack.ai.domain.context.model;

/**
 * 上下文片段类型。
 * <p>优先级决定预算不足时的保留顺序。</p>
 */
public enum ContextFragmentType {

    LONG_TERM_MEMORY(40),
    RECENT_CONVERSATION(30),
    ATTACHMENT(25),
    WORKFLOW_UPSTREAM(20),
    RAG(10);

    private final int priority;

    ContextFragmentType(int priority) {
        this.priority = priority;
    }

    /**
     * 获取预算优先级；无参数；返回数值越大优先级越高。
     */
    public int getPriority() {
        return priority;
    }
}
