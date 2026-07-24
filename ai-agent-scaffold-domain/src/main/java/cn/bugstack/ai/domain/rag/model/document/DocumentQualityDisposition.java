package cn.bugstack.ai.domain.rag.model.document;

/**
 * 文档解析质量处置结果。
 */
public enum DocumentQualityDisposition {
    READY,
    READY_WITH_WARNING,
    NEEDS_REVIEW,
    REJECTED
}
