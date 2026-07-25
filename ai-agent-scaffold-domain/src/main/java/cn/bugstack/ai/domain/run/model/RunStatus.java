package cn.bugstack.ai.domain.run.model;

/**
 * 会话运行状态。
 */
public enum RunStatus {
    /** 引导预建但尚未启动。 */
    CREATED,
    /** 模型或工作流正在执行。 */
    RUNNING,
    /** 等待上下文压缩完成。 */
    WAITING_COMPACTION,
    /** 模型已请求工具、尚未完成调用。 */
    WAITING_TOOL,
    /** 已收到引导，正在失效旧运行副作用。 */
    STEER_REQUESTED,
    /** 已收到取消，等待收敛关联状态。 */
    CANCEL_REQUESTED,
    /** 正在执行取消清理。 */
    CANCELLING,
    /** 已由引导创建的后继运行替代。 */
    SUPERSEDED,
    /** 取消清理完成。 */
    CANCELLED,
    /** 正常完成。 */
    COMPLETED,
    /** 不可恢复失败。 */
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
