package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;

import java.time.LocalDateTime;

/** 工作流路由意图持久化端口。 */
public interface IWorkflowRouteIntentRepository {
    int claim(WorkflowRouteIntentEntity intent);
    WorkflowRouteIntentEntity queryByNode(String tenantId, String runId, String nodeExecutionId);
    WorkflowRouteIntentEntity queryByFunctionCall(String tenantId, String functionCallId);
    int consume(String tenantId, String runId, String nodeExecutionId, LocalDateTime consumedAt);
}
