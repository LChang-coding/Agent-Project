package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;

import java.util.List;

/** 分配严格递增序号并持久化智能工作流事件。 */
public interface IWorkflowEventRepository {

    WorkflowRunEventEntity append(WorkflowRunEventEntity event);

    List<WorkflowRunEventEntity> queryAfter(String tenantId, String userId, String runId,
                                             long afterSequence, int limit);

    Long queryOldestSequence(String tenantId, String userId, String runId);
}
