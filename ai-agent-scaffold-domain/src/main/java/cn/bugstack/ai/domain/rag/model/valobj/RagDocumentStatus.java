package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 逻辑文档生命周期状态。
 */
public enum RagDocumentStatus {
    UPLOADING,
    PROCESSING,
    READY,
    FAILED,
    DELETING,
    DELETED;

    /** 判断文档是否允许参与检索。 */
    public boolean searchable() {
        return this == READY;
    }
}
