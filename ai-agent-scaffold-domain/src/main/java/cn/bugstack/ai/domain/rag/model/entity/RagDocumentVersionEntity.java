package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.types.exception.AppException;

import java.util.Map;

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
                                       String parsedObjectBucket,
                                       String parsedObjectKey,
                                       String fileName,
                                       String sha256,
                                       String mimeType,
                                       long sizeBytes,
                                       RagDocumentVersionStatus status,
                                       String parserVersion,
                                       String chunkerVersion,
                                       String embeddingModelRevision,
                                       long revision,
                                       int pageCount,
                                       long characterCount,
                                       int chunkCount,
                                       Map<String, String> metadata) {

    /** 兼容创建阶段尚无解析指标的调用。 */
    public RagDocumentVersionEntity(String tenantId, String knowledgeBaseId, String documentId,
                                    String versionId, int versionNumber, long generation,
                                    String objectBucket, String objectKey, String parsedObjectBucket,
                                    String parsedObjectKey, String fileName, String sha256, String mimeType,
                                    long sizeBytes, RagDocumentVersionStatus status, String parserVersion,
                                    String chunkerVersion, String embeddingModelRevision, long revision) {
        this(tenantId, knowledgeBaseId, documentId, versionId, versionNumber, generation,
                objectBucket, objectKey, parsedObjectBucket, parsedObjectKey, fileName, sha256, mimeType,
                sizeBytes, status, parserVersion, chunkerVersion, embeddingModelRevision, revision,
                0, 0L, 0, Map.of());
    }

    public RagDocumentVersionEntity {
        requireText(tenantId, "租户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(versionId, "文档版本ID");
        requireText(objectBucket, "对象存储桶");
        requireText(objectKey, "对象存储键");
        boolean hasParsedBucket = parsedObjectBucket != null && !parsedObjectBucket.isBlank();
        boolean hasParsedKey = parsedObjectKey != null && !parsedObjectKey.isBlank();
        if (hasParsedBucket != hasParsedKey) {
            throw new IllegalArgumentException("解析产物存储桶与对象键必须成对");
        }
        if (hasParsedBucket) {
            requireText(parsedObjectBucket, "解析产物存储桶");
            requireText(parsedObjectKey, "解析产物对象键");
        }
        requireText(fileName, "文件名");
        requireText(sha256, "文件摘要");
        requireText(mimeType, "文件类型");
        if (versionNumber < 1 || generation < 1 || sizeBytes < 0 || status == null || revision < 0
                || pageCount < 0 || characterCount < 0 || chunkCount < 0) {
            throw new IllegalArgumentException("文档版本参数非法");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
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

    /** 失败副作用已清理后，将同一不可变源文件版本重新排队。 */
    public RagDocumentVersionEntity retryQueued() {
        if (status != RagDocumentVersionStatus.FAILED) {
            throw new AppException("RAG_INGEST_VERSION_RETRY_STATE_INVALID", "只有失败版本可以重新排队");
        }
        return new RagDocumentVersionEntity(tenantId, knowledgeBaseId, documentId, versionId, versionNumber,
                generation, objectBucket, objectKey, null, null, fileName, sha256, mimeType, sizeBytes,
                RagDocumentVersionStatus.QUEUED, null, null, null, revision + 1,
                0, 0L, 0, Map.of());
    }

    /** 将任意已停止写入的版本转为删除中；重复调用保持幂等。 */
    public RagDocumentVersionEntity requestDeletion() {
        if (status == RagDocumentVersionStatus.DELETING || status == RagDocumentVersionStatus.DELETED) return this;
        if (status == RagDocumentVersionStatus.PROCESSING) {
            throw new AppException("RAG_DOCUMENT_VERSION_BUSY", "文档版本仍在处理中，不能开始删除");
        }
        return copy(RagDocumentVersionStatus.DELETING, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /** 外部对象和索引均清理后关闭版本墓碑。 */
    public RagDocumentVersionEntity deleted() {
        if (status == RagDocumentVersionStatus.DELETED) return this;
        if (status != RagDocumentVersionStatus.DELETING) {
            throw new AppException("RAG_DOCUMENT_VERSION_DELETE_STATE_INVALID", "只有删除中的版本可以关闭");
        }
        return copy(RagDocumentVersionStatus.DELETED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    private RagDocumentVersionEntity copy(RagDocumentVersionStatus targetStatus, String parserRevision,
                                          String chunkerRevision, String embeddingRevision) {
        return new RagDocumentVersionEntity(tenantId, knowledgeBaseId, documentId, versionId, versionNumber,
                generation, objectBucket, objectKey, parsedObjectBucket, parsedObjectKey,
                fileName, sha256, mimeType, sizeBytes, targetStatus,
                parserRevision, chunkerRevision, embeddingRevision, revision + 1,
                pageCount, characterCount, chunkCount, metadata);
    }
}
