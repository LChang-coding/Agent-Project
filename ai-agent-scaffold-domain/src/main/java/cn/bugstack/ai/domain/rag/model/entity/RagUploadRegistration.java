package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 文档上传的原子登记内容。
 * <p>文档、版本、摄取任务和 Outbox 事件必须在同一本地数据库事务中写入，
 * 避免任务已创建但没有可发布事件。</p>
 *
 * @param document 待创建或更新的逻辑文档
 * @param version 本次上传对应的不可变版本
 * @param job 后台摄取任务
 * @param eventId Outbox 唤醒事件的唯一标识
 */
public record RagUploadRegistration(RagDocumentEntity document,
                                    RagDocumentVersionEntity version,
                                    RagIngestJobEntity job,
                                    String eventId) {

    /** 校验登记内各聚合对象属于同一租户。 */
    public RagUploadRegistration {
        if (document == null || version == null || job == null || eventId == null || eventId.isBlank()
                || !document.tenantId().equals(version.tenantId())
                || !document.tenantId().equals(job.tenantId())) {
            throw new IllegalArgumentException("RAG 上传登记参数非法");
        }
    }
}
