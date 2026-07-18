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
                                  int vectorUpsertIndex) {

    public RagIngestCheckpoint {
        if (stage == null || processedChunks < 0 || totalChunks < 0 || processedChunks > totalChunks
                || embeddingBatchIndex < 0 || vectorUpsertIndex < 0) {
            throw new IllegalArgumentException("摄取检查点参数非法");
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
                && target.vectorUpsertIndex >= vectorUpsertIndex;
    }
}
