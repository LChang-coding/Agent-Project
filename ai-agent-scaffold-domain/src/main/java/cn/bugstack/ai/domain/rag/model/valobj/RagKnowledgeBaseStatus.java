package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库生命周期状态。
 * <p>ACTIVE 可检索，DISABLED 人工停用，INDEXING 构建新代引，DELETING/DELETED 建立删除屏障。</p>
 */
public enum RagKnowledgeBaseStatus {
    ACTIVE,
    DISABLED,
    INDEXING,
    DELETING,
    DELETED;

    /** 判断知识库是否允许在线检索。 */
    public boolean searchable() {
        return this == ACTIVE;
    }
}
