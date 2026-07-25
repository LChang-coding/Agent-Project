package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.context.model.ContextBudget;
import cn.bugstack.ai.domain.context.model.ContextFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文预算组装器。
 * <p>按片段优先级选择完整片段，避免截断结构化长期记忆。</p>
 */
public class ContextAssembler {

    /** 统一测量候选片段和总预算。 */
    private final TokenCounter tokenCounter;

    /**
     * 创建上下文组装器；参数是 token 计数器；返回组装器实例。
     */
    public ContextAssembler(TokenCounter tokenCounter) {
        if (tokenCounter == null) {
            throw new IllegalArgumentException("TokenCounter 不能为空");
        }
        this.tokenCounter = tokenCounter;
    }

    /**
     * 按预算组装片段；参数是总预算和候选片段；返回保留后的片段列表。
     */
    public List<ContextFragment> assemble(ContextBudget budget, List<ContextFragment> fragments) {
        if (budget == null || fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        int remaining = budget.availableTokens();
        List<ContextFragment> result = new ArrayList<>();
        // 优先级排序保证长期记忆和最近对话先于可重新检索的 RAG。
        List<ContextFragment> candidates = fragments.stream()
                .sorted(Comparator.comparingInt((ContextFragment fragment) -> fragment.getType().getPriority()).reversed())
                .toList();
        for (ContextFragment fragment : candidates) {
            int tokens = tokenCounter.estimate(fragment.getContent());
            // 结构化片段只整段保留或整段丢弃，禁止字符截断破坏引用和摘要结构。
            if (tokens <= fragment.getMaxTokens() && tokens <= remaining) {
                result.add(fragment);
                remaining -= tokens;
            }
        }
        return result;
    }
}
