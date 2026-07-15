package cn.bugstack.ai.domain.usage.model;

import lombok.Builder;
import lombok.Data;

/**
 * 模型用量聚合实体。
 */
@Data
@Builder
public class ModelUsageSummaryEntity {
    private Long callCount;
    private Long successCount;
    private Long failedCount;
    private Long runningCount;
    private Long cancelledCount;
    private Long promptTokens;
    private Long candidateTokens;
    private Long totalTokens;
    private Long thoughtsTokens;
    private Long toolUsePromptTokens;
}
