package cn.bugstack.ai.domain.agent.model.valobj;

/** 临时子 Agent 任务状态；终态不可重新领取。 */
public enum SubagentTaskStatus {
    READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, ACKED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == ACKED;
    }
}
