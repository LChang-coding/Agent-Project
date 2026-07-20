package cn.bugstack.ai.domain.rag.model.valobj;

/** 知识库级联删除任务状态。 */
public enum RagKnowledgeBaseDeleteStatus {
    PENDING,
    RUNNING,
    RETRYING,
    COMPLETED,
    FAILED,
    DEAD;

    public boolean claimable() {
        return this == PENDING || this == RETRYING || this == RUNNING;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == DEAD;
    }
}
