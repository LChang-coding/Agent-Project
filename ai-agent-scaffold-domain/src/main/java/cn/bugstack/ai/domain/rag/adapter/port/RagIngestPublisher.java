package cn.bugstack.ai.domain.rag.adapter.port;

/**
 * 摄取任务事件发布端口。
 * <p>消息只携带任务身份与版本，不携带文档正文。</p>
 */
public interface RagIngestPublisher {

    /** 发布摄取任务唤醒命令。 */
    void publish(RagIngestCommand command);

    /** 摄取任务唤醒命令。 */
    record RagIngestCommand(String tenantId, String jobId, String versionId,
                            long expectedRevision, String traceId) {
        public RagIngestCommand {
            if (tenantId == null || tenantId.isBlank() || jobId == null || jobId.isBlank()
                    || versionId == null || versionId.isBlank() || expectedRevision < 0) {
                throw new IllegalArgumentException("摄取任务命令参数非法");
            }
        }
    }
}
