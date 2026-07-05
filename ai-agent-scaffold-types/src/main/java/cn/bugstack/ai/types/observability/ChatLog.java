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
}
