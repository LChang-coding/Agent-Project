package cn.bugstack.ai.domain.usage.model;

import lombok.Builder;
import lombok.Data;

/**
 * 模型用量聚合实体。
 */
@Data
@Builder
public class ModelUsageSummaryEntity {
    /** 调用总数。 */
    private Long callCount;
    /** 成功调用数。 */
    private Long successCount;
    /** 失败调用数。 */
    private Long failedCount;
    /** 尚未终态调用数。 */
    private Long runningCount;
    /** 已取消调用数。 */
    private Long cancelledCount;
    /** 输入 Token 总和。 */
    private Long promptTokens;
    /** 输出 Token 总和。 */
    private Long candidateTokens;
    /** 总 Token 总和。 */
    private Long totalTokens;
    /** 推理思考 Token 总和。 */
    private Long thoughtsTokens;
    /** 工具提示 Token 总和。 */
    private Long toolUsePromptTokens;
}
