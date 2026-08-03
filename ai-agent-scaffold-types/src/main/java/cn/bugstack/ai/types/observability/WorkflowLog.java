package cn.bugstack.ai.types.observability;

public final class WorkflowLog {

    WorkflowLog() {
    }

    /**
     * 记录工作流创建；参数是租户、用户和工作流信息；返回日志记录。
     */
    public AiLogRecord created(String tenantId, String userId, String workflowId, String workflowName, String modelCode) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_CREATED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("workflowName", workflowName)
                .field("modelCode", modelCode)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录草稿保存；参数是租户、用户、工作流和版本；返回日志记录。
     */
    public AiLogRecord draftSaved(String tenantId, String userId, String workflowId, Integer version) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_DRAFT_SAVED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("version", version)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录工作流发布；参数是租户、用户、工作流和版本；返回日志记录。
     */
    public AiLogRecord published(String tenantId, String userId, String workflowId, Integer version) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_PUBLISHED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("version", version)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录运行时加载；参数是租户、用户、工作流、版本和模型；返回日志记录。
     */
    public AiLogRecord runtimeLoaded(String tenantId, String userId, String workflowId, Integer version, String runtimeAgentId, String modelCode) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_RUNTIME_LOADED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("version", version)
                .field(AiLogFields.AGENT_ID, runtimeAgentId)
                .field("modelCode", modelCode)
                .field(AiLogFields.SUCCESS, true);
    }

    public AiLogRecord nodeStarted(String tenantId, String userId, String sessionId, String runId,
                                   String workflowId, String nodeId, Integer iteration,
                                   Integer totalIterations, Integer upstreamCount) {
        return node(AiLogEvent.WORKFLOW_NODE_STARTED, tenantId, userId, sessionId, runId,
                workflowId, nodeId, iteration, totalIterations)
                .field("upstreamCount", upstreamCount).field(AiLogFields.STAGE, "node_execute")
                .field(AiLogFields.SUCCESS, true);
    }

    public AiLogRecord nodeCompleted(String tenantId, String userId, String sessionId, String runId,
                                     String workflowId, String nodeId, Integer iteration,
                                     Integer totalIterations, Integer outputLength,
                                     Integer evidenceCount, Long costMs) {
        return node(AiLogEvent.WORKFLOW_NODE_COMPLETED, tenantId, userId, sessionId, runId,
                workflowId, nodeId, iteration, totalIterations)
                .field("outputLength", outputLength).field("evidenceCount", evidenceCount)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.STAGE, "node_execute")
                .field(AiLogFields.SUCCESS, true);
    }

    public AiLogRecord nodeCancelled(String tenantId, String userId, String sessionId, String runId,
                                     String workflowId, String nodeId, Integer iteration,
                                     Integer totalIterations, Long costMs) {
        return node(AiLogEvent.WORKFLOW_NODE_CANCELLED, tenantId, userId, sessionId, runId,
                workflowId, nodeId, iteration, totalIterations)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.STAGE, "node_execute")
                .field(AiLogFields.SUCCESS, true);
    }

    public AiLogRecord nodeFailed(String tenantId, String userId, String sessionId, String runId,
                                  String workflowId, String nodeId, Integer iteration,
                                  Integer totalIterations, Long costMs, Throwable throwable) {
        return node(AiLogEvent.WORKFLOW_NODE_FAILED, tenantId, userId, sessionId, runId,
                workflowId, nodeId, iteration, totalIterations)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.STAGE, "node_execute")
                .field(AiLogFields.ERROR_TYPE, throwable == null ? null : throwable.getClass().getSimpleName())
                .field(AiLogFields.SUCCESS, false);
    }

    /** 记录单次路由裁决；可以按根 traceId 还原节点跳转。 */
    public AiLogRecord routeDecided(String tenantId, String userId, String sessionId, String runId,
                                    String workflowId, String nodeExecutionId, String sourceNodeId,
                                    String targetNodeId, String strategy, String reason, boolean terminal) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_ROUTE_DECIDED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("workflowId", workflowId).field("nodeExecutionId", nodeExecutionId)
                .field("sourceNodeId", sourceNodeId).field("targetNodeId", targetNodeId)
                .field("strategy", strategy).field("reason", reason).field("terminal", terminal)
                .field(AiLogFields.STAGE, "route_decision").field(AiLogFields.SUCCESS, true);
    }

    private AiLogRecord node(AiLogEvent event, String tenantId, String userId, String sessionId,
                             String runId, String workflowId, String nodeId,
                             Integer iteration, Integer totalIterations) {
        return AiLogRecord.event(event).field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId).field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.RUN_ID, runId).field("workflowId", workflowId)
                .field("nodeId", nodeId).field("iteration", iteration)
                .field("totalIterations", totalIterations);
    }

    /**
     * 记录 DAG 执行完成；参数是身份、工作流、版本、图规模、终点节点和输出长度；返回日志记录。
     */
    public AiLogRecord dagCompleted(String tenantId,
                                    String userId,
                                    String workflowId,
                                    Integer version,
                                    int nodeCount,
                                    int edgeCount,
                                    String terminalNodeIds,
                                    int outputLength) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_DAG_COMPLETED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("version", version)
                .field("nodeCount", nodeCount)
                .field("edgeCount", edgeCount)
                .field("terminalNodeIds", terminalNodeIds)
                .field("outputLength", outputLength)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录模型路由；参数是租户、用户、工作流和模型；返回日志记录。
     */
    public AiLogRecord modelRouted(String tenantId, String userId, String workflowId, String modelCode) {
        return AiLogRecord.event(AiLogEvent.MODEL_ROUTED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field("modelCode", modelCode)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录运行失败；参数是租户、用户、工作流和异常；返回日志记录。
     */
    public AiLogRecord runFailed(String tenantId, String userId, String workflowId, Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.WORKFLOW_RUN_FAILED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("workflowId", workflowId)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }
}
