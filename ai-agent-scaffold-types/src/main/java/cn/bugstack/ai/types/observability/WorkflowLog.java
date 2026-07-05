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
