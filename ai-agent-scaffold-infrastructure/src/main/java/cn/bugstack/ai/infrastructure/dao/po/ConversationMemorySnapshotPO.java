package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 会话长期记忆持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
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
