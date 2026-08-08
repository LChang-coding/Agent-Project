package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;

/** 智能工作流外部调用账本仓储。 */
public interface IWorkflowInvocationRepository {

    /**
     * 按幂等键登记一次外部调用，重复键不覆盖原记录。
     *
     * @param invocation 准备执行的模型或工具调用
     * @return 1 表示首次登记，0 表示相同幂等键已经存在
     */
    int insertIgnore(WorkflowInvocationEntity invocation);

    /**
     * 将运行中的调用更新为指定终态。
     *
     * @param tenantId 调用所属租户
     * @param invocationId 待完成的调用标识
     * @param status SUCCEEDED 或 FAILED
     * @param downstreamRequestId 外部服务返回的请求标识；请求未发出时可以为空
     * @return 实际更新的记录数；零表示调用不存在或已经完成
     */
    int finish(String tenantId, String invocationId, String status, String downstreamRequestId);
}
