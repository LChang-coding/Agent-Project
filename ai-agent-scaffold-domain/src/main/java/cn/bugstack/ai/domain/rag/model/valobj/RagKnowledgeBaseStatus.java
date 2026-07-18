package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库生命周期状态。
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
