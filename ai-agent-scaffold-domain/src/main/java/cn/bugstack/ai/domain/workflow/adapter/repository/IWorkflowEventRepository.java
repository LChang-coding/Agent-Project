package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;

import java.util.List;

/** 持久化并回放 workflow 类 chat_run 的通用业务事件。 */
public interface IWorkflowEventRepository {

    WorkflowRunEventEntity append(WorkflowRunEventEntity event);

    List<WorkflowRunEventEntity> queryAfter(String tenantId, String userId, String runId,
                                             long afterSequence, int limit);

    Long queryOldestSequence(String tenantId, String userId, String runId);

    WorkflowRunEventEntity queryTerminal(String tenantId, String userId, String runId);
}
