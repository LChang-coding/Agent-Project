package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 会话长期记忆快照。
 */
@Data
@Builder
public class ConversationMemorySnapshotEntity {
    /** 摘要所属租户。 */
    private String tenantId;
    /** 摘要所属用户。 */
    private String userId;
    /** 摘要所属会话。 */
    private String sessionId;
    /** 会话内递增的摘要版本。 */
    private Integer memoryVersion;
    /** 生成摘要时的上下文版本。 */
    private Long baseContextRevision;
    /** 摘要覆盖的最大消息序号。 */
    private Integer coveredToSequence;
    /** 覆盖消息内容摘要，用于取消后验真。 */
    private String coverageHash;
    /** 可回滚的上一摘要版本。 */
    private Integer parentMemoryVersion;
    /** 结构化长期记忆文本。 */
    private String content;
    /** 摘要预估 Token 数。 */
    private Integer estimatedTokenCount;
    /** 生成摘要的策略版本。 */
    private String policyVersion;
    /** pending、active、superseded 或 invalid。 */
    private String status;
    /** 生成摘要的链路标识。 */
    private String traceId;

    /**
     * 返回空摘要版本；无参数；没有有效摘要时版本视为 0。
     */
    public static int versionOf(ConversationMemorySnapshotEntity snapshot) {
        return snapshot == null || snapshot.getMemoryVersion() == null ? 0 : snapshot.getMemoryVersion();
    }

    /**
     * 返回已覆盖序号；无参数；没有有效摘要时返回 0。
     */
    public static int coveredSequenceOf(ConversationMemorySnapshotEntity snapshot) {
        return snapshot == null || snapshot.getCoveredToSequence() == null ? 0 : snapshot.getCoveredToSequence();
    }
}
