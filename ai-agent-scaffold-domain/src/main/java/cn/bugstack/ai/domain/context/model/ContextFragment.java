package cn.bugstack.ai.domain.context.model;

import lombok.Getter;

/**
 * 可注入模型请求的上下文片段。
 */
@Getter
public final class ContextFragment {

    private final ContextFragmentType type;
    private final String content;
    private final int maxTokens;

    private ContextFragment(ContextFragmentType type, String content, int maxTokens) {
        this.type = type;
        this.content = content;
        this.maxTokens = maxTokens;
    }

    /**
     * 创建上下文片段；参数是类型、内容和片段预算；返回上下文片段。
     */
    public static ContextFragment of(ContextFragmentType type, String content, int maxTokens) {
        if (type == null || content == null || content.isBlank() || maxTokens < 0) {
            throw new IllegalArgumentException("上下文片段参数非法");
        }
        return new ContextFragment(type, content, maxTokens);
    }
}
