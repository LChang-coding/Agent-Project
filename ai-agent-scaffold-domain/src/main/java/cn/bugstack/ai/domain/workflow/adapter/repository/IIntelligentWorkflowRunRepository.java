package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;

/** 智能工作流运行扩展仓储。 */
public interface IIntelligentWorkflowRunRepository {
    int insert(IntelligentWorkflowRunEntity run);
    IntelligentWorkflowRunEntity query(String tenantId, String userId, String runId);
    int updateState(IntelligentWorkflowRunEntity run, long expectedRevision);
}
