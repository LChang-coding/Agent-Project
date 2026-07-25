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
    /** 快照所属租户。 */
    private String tenantId;
    /** 快照所属用户。 */
    private String userId;
    /** 快照所属会话。 */
    private String sessionId;
    /** 会话内单调递增记忆版本。 */
    private Integer memoryVersion;
    /** 生成快照时的上下文修订基线。 */
    private Long baseContextRevision;
    /** 摘要覆盖到的最大消息序列。 */
    private Integer coveredToSequence;
    /** 覆盖消息集合摘要，用于检测污染。 */
    private String coverageHash;
    /** 本快照基于的上一记忆版本。 */
    private Integer parentMemoryVersion;
    /** 长期记忆正文。 */
    private String content;
    /** 正文预估 Token 数。 */
    private Integer estimatedTokenCount;
    /** 生成摘要使用的策略版本。 */
    private String policyVersion;
    /** active/superseded/stale 状态。 */
    private String status;
    /** 生成快照的链路 ID。 */
    private String traceId;
}
