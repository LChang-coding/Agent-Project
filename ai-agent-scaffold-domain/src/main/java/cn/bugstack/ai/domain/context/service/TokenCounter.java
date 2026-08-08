package cn.bugstack.ai.domain.context.service;

/**
 * 模型 token 预估接口。
 */
public interface TokenCounter {

    /**
     * 预估文本 token 数。
     */
    int estimate(String text);
}
