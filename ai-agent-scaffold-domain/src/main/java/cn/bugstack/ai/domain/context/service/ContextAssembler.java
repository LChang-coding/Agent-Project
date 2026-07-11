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
        List<ContextFragment> candidates = fragments.stream()
                .sorted(Comparator.comparingInt((ContextFragment fragment) -> fragment.getType().getPriority()).reversed())
                .toList();
        for (ContextFragment fragment : candidates) {
            int tokens = Math.min(tokenCounter.estimate(fragment.getContent()), fragment.getMaxTokens());
            if (tokens <= remaining) {
                result.add(fragment);
                remaining -= tokens;
            }
        }
        return result;
    }
}
