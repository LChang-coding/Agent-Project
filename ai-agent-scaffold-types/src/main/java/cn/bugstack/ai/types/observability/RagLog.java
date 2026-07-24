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

    /** 记录生产检索开始，不记录原始问题正文。 */
    public AiLogRecord retrieveStarted(String tenantId, String userId, String sessionId, String runId,
                                       String retrievalId, String targetType, String targetId, Integer queryLength) {
        return production(AiLogEvent.RAG_RETRIEVE_STARTED, tenantId, userId, sessionId, runId,
                retrievalId, targetType, targetId)
                .field("queryLength", queryLength).field(AiLogFields.STAGE, "retrieval")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录生产检索完成。 */
    public AiLogRecord retrieveCompleted(String tenantId, String userId, String sessionId, String runId,
                                         String retrievalId, String targetType, String targetId, Integer bindings,
                                         Integer hits, Integer citations, Integer tokens, Boolean degraded,
                                         Long costMs) {
        return production(AiLogEvent.RAG_RETRIEVE, tenantId, userId, sessionId, runId,
                retrievalId, targetType, targetId)
                .field("bindings", bindings).field("hits", hits).field("citations", citations)
                .field("tokens", tokens).field("degraded", degraded).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "retrieval").field(AiLogFields.SUCCESS, true);
    }

    /** 记录生产检索降级。 */
    public AiLogRecord retrieveDegraded(String tenantId, String userId, String sessionId, String runId,
                                        String retrievalId, String targetType, String targetId,
                                        String errorCode, Long costMs, Throwable throwable) {
        return production(AiLogEvent.RAG_RETRIEVE_DEGRADED, tenantId, userId, sessionId, runId,
                retrievalId, targetType, targetId)
                .field(AiLogFields.ERROR_CODE, errorCode).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "degraded").field(AiLogFields.SUCCESS, false).error(throwable);
    }

    /** 记录生产检索失败。 */
    public AiLogRecord retrieveFailed(String tenantId, String userId, String sessionId, String runId,
                                      String retrievalId, String targetType, String targetId,
                                      String errorCode, Long costMs, Throwable throwable) {
        return production(AiLogEvent.RAG_ERROR, tenantId, userId, sessionId, runId,
                retrievalId, targetType, targetId)
                .field(AiLogFields.ERROR_CODE, errorCode).field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.STAGE, "retrieval").field(AiLogFields.SUCCESS, false).error(throwable);
    }

    private AiLogRecord production(AiLogEvent event, String tenantId, String userId, String sessionId,
                                   String runId, String retrievalId, String targetType, String targetId) {
        return AiLogRecord.event(event)
                .field(AiLogFields.TENANT_ID, tenantId).field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.SESSION_ID, sessionId).field(AiLogFields.RUN_ID, runId)
                .field(AiLogFields.RETRIEVAL_ID, retrievalId).field("targetType", targetType)
                .field("targetId", targetId);
    }

    /** 记录摄取任务开始。 */
    public AiLogRecord ingestStarted(String tenantId, String taskId, String documentId,
                                     String versionId, Integer attempt) {
        return ingest(AiLogEvent.RAG_INGEST_STARTED, tenantId, taskId, documentId, versionId)
                .field("attempt", attempt).field(AiLogFields.STAGE, "received")
                .field(AiLogFields.SUCCESS, true);
    }

    /** 记录摄取检查点推进。 */
    public AiLogRecord ingestStageCompleted(String tenantId, String taskId, String documentId,
                                            String versionId, String stage, Integer processedChunks,
                                            Integer totalChunks) {
        return ingest(AiLogEvent.RAG_INGEST_STAGE_COMPLETED, tenantId, taskId, documentId, versionId)
                .field(AiLogFields.STAGE, stage).field("processedChunks", processedChunks)
                .field("totalChunks", totalChunks).field(AiLogFields.SUCCESS, true);
    }

    /** 记录摄取任务完成。 */
    public AiLogRecord ingestCompleted(String tenantId, String taskId, String documentId,
                                       String versionId, Integer totalChunks, Long costMs) {
        return ingest(AiLogEvent.RAG_INGEST_COMPLETED, tenantId, taskId, documentId, versionId)
                .field(AiLogFields.STAGE, "completed").field("totalChunks", totalChunks)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.SUCCESS, true);
    }

    /** 记录摄取任务失败或进入重试。 */
    public AiLogRecord ingestFailed(String tenantId, String taskId, String documentId,
                                    String versionId, String stage, String errorCode,
                                    Long costMs, Throwable throwable) {
        return ingest(AiLogEvent.RAG_INGEST_FAILED, tenantId, taskId, documentId, versionId)
                .field(AiLogFields.STAGE, stage).field(AiLogFields.ERROR_CODE, errorCode)
                .field(AiLogFields.COST_MS, costMs).field(AiLogFields.SUCCESS, false).error(throwable);
    }

    private AiLogRecord ingest(AiLogEvent event, String tenantId, String taskId,
                               String documentId, String versionId) {
        return AiLogRecord.event(event).field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.TASK_ID, taskId).field("documentId", documentId)
                .field("versionId", versionId);
    }
}
