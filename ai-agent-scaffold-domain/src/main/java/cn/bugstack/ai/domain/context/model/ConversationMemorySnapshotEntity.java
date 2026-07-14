package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 会话长期记忆快照。
 */
@Data
@Builder
public class ConversationMemorySnapshotEntity {
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer memoryVersion;
    private Long baseContextRevision;
    private Integer coveredToSequence;
    private String coverageHash;
    private Integer parentMemoryVersion;
    private String content;
    private Integer estimatedTokenCount;
    private String policyVersion;
    private String status;
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
