package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;

/** 智能工作流外部调用账本仓储。 */
public interface IWorkflowInvocationRepository {
    int insertIgnore(WorkflowInvocationEntity invocation);
    int finish(String tenantId, String invocationId, String status, String downstreamRequestId);
}
