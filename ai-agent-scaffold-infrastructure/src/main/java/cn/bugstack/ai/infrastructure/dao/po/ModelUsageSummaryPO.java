package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 模型用量聚合持久化对象。
 */
@Data
public class ModelUsageSummaryPO {
    /** 统计范围内模型调用总数。 */
    private Long callCount;
    /** 成功调用数。 */
    private Long successCount;
    /** 失败调用数。 */
    private Long failedCount;
    /** 尚未闭合调用数。 */
    private Long runningCount;
    /** 被取消调用数。 */
    private Long cancelledCount;
    /** 输入 Token 合计。 */
    private Long promptTokens;
    /** 输出候选 Token 合计。 */
    private Long candidateTokens;
    /** 模型报告的总 Token 合计。 */
    private Long totalTokens;
    /** 隐式推理 Token 合计。 */
    private Long thoughtsTokens;
    /** 工具调用提示 Token 合计。 */
    private Long toolUsePromptTokens;
}
