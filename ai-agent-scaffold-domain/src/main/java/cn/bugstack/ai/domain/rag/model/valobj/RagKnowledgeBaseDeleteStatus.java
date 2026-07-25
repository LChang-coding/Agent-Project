package cn.bugstack.ai.domain.rag.model.valobj;

/** 知识库级联删除任务状态；WAITING 表示等待子文档，RETRYING 表示可恢复失败，DEAD 表示耗尽。 */
public enum RagKnowledgeBaseDeleteStatus {
    PENDING,
    RUNNING,
    WAITING,
    RETRYING,
    COMPLETED,
    FAILED,
    DEAD;

    /** 判断任务是否可首次领取、从等待/重试恢复或接管过期运行。 */
    public boolean claimable() {
        return this == PENDING || this == WAITING || this == RETRYING || this == RUNNING;
    }

    /** 判断任务是否已完成或不可自动继续。 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == DEAD;
    }
}
