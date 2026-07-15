package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 模型用量聚合持久化对象。
 */
@Data
public class ModelUsageSummaryPO {
    private Long callCount;
    private Long successCount;
    private Long failedCount;
    private Long promptTokens;
    private Long candidateTokens;
    private Long totalTokens;
    private Long thoughtsTokens;
    private Long toolUsePromptTokens;
}
