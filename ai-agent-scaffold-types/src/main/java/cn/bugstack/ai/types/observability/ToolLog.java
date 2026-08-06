package cn.bugstack.ai.types.observability;

/**
 * 工具领域结构化日志构造器。
 */
public final class ToolLog {

    ToolLog() {
    }

    /**
     * 记录 Skill 发布；参数是身份和 Skill 信息；返回日志记录。
     */
    public AiLogRecord skillPublished(String tenantId, String userId, String skillId, String version, String visibility) {
        return AiLogRecord.event(AiLogEvent.TOOL_SKILL_PUBLISHED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("skillId", skillId)
                .field("version", version)
                .field("visibility", visibility)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录 MCP 发布；参数是身份和 MCP 信息；返回日志记录。
     */
    public AiLogRecord mcpPublished(String tenantId, String userId, String mcpId, String version, String visibility) {
        return AiLogRecord.event(AiLogEvent.TOOL_MCP_PUBLISHED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field("mcpId", mcpId)
                .field("version", version)
                .field("visibility", visibility)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录工具调用开始；参数是调用上下文和工具信息；返回日志记录。
     */
    public AiLogRecord callStarted(String tenantId, String userId, String sessionId, String toolType, String toolId, String toolName, String traceId) {
        return callStarted(tenantId, userId, sessionId, null, toolType, toolId, toolName, traceId);
    }

    public AiLogRecord callStarted(String tenantId, String userId, String sessionId, String runId,
                                   String toolType, String toolId, String toolName, String traceId) {
        return call(AiLogEvent.TOOL_CALL_STARTED, tenantId, userId, sessionId, runId,
                toolType, toolId, toolName, traceId, null, true);
    }

    /**
     * 记录工具调用成功；参数是调用上下文、工具信息和耗时；返回日志记录。
     */
    public AiLogRecord callSuccess(String tenantId, String userId, String sessionId, String toolType, String toolId, String toolName, String traceId, Long costMs) {
        return callSuccess(tenantId, userId, sessionId, null, toolType, toolId, toolName, traceId, costMs);
    }

    public AiLogRecord callSuccess(String tenantId, String userId, String sessionId, String runId,
                                   String toolType, String toolId, String toolName, String traceId, Long costMs) {
        return call(AiLogEvent.TOOL_CALL_SUCCESS, tenantId, userId, sessionId, runId,
                toolType, toolId, toolName, traceId, costMs, true);
    }

    /**
     * 记录工具调用失败；参数是调用上下文、工具信息、耗时和异常；返回日志记录。
     */
    public AiLogRecord callFailed(String tenantId, String userId, String sessionId, String toolType, String toolId, String toolName, String traceId, Long costMs, Throwable throwable) {
        return callFailed(tenantId, userId, sessionId, null, toolType, toolId, toolName, traceId, costMs, throwable);
    }

    public AiLogRecord callFailed(String tenantId, String userId, String sessionId, String runId,
                                  String toolType, String toolId, String toolName, String traceId,
                                  Long costMs, Throwable throwable) {
        AiLogRecord record = call(AiLogEvent.TOOL_CALL_FAILED, tenantId, userId, sessionId, runId,
                toolType, toolId, toolName, traceId, costMs, false).error(throwable);
        if (throwable instanceof cn.bugstack.ai.types.exception.AppException exception) {
            record.field("errorCode", exception.getCode());
        }
        return record;
    }

    /** 记录工具调用授权、幂等领取和路由等内部阶段。 */
    public AiLogRecord stage(String tenantId, String userId, String sessionId, String runId,
                             String toolType, String toolId, String toolName, String traceId,
                             String stage, String message, String outcome, Long costMs) {
        return call(AiLogEvent.TOOL_STAGE, tenantId, userId, sessionId, runId,
                toolType, toolId, toolName, traceId, costMs, !"failed".equals(outcome))
                .field(AiLogFields.STAGE, stage).field(AiLogFields.MESSAGE, message)
                .field(AiLogFields.OUTCOME, outcome);
    }

    /**
     * 构造工具调用日志；参数是事件、上下文、工具和耗时；返回日志记录。
     */
    private AiLogRecord call(AiLogEvent event,
                             String tenantId,
                             String userId,
                             String sessionId,
                             String runId,
                             String toolType,
                             String toolId,
                             String toolName,
                             String traceId,
                             Long costMs,
                             Boolean success) {
        return AiLogRecord.event(event)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.RUN_ID, runId)
                .field(AiLogFields.TRACE_ID, traceId)
                .field("toolType", toolType)
                .field("toolId", toolId)
                .field("toolName", toolName)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }
}
