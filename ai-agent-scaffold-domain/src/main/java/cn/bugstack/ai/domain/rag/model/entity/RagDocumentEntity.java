package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 知识库逻辑文档实体。
 *
 * @param tenantId 文档所属租户
 * @param ownerUserId 文档拥有者用户标识
 * @param visibility 文档在租户内的可见范围
 * @param knowledgeBaseId 文档所属知识库标识
 * @param documentId 逻辑文档标识
 * @param displayName 用于管理和引用展示的文档名称
 * @param activeVersionId 当前可检索的文档版本标识
 * @param activeGeneration 当前可检索版本的索引代际
 * @param targetGeneration 本次处理待激活的目标索引代际
 * @param status 逻辑文档生命周期状态
 * @param revision 乐观并发控制版本号
 * @param pageCount 当前激活版本的页数
 * @param chunkCount 当前激活版本的分块数
 */
public record RagDocumentEntity(String tenantId,
                                String ownerUserId,
                                RagVisibility visibility,
                                String knowledgeBaseId,
                                String documentId,
                                String displayName,
                                String activeVersionId,
                                long activeGeneration,
                                Long targetGeneration,
                                RagDocumentStatus status,
                                long revision,
                                int pageCount,
                                int chunkCount) {

    /**
     * 兼容创建阶段尚无解析指标的调用。
     *
     * @param tenantId 文档所属租户
     * @param ownerUserId 文档拥有者用户标识
     * @param visibility 文档可见范围
     * @param knowledgeBaseId 知识库标识
     * @param documentId 逻辑文档标识
     * @param displayName 文档展示名称
     * @param activeVersionId 当前可检索版本标识
     * @param activeGeneration 当前可检索索引代际
     * @param targetGeneration 待激活索引代际
     * @param status 文档状态
     * @param revision 乐观版本号
     */
    public RagDocumentEntity(String tenantId, String ownerUserId, RagVisibility visibility,
                             String knowledgeBaseId, String documentId, String displayName,
                             String activeVersionId, long activeGeneration, Long targetGeneration,
                             RagDocumentStatus status, long revision) {
        this(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId, displayName,
                activeVersionId, activeGeneration, targetGeneration, status, revision, 0, 0);
    }

    /** 校验文档身份、索引代际、状态和解析统计。 */
    public RagDocumentEntity {
        requireText(tenantId, "租户ID");
        requireText(ownerUserId, "文档拥有者用户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(displayName, "文档名称");
        if (activeGeneration < 0 || targetGeneration != null && targetGeneration < 1
                || visibility == null || status == null || revision < 0 || pageCount < 0 || chunkCount < 0) {
            throw new IllegalArgumentException("文档状态或版本非法");
        }
    }

    /** 校验逻辑文档必填身份和文件事实。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /**
     * 在向量与业务分块均验证完成后生成切换活动版本的新文档。
     * @param versionId 已通过激活校验的文档版本标识
     * @param generation 已通过激活校验的索引代际
     * @return 活动版本已切换且版本号递增的新文档
     */
    public RagDocumentEntity activate(String versionId, long generation) {
        requireText(versionId, "活动版本ID");
        if (generation < 1 || targetGeneration == null || targetGeneration != generation) {
            throw new IllegalArgumentException("文档激活generation与目标不一致");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, versionId, generation, null, RagDocumentStatus.READY, revision + 1,
                pageCount, chunkCount);
    }

    /**
     * 生成处理失败但保留旧活动版本的新文档。
     * @return 状态为 FAILED、目标代际已清除的新文档
     */
    public RagDocumentEntity failProcessing() {
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, activeVersionId, activeGeneration, null, RagDocumentStatus.FAILED, revision + 1,
                pageCount, chunkCount);
    }

    /**
     * 重试失败摄取，恢复同一目标索引代际且保留旧活动版本。
     * @param generation 本次重试待激活的索引代际
     * @return 状态为 PROCESSING 的新文档
     */
    public RagDocumentEntity retryProcessing(long generation) {
        if (status != RagDocumentStatus.FAILED || targetGeneration != null || generation < 1) {
            throw new AppException("RAG_INGEST_DOCUMENT_RETRY_STATE_INVALID", "文档当前不能恢复摄取");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, activeVersionId, activeGeneration, generation, RagDocumentStatus.PROCESSING,
                revision + 1, pageCount, chunkCount);
    }

    /**
     * 生成进入删除中状态的新文档，使其立即退出可检索范围。
     * @return 状态为 DELETING 的新文档；已处于删除流程时返回当前对象
     */
    public RagDocumentEntity requestDeletion() {
        if (status == RagDocumentStatus.DELETING || status == RagDocumentStatus.DELETED) return this;
        if (status != RagDocumentStatus.READY && status != RagDocumentStatus.FAILED) {
            throw new AppException("RAG_DOCUMENT_BUSY", "文档仍在处理中，不能开始删除");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, activeVersionId, activeGeneration, null, RagDocumentStatus.DELETING, revision + 1,
                pageCount, chunkCount);
    }

    /**
     * 所有版本的外部对象和索引清理完成后，生成删除终态文档。
     * @return 活动版本和解析统计已清除的新文档
     */
    public RagDocumentEntity deleted() {
        if (status == RagDocumentStatus.DELETED) return this;
        if (status != RagDocumentStatus.DELETING) {
            throw new AppException("RAG_DOCUMENT_DELETE_STATE_INVALID", "只有删除中的文档可以关闭");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, null, 0L, null, RagDocumentStatus.DELETED, revision + 1, 0, 0);
    }
}
