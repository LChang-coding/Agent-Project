package cn.bugstack.ai.domain.rag.model.document;

/**
 * 文档解析质量处置结果。
 * <p>READY 可直接索引；READY_WITH_WARNING 保留告警后索引；NEEDS_REVIEW 等待人工复核；
 * REJECTED 禁止写入检索索引。</p>
 */
public enum DocumentQualityDisposition {
    READY,
    READY_WITH_WARNING,
    NEEDS_REVIEW,
    REJECTED
}
