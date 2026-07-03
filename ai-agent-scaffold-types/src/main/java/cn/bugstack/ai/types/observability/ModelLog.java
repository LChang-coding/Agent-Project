package cn.bugstack.ai.types.observability;

public final class ModelLog {

    ModelLog() {
    }

    public AiLogRecord call(String userId,
                            String sessionId,
                            String agentName,
                            String appName,
                            String invocationId,
                            String modelVersion,
                            Long costMs,
                            Boolean success) {
        return base(AiLogEvent.MODEL_CALL, userId, sessionId, agentName, appName, invocationId)
                .field("modelVersion", modelVersion)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord tokenUsage(String userId,
                                  String sessionId,
                                  String agentName,
                                  String appName,
                                  String invocationId,
                                  String modelVersion,
                                  Integer promptTokens,
                                  Integer candidateTokens,
                                  Integer totalTokens,
                                  Integer thoughtsTokens,
                                  Integer toolUsePromptTokens,
                                  Boolean partial,
                                  Boolean turnComplete) {
        return base(AiLogEvent.TOKEN_USAGE, userId, sessionId, agentName, appName, invocationId)
                .field("modelVersion", modelVersion)
                .field("promptTokens", promptTokens)
                .field("candidateTokens", candidateTokens)
                .field("totalTokens", totalTokens)
                .nullableField("thoughtsTokens", thoughtsTokens)
                .nullableField("toolUsePromptTokens", toolUsePromptTokens)
                .field("partial", partial)
                .field("turnComplete", turnComplete);
    }

    public AiLogRecord error(String userId,
                             String sessionId,
                             String agentName,
                             String appName,
                             String invocationId,
                             String modelVersion,
                             Throwable throwable) {
        return base(AiLogEvent.MODEL_ERROR, userId, sessionId, agentName, appName, invocationId)
                .field("modelVersion", modelVersion)
                .error(throwable);
    }

    private AiLogRecord base(AiLogEvent event,
                             String userId,
                             String sessionId,
                             String agentName,
                             String appName,
                             String invocationId) {
        return AiLogRecord.event(event)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field(AiLogFields.AGENT_NAME, agentName)
                .field(AiLogFields.APP_NAME, appName)
                .field(AiLogFields.INVOCATION_ID, invocationId);
    }
}
