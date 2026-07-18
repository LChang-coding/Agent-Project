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
}
