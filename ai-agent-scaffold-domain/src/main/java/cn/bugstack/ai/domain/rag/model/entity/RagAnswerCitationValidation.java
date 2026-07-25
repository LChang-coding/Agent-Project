package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;

import java.util.List;

/** 最终回答引用校验结果。 */
public record RagAnswerCitationValidation(Status status,
                                          List<String> retrievalIds,
                                          List<String> allowedCitationIds,
                                          List<String> usedCitationIds,
                                          List<String> invalidCitationIds,
                                          List<RagContextEvidence.CitationReference> usedCitations) {

    public RagAnswerCitationValidation {
        retrievalIds = copy(retrievalIds);
        allowedCitationIds = copy(allowedCitationIds);
        usedCitationIds = copy(usedCitationIds);
        invalidCitationIds = copy(invalidCitationIds);
        usedCitations = usedCitations == null ? List.of() : List.copyOf(usedCitations);
        if (status == null) throw new IllegalArgumentException("引用校验状态不能为空");
    }

    /** 将可空列表转换为不可变空列表或防御副本。 */
    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 引用校验状态。
     * <p>依次表示未使用 RAG、存在证据但回答未引用、引用全部合法、出现伪造或越界引用。</p>
     */
    public enum Status {
        NO_RAG,
        RAG_AVAILABLE_UNUSED,
        VALID,
        INVALID_CITATIONS
    }
}
