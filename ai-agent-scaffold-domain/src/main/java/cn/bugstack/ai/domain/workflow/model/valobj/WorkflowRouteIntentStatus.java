package cn.bugstack.ai.domain.workflow.model.valobj;

/** 路由意图从待消费到已消费的单向状态。 */
public enum WorkflowRouteIntentStatus {

    /** 已登记并等待运行时形成路由裁决。 */
    PENDING,

    /** 已被运行时用于一次路由裁决，不能再次推进节点。 */
    CONSUMED
}
