package cn.bugstack.ai.domain.context.model;

import lombok.Getter;

/**
 * 可注入模型请求的上下文片段。
 */
@Getter
public final class ContextFragment {

    /** 片段类别与预算优先级。 */
    private final ContextFragmentType type;
    /** 完整候选文本。 */
    private final String content;
    /** 此类别允许占用的 Token 上限。 */
    private final int maxTokens;
    /** RAG 类别专属的引用证据。 */
    private final RagContextEvidence ragEvidence;

    /** 仅允许工厂方法创建经过约束校验的不可变片段。 */
    private ContextFragment(ContextFragmentType type, String content, int maxTokens, RagContextEvidence ragEvidence) {
        this.type = type;
        this.content = content;
        this.maxTokens = maxTokens;
        this.ragEvidence = ragEvidence;
    }

    /**
     * 创建上下文片段；参数是类型、内容和片段预算；返回上下文片段。
     */
    public static ContextFragment of(ContextFragmentType type, String content, int maxTokens) {
        if (type == null || content == null || content.isBlank() || maxTokens < 0) {
            throw new IllegalArgumentException("上下文片段参数非法");
        }
        return new ContextFragment(type, content, maxTokens, null);
    }

    /** 创建带结构化 RAG 证据的上下文片段。 */
    public static ContextFragment of(ContextFragmentType type, String content, int maxTokens,
                                     RagContextEvidence ragEvidence) {
        if (type == null || content == null || content.isBlank() || maxTokens < 0) {
            throw new IllegalArgumentException("上下文片段参数非法");
        }
        if (ragEvidence != null && type != ContextFragmentType.RAG) {
            throw new IllegalArgumentException("仅RAG片段允许携带RAG证据");
        }
        return new ContextFragment(type, content, maxTokens, ragEvidence);
    }
}
