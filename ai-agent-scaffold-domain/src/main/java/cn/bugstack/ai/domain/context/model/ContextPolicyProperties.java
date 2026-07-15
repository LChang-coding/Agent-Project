package cn.bugstack.ai.domain.context.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话上下文策略配置。
 * <p>模型窗口和预算必须来自配置，避免在代码中写死上下文大小。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.context")
public class ContextPolicyProperties {

    private boolean enabled = false;
    private String policyVersion = "v1";
    private int modelWindowTokens = 0;
    private int reserveOutputTokens = 8192;
    private int safetyMarginTokens = 2048;
    private int longTermMemoryTokens = 2048;
    private int recentConversationTokens = 8192;
    private int recentWindowMaxMessages = 100;
    private int upstreamTokens = 4096;
    private int attachmentTokens = 8192;
    private int ragTokens = 0;
    private int compactionMinUncoveredTokens = 12000;
    private int compactionRetainRecentTokens = 4096;
    private int compactionMaxAttempts = 3;
    private int cacheTtlSeconds = 1800;

    /**
     * 计算可注入预算；无参数；返回当前策略预算。
     */
    public ContextBudget toBudget() {
        if (enabled && modelWindowTokens <= 0) {
            throw new IllegalStateException("启用上下文管理时必须配置 ai.context.model-window-tokens");
        }
        int available = Math.max(0, modelWindowTokens - reserveOutputTokens - safetyMarginTokens);
        return new ContextBudget(available, longTermMemoryTokens, recentConversationTokens, attachmentTokens,
                upstreamTokens, ragTokens);
    }
}
