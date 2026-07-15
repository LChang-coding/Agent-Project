package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 会话上下文洞察实体。
 */
@Data
@Builder
public class ContextInsightEntity {
    private String sessionId;
    private Long contextRevision;
    private Integer modelWindowTokens;
    private Integer effectiveTokens;
    private Double utilization;
    private Integer systemTokens;
    private Integer historyTokens;
    private Integer summaryTokens;
    private Integer toolResultTokens;
    private Integer attachmentTokens;
    private Integer ragTokens;
    private Integer upstreamTokens;
    private Integer effectiveFromSequence;
    private Integer effectiveToSequence;
    private Integer memoryVersion;
    private String compactionStatus;
    private Integer toolCount;
    private Integer callCount;
    private Integer attachmentCount;
    private String trimReason;
}
