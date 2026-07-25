package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 会话上下文洞察实体。
 */
@Data
@Builder
public class ContextInsightEntity {
    /** 被统计会话。 */
    private String sessionId;
    /** 当前有效上下文版本。 */
    private Long contextRevision;
    /** 配置的模型窗口上限。 */
    private Integer modelWindowTokens;
    /** 当前真实组装结果加系统提示的 Token 数。 */
    private Integer effectiveTokens;
    /** 有效 Token 占模型窗口比例。 */
    private Double utilization;
    /** Agent 系统指令预估 Token。 */
    private Integer systemTokens;
    /** 短期有效历史 Token。 */
    private Integer historyTokens;
    /** 长期摘要 Token。 */
    private Integer summaryTokens;
    /** 已注入工具结果 Token；当前链路未单独统计时为零。 */
    private Integer toolResultTokens;
    /** 附件上下文 Token。 */
    private Integer attachmentTokens;
    /** RAG 上下文 Token。 */
    private Integer ragTokens;
    /** 工作流上游输出 Token。 */
    private Integer upstreamTokens;
    /** 实际历史窗口起始序号。 */
    private Integer effectiveFromSequence;
    /** 实际历史窗口结束序号。 */
    private Integer effectiveToSequence;
    /** 当前长期摘要版本。 */
    private Integer memoryVersion;
    /** 最近压缩任务状态或 idle。 */
    private String compactionStatus;
    /** 会话使用过的不同工具数。 */
    private Integer toolCount;
    /** 会话累计工具调用次数。 */
    private Integer callCount;
    /** 当前上下文窗口可见附件数。 */
    private Integer attachmentCount;
    /** 预算裁剪原因。 */
    private String trimReason;
}
