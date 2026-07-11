package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话长期记忆持久化对象。
 */
@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class ConversationMemorySnapshotPO extends BasePO {
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer memoryVersion;
    private Integer coveredToSequence;
    private String content;
    private Integer estimatedTokenCount;
    private String policyVersion;
    private String status;
    private String traceId;
}
