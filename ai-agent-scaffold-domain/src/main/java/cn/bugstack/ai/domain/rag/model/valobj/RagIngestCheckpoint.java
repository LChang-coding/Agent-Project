package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 摄取任务可恢复检查点。
 *
 * @param stage 当前流水线阶段
 * @param processedChunks 已处理分块数
 * @param totalChunks 已知分块总数，解析前为零
 * @param embeddingBatchIndex 下一个待处理 Embedding 批次序号
 * @param vectorUpsertIndex 下一个待写入向量序号
 */
public record RagIngestCheckpoint(RagIngestStage stage,
                                  int processedChunks,
                                  int totalChunks,
                                  int embeddingBatchIndex,
                                  int vectorUpsertIndex,
                                  int pageCount,
                                  long characterCount,
                                  String parsedObjectBucket,
                                  String parsedObjectKey,
                                  String parsedContentHash,
                                  long parsedSizeBytes) {

    /** 兼容尚未生成解析产物的旧检查点和删除任务。 */
    public RagIngestCheckpoint(RagIngestStage stage, int processedChunks, int totalChunks,
                               int embeddingBatchIndex, int vectorUpsertIndex) {
        this(stage, processedChunks, totalChunks, embeddingBatchIndex, vectorUpsertIndex,
                0, 0L, null, null, null, 0L);
    }

    public RagIngestCheckpoint {
        if (stage == null || processedChunks < 0 || totalChunks < 0 || processedChunks > totalChunks
                || embeddingBatchIndex < 0 || vectorUpsertIndex < 0 || vectorUpsertIndex > totalChunks
                || pageCount < 0 || characterCount < 0 || parsedSizeBytes < 0
                || stage == RagIngestStage.VERIFYING
                && (totalChunks < 1 || processedChunks != totalChunks || vectorUpsertIndex != totalChunks)) {
            throw new IllegalArgumentException("摄取检查点参数非法");
        }
        boolean anyParsedLocation = hasText(parsedObjectBucket) || hasText(parsedObjectKey)
                || hasText(parsedContentHash) || parsedSizeBytes > 0;
        if (anyParsedLocation && (!hasText(parsedObjectBucket) || !hasText(parsedObjectKey)
                || parsedContentHash == null || !parsedContentHash.matches("[0-9a-f]{64}")
                || parsedSizeBytes < 1 || characterCount < 1)) {
            throw new IllegalArgumentException("解析产物检查点参数非法");
        }
    }

    /** 创建新任务的初始检查点。 */
    public static RagIngestCheckpoint initial() {
        return new RagIngestCheckpoint(RagIngestStage.RECEIVED, 0, 0, 0, 0);
    }

    /** 判断新检查点是否保持阶段和进度单调递增。 */
    public boolean canAdvanceTo(RagIngestCheckpoint target) {
        return target != null && stage.canAdvanceTo(target.stage)
                && target.processedChunks >= processedChunks
                && target.totalChunks >= totalChunks
                && target.embeddingBatchIndex >= embeddingBatchIndex
                && target.vectorUpsertIndex >= vectorUpsertIndex
                && parsedFactsCanAdvanceTo(target);
    }

    /** 解析事实只允许首次写入，后续检查点必须原样携带。 */
    private boolean parsedFactsCanAdvanceTo(RagIngestCheckpoint target) {
        if (!hasText(parsedObjectKey)) {
            return true;
        }
        return pageCount == target.pageCount && characterCount == target.characterCount
                && parsedSizeBytes == target.parsedSizeBytes
                && parsedObjectBucket.equals(target.parsedObjectBucket)
                && parsedObjectKey.equals(target.parsedObjectKey)
                && parsedContentHash.equals(target.parsedContentHash);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
