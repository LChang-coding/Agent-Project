package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * Worker 全局扫描得到的最小任务投影。
 * <p>候选集不携带任务内容，Worker 必须使用 tenantId + jobId 再做原子领取。</p>
 */
public record RagIngestJobCandidate(String tenantId, String jobId) {

    public RagIngestJobCandidate {
        requireText(tenantId, "tenantId");
        requireText(jobId, "jobId");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }
}
