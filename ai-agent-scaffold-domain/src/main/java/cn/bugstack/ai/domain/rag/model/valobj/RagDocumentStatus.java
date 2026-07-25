package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 逻辑文档生命周期状态。
 * <p>从上传、处理进入可检索 READY；失败可重试；删除采用 DELETING 墓碑后关闭为 DELETED。</p>
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
