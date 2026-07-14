package cn.bugstack.ai.domain.run.model;

/**
 * 会话运行状态。
 */
public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_COMPACTION,
    WAITING_TOOL,
    STEER_REQUESTED,
    CANCEL_REQUESTED,
    CANCELLING,
    SUPERSEDED,
    CANCELLED,
    COMPLETED,
    FAILED;

    /**
     * 判断是否为终态；无参数；返回是否已结束。
     */
    public boolean terminal() {
        return this == SUPERSEDED || this == CANCELLED || this == COMPLETED || this == FAILED;
    }

    /**
     * 判断是否允许继续执行；无参数；返回是否可继续。
     */
    public boolean executable() {
        return this == CREATED || this == RUNNING || this == WAITING_COMPACTION || this == WAITING_TOOL;
    }
}
