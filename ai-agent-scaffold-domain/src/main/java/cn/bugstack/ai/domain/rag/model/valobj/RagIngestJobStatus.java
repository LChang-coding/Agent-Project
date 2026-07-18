package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 文档摄取任务状态。
 */
public enum RagIngestJobStatus {
    PENDING,
    RUNNING,
    RETRYING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED,
    DEAD;

    /** 判断任务是否已经进入不可继续执行的终态。 */
    public boolean terminal() {
        return this == CANCELLED || this == COMPLETED || this == FAILED || this == DEAD;
    }

    /** 判断任务是否可以被 Worker 领取。 */
    public boolean claimable() {
        return this == PENDING || this == RETRYING || this == RUNNING;
    }
}
