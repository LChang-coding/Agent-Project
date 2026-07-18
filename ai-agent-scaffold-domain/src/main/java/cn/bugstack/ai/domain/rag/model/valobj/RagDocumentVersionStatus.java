package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 不可变文档版本的处理状态。
 */
public enum RagDocumentVersionStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    READY,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    DELETING,
    DELETED;

    /** 判断版本是否已经结束处理。 */
    public boolean terminal() {
        return this == READY || this == FAILED || this == CANCELLED || this == SUPERSEDED || this == DELETED;
    }
}
