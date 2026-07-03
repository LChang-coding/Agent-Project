package cn.bugstack.ai.types.observability;

public final class RagLog {

    RagLog() {
    }

    public AiLogRecord retrieve(String userId,
                                String sessionId,
                                String knowledgeBase,
                                String queryId,
                                Integer topK,
                                Integer hits,
                                Long costMs,
                                Boolean success) {
        return AiLogRecord.event(AiLogEvent.RAG_RETRIEVE)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field("knowledgeBase", knowledgeBase)
                .field("queryId", queryId)
                .field("topK", topK)
                .field("hits", hits)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord error(String userId,
                             String sessionId,
                             String knowledgeBase,
                             String queryId,
                             Long costMs,
                             Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.RAG_ERROR)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId)
                .field("knowledgeBase", knowledgeBase)
                .field("queryId", queryId)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }
}
