package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 外部上下文贡献片段。
 * <p>RAG 等扩展能力通过该对象向组装器提供可注入内容。</p>
 */
@Data
@Builder
public class ContextContribution {

    /** 贡献片段类型，决定预算优先级。 */
    private ContextFragmentType type;
    /** 候选注入文本。 */
    private String content;
    /** 贡献方预估的 Token 数。 */
    private Integer estimatedTokenCount;
    /** 贡献方或数据来源。 */
    private String source;
    /** RAG 片段对应的结构化引用证据。 */
    private RagContextEvidence ragEvidence;
}
