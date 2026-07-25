package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 上下文组装结果。
 * <p>包含可注入模型请求的文本和观测字段。</p>
 */
@Data
@Builder
public class ContextAssemblyResult {

    /** 最终注入模型的上下文指令文本。 */
    private String instruction;
    /** 全部注入片段的预估 Token 总数。 */
    private Integer estimatedTokenCount;
    /** 本次采用的长期摘要版本。 */
    private Integer memoryVersion;
    /** 长期摘要已覆盖的最大消息序号。 */
    private Integer coveredToSequence;
    /** 长期摘要是否命中缓存。 */
    private Boolean cacheHit;
    /** 因预算丢弃片段时的原因。 */
    private String trimReason;
    /** 实际注入的摘要 Token 数。 */
    private Integer summaryTokens;
    /** 实际注入的短期历史 Token 数。 */
    private Integer historyTokens;
    /** 实际注入的上游输出 Token 数。 */
    private Integer upstreamTokens;
    /** 实际注入的附件 Token 数。 */
    private Integer attachmentTokens;
    /** 实际注入的 RAG Token 数。 */
    private Integer ragTokens;
    /** 实际历史窗口起始序号。 */
    private Integer effectiveFromSequence;
    /** 实际历史窗口结束序号。 */
    private Integer effectiveToSequence;
    /** 与实际注入 RAG 文本严格对应的引用证据。 */
    private List<RagContextEvidence> ragEvidence;
}
