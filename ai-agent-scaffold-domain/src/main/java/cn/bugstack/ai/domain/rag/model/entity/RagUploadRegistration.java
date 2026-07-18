package cn.bugstack.ai.domain.rag.model.entity;

/** 需要在同一数据库事务中登记的文档、版本、任务与唤醒事件。 */
public record RagUploadRegistration(RagDocumentEntity document,
                                    RagDocumentVersionEntity version,
                                    RagIngestJobEntity job,
                                    String eventId) {

    public RagUploadRegistration {
        if (document == null || version == null || job == null || eventId == null || eventId.isBlank()
                || !document.tenantId().equals(version.tenantId())
                || !document.tenantId().equals(job.tenantId())) {
            throw new IllegalArgumentException("RAG 上传登记参数非法");
        }
    }
}
