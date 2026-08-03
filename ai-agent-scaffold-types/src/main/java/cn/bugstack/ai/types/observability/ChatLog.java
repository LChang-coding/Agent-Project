package cn.bugstack.ai.types.observability;

public final class ChatLog {

    ChatLog() {
    }

    /**
     * 记录会话创建；参数是租户、用户、会话和 Agent 信息；返回日志记录。
     */
    public AiLogRecord sessionCreated(String tenantId,
                                      String userId,
                                      String sessionId,
                                      String agentId,
                                      String agentName,
                                      String appName) {
        return AiLogRecord.event(AiLogEvent.CHAT_SESSION_CREATED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.AGENT_ID, agentId)
                .field(AiLogFields.AGENT_NAME, agentName)
                .field(AiLogFields.APP_NAME, appName)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录消息保存；参数是消息身份和长度；返回日志记录。
     */
    public AiLogRecord messageSaved(String tenantId,
                                    String userId,
                                    String sessionId,
                                    String messageId,
                                    String role,
                                    Integer sequenceNo,
                                    Integer contentLength) {
        return AiLogRecord.event(AiLogEvent.CHAT_MESSAGE_SAVED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.MESSAGE_ID, messageId)
                .field(AiLogFields.ROLE, role)
                .field(AiLogFields.SEQUENCE_NO, sequenceNo)
                .field(AiLogFields.CONTENT_LENGTH, contentLength)
                .field(AiLogFields.SUCCESS, true);
    }

    /**
     * 记录会话拒绝；参数是身份和错误信息；返回日志记录。
     */
    public AiLogRecord sessionRejected(String tenantId,
                                       String userId,
                                       String sessionId,
                                       String agentId,
                                       String errorCode,
                                       String errorMessage) {
        return AiLogRecord.event(AiLogEvent.CHAT_SESSION_REJECTED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.AGENT_ID, agentId)
                .field(AiLogFields.ERROR_CODE, errorCode)
                .field(AiLogFields.ERROR_MESSAGE, errorMessage)
                .field(AiLogFields.SUCCESS, false);
    }

    /** 记录会话RAG设置变更。 */
    public AiLogRecord ragSettingChanged(String tenantId, String userId, String sessionId,
                                         Boolean enabled, Boolean bindingConfigured) {
        return AiLogRecord.event(AiLogEvent.CHAT_RAG_SETTING_CHANGED)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field("ragEnabled", enabled)
                .field("bindingConfigured", bindingConfigured)
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录一次会话运行开始。 */
    public AiLogRecord runStarted(String tenantId, String userId, String sessionId, String runId,
                                  String sourceType, String sourceId, Boolean ragEnabled) {
        return AiLogRecord.event(AiLogEvent.CHAT_RUN_STARTED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("sourceType", sourceType).field("sourceId", sourceId)
                .field("ragEnabled", ragEnabled).field(AiLogFields.STAGE, "run")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录一次会话运行完成。 */
    public AiLogRecord runCompleted(String tenantId, String userId, String sessionId, String runId,
                                    Boolean ragEnabled, Integer contentLength, Long costMs) {
        return AiLogRecord.event(AiLogEvent.CHAT_RUN_COMPLETED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field(AiLogFields.CONTENT_LENGTH, contentLength)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.STAGE, "run")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录一次会话运行取消；根 Trace 由调用方使用运行记录中的 traceId 显式覆盖。 */
    public AiLogRecord runCancelled(String tenantId, String userId, String sessionId, String runId,
                                    Boolean ragEnabled, String reason, Long costMs) {
        return AiLogRecord.event(AiLogEvent.CHAT_RUN_CANCELLED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field("reason", reason)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.STAGE, "run")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录一次会话运行失败。 */
    public AiLogRecord runFailed(String tenantId, String userId, String sessionId, String runId,
                                 Boolean ragEnabled, Long costMs, Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.CHAT_RUN_FAILED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "run").field(AiLogFields.SUCCESS, false).error(throwable);
    }

    /** 记录上下文组装开始。 */
    public AiLogRecord contextStarted(String tenantId, String userId, String sessionId, String runId,
                                      Boolean ragEnabled) {
        return AiLogRecord.event(AiLogEvent.CONTEXT_ASSEMBLY_STARTED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field(AiLogFields.STAGE, "context")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录上下文组装完成。 */
    public AiLogRecord contextCompleted(String tenantId, String userId, String sessionId, String runId,
                                        Boolean ragEnabled, Integer estimatedTokens, Integer ragEvidenceCount,
                                        Long costMs) {
        return AiLogRecord.event(AiLogEvent.CONTEXT_ASSEMBLY_COMPLETED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field("estimatedTokens", estimatedTokens)
                .field("ragEvidenceCount", ragEvidenceCount).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "context").field(AiLogFields.SUCCESS, true);
    }

    /** 记录上下文组装失败。 */
    public AiLogRecord contextFailed(String tenantId, String userId, String sessionId, String runId,
                                     Boolean ragEnabled, Long costMs, Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.CONTEXT_ASSEMBLY_FAILED)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field("ragEnabled", ragEnabled).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "context").field(AiLogFields.SUCCESS, false).error(throwable);
    }
}
