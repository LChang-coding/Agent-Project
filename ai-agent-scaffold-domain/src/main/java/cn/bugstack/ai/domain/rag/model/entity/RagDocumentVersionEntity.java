package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;

/**
 * 不可变文档版本实体。
 */
public record RagDocumentVersionEntity(String tenantId,
                                       String knowledgeBaseId,
                                       String documentId,
                                       String versionId,
                                       int versionNumber,
                                       long generation,
                                       String objectBucket,
                                       String objectKey,
                                       String fileName,
                                       String sha256,
                                       String mimeType,
                                       long sizeBytes,
                                       RagDocumentVersionStatus status,
                                       String parserVersion,
                                       String chunkerVersion,
                                       String embeddingModelRevision,
                                       long revision) {

    public RagDocumentVersionEntity {
        requireText(tenantId, "租户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(versionId, "文档版本ID");
        requireText(objectBucket, "对象存储桶");
        requireText(objectKey, "对象存储键");
        requireText(fileName, "文件名");
        requireText(sha256, "文件摘要");
        requireText(mimeType, "文件类型");
        if (versionNumber < 1 || generation < 1 || sizeBytes < 0 || status == null || revision < 0) {
            throw new IllegalArgumentException("文档版本参数非法");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 记录本次摄取实际使用的不可变组件版本。 */
    public RagDocumentVersionEntity processing(String parserRevision, String chunkerRevision,
                                                String embeddingRevision) {
        requireText(parserRevision, "解析器版本");
        requireText(chunkerRevision, "分块器版本");
        requireText(embeddingRevision, "Embedding版本");
        if (status != RagDocumentVersionStatus.QUEUED && status != RagDocumentVersionStatus.CREATED) {
            throw new IllegalStateException("只有待处理版本可以开始摄取");
        }
        return copy(RagDocumentVersionStatus.PROCESSING, parserRevision, chunkerRevision, embeddingRevision);
    }

    /** 已验证索引后激活版本。 */
    public RagDocumentVersionEntity ready() {
        if (status != RagDocumentVersionStatus.PROCESSING) {
            throw new IllegalStateException("只有处理中的版本可以激活");
        }
        return copy(RagDocumentVersionStatus.READY, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /** 处理取消后关闭版本。 */
    public RagDocumentVersionEntity cancelled() {
        if (status.terminal()) return this;
        return copy(RagDocumentVersionStatus.CANCELLED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /** 处理失败后关闭版本。 */
    public RagDocumentVersionEntity failed() {
        if (status.terminal()) return this;
        return copy(RagDocumentVersionStatus.FAILED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    private RagDocumentVersionEntity copy(RagDocumentVersionStatus targetStatus, String parserRevision,
                                          String chunkerRevision, String embeddingRevision) {
        return new RagDocumentVersionEntity(tenantId, knowledgeBaseId, documentId, versionId, versionNumber,
                generation, objectBucket, objectKey, fileName, sha256, mimeType, sizeBytes, targetStatus,
                parserRevision, chunkerRevision, embeddingRevision, revision + 1);
    }
}
