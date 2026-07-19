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

    private ContextFragmentType type;
    private String content;
    private Integer estimatedTokenCount;
    private String source;
    private RagContextEvidence ragEvidence;
}
