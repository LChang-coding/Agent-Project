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

    /** 上下文管理总开关。 */
    private boolean enabled = false;
    /** 参与压缩任务幂等与摘要审计的策略版本。 */
    private String policyVersion = "v1";
    /** 当前模型最大上下文窗口。 */
    private int modelWindowTokens = 0;
    /** 为模型回答预留的 Token。 */
    private int reserveOutputTokens = 8192;
    /** 防止计数误差与模型差异的安全余量。 */
    private int safetyMarginTokens = 2048;
    /** 长期摘要单类预算。 */
    private int longTermMemoryTokens = 2048;
    /** 摘要后短期对话单类预算。 */
    private int recentConversationTokens = 8192;
    /** Redis 短期窗口最多保存消息数。 */
    private int recentWindowMaxMessages = 100;
    /** 工作流上游输出单类预算。 */
    private int upstreamTokens = 4096;
    /** 附件内容单类预算。 */
    private int attachmentTokens = 8192;
    /** 单次组装最多扫描的附件数。 */
    private int attachmentCandidateLimit = 32;
    /** 单个附件最多读取的字符数。 */
    private int attachmentMaxContentChars = 131072;
    /** RAG 证据单类预算。 */
    private int ragTokens = 0;
    /** 未摘要消息达到该 Token 数时触发压缩。 */
    private int compactionMinUncoveredTokens = 12000;
    /** 压缩后保留在短期窗口的最近 Token 数。 */
    private int compactionRetainRecentTokens = 4096;
    /** 压缩任务进入死信前最大尝试次数。 */
    private int compactionMaxAttempts = 3;
    /** 摘要与短期窗口缓存有效秒数。 */
    private int cacheTtlSeconds = 1800;

    /**
     * 计算可注入预算；返回当前策略预算。
     */
    public ContextBudget toBudget() {
        if (enabled && modelWindowTokens <= 0) {
            throw new IllegalStateException("启用上下文管理时必须配置 ai.context.model-window-tokens");
        }
        // 输出预留和安全余量先从总窗口扣除，所有贡献方共享剩余硬上限。
        int available = Math.max(0, modelWindowTokens - reserveOutputTokens - safetyMarginTokens);
        return new ContextBudget(available, longTermMemoryTokens, recentConversationTokens, attachmentTokens,
                upstreamTokens, ragTokens);
    }
}
