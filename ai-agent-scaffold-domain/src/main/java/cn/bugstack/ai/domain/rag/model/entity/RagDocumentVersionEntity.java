package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.types.exception.AppException;

import java.util.Map;

/**
 * 不可变文档版本实体。
 *
 * @param tenantId 文档版本所属租户
 * @param knowledgeBaseId 知识库标识
 * @param documentId 逻辑文档标识
 * @param versionId 不可变文档版本标识
 * @param versionNumber 逻辑文档内递增的版本序号
 * @param generation 本版本对应的索引代际
 * @param objectBucket 原始文件对象存储桶
 * @param objectKey 原始文件对象键
 * @param parsedObjectBucket 解析产物对象存储桶
 * @param parsedObjectKey 规范化解析产物对象键
 * @param fileName 通过安全校验的文件名
 * @param sha256 原始文件 SHA-256 摘要
 * @param mimeType 经扩展名与文件内容共同确认的 MIME 类型
 * @param sizeBytes 原始文件字节数
 * @param status 文档版本生命周期状态
 * @param parserVersion 本次摄取实际使用的解析器版本
 * @param chunkerVersion 本次摄取实际使用的分块器版本
 * @param embeddingModelRevision 本次摄取实际使用的 Embedding 模型版本
 * @param revision 乐观并发控制版本号
 * @param pageCount 解析后页数
 * @param characterCount 规范化正文字符数
 * @param chunkCount 分块数
 * @param metadata 解析和质量评估产生的不可变元数据
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

    /** 校验版本身份、对象位置、索引代际、状态和解析统计。 */
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

    /** 校验不可变文档版本的必填身份与对象位置。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /**
     * 记录本次摄取实际使用的不可变组件版本。
     * @param parserRevision 解析器版本
     * @param chunkerRevision 分块器版本
     * @param embeddingRevision Embedding 模型版本
     * @return 状态为 PROCESSING 且版本号递增的新文档版本
     */
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

    /**
     * 已验证业务分块和向量索引一致后激活版本。
     * @return 状态为 READY 且版本号递增的新文档版本
     */
    public RagDocumentVersionEntity ready() {
        if (status != RagDocumentVersionStatus.PROCESSING) {
            throw new IllegalStateException("只有处理中的版本可以激活");
        }
        return copy(RagDocumentVersionStatus.READY, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /**
     * 处理取消后关闭版本。
     * @return 状态为 CANCELLED 的新文档版本；已是终态时返回当前对象
     */
    public RagDocumentVersionEntity cancelled() {
        if (status.terminal()) return this;
        return copy(RagDocumentVersionStatus.CANCELLED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /**
     * 处理失败后关闭版本。
     * @return 状态为 FAILED 的新文档版本；已是终态时返回当前对象
     */
    public RagDocumentVersionEntity failed() {
        if (status.terminal()) return this;
        return copy(RagDocumentVersionStatus.FAILED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /**
     * 失败副作用已清理后，将同一不可变源文件版本重新排队。
     * @return 状态为 QUEUED、解析产物与统计已清除的新文档版本
     */
    public RagDocumentVersionEntity retryQueued() {
        if (status != RagDocumentVersionStatus.FAILED) {
            throw new AppException("RAG_INGEST_VERSION_RETRY_STATE_INVALID", "只有失败版本可以重新排队");
        }
        return new RagDocumentVersionEntity(tenantId, knowledgeBaseId, documentId, versionId, versionNumber,
                generation, objectBucket, objectKey, null, null, fileName, sha256, mimeType, sizeBytes,
                RagDocumentVersionStatus.QUEUED, null, null, null, revision + 1,
                0, 0L, 0, Map.of());
    }

    /**
     * 将已停止写入的版本转为删除中；重复调用保持幂等。
     * @return 状态为 DELETING 的新文档版本；已处于删除流程时返回当前对象
     */
    public RagDocumentVersionEntity requestDeletion() {
        if (status == RagDocumentVersionStatus.DELETING || status == RagDocumentVersionStatus.DELETED) return this;
        if (status == RagDocumentVersionStatus.PROCESSING) {
            throw new AppException("RAG_DOCUMENT_VERSION_BUSY", "文档版本仍在处理中，不能开始删除");
        }
        return copy(RagDocumentVersionStatus.DELETING, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /**
     * 外部对象和索引均清理后将版本转为删除终态。
     * @return 状态为 DELETED 的新文档版本；已是删除终态时返回当前对象
     */
    public RagDocumentVersionEntity deleted() {
        if (status == RagDocumentVersionStatus.DELETED) return this;
        if (status != RagDocumentVersionStatus.DELETING) {
            throw new AppException("RAG_DOCUMENT_VERSION_DELETE_STATE_INVALID", "只有删除中的版本可以关闭");
        }
        return copy(RagDocumentVersionStatus.DELETED, parserVersion, chunkerVersion, embeddingModelRevision);
    }

    /** 复制不可变版本身份，并为每次状态迁移递增 revision。 */
    private RagDocumentVersionEntity copy(RagDocumentVersionStatus targetStatus, String parserRevision,
                                          String chunkerRevision, String embeddingRevision) {
        return new RagDocumentVersionEntity(tenantId, knowledgeBaseId, documentId, versionId, versionNumber,
                generation, objectBucket, objectKey, parsedObjectBucket, parsedObjectKey,
                fileName, sha256, mimeType, sizeBytes, targetStatus,
                parserRevision, chunkerRevision, embeddingRevision, revision + 1,
                pageCount, characterCount, chunkCount, metadata);
    }
}
