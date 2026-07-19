package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 知识库逻辑文档实体。
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
                                long revision) {

    public RagDocumentEntity {
        requireText(tenantId, "租户ID");
        requireText(ownerUserId, "文档拥有者用户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(displayName, "文档名称");
        if (activeGeneration < 0 || targetGeneration != null && targetGeneration < 1
                || visibility == null || status == null || revision < 0) {
            throw new IllegalArgumentException("文档状态或版本非法");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 在向量与业务分块均验证完成后原子切换活动版本。 */
    public RagDocumentEntity activate(String versionId, long generation) {
        requireText(versionId, "活动版本ID");
        if (generation < 1 || targetGeneration == null || targetGeneration != generation) {
            throw new IllegalArgumentException("文档激活generation与目标不一致");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, versionId, generation, null, RagDocumentStatus.READY, revision + 1);
    }

    /** 标记本次处理失败但保留旧活动版本。 */
    public RagDocumentEntity failProcessing() {
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, activeVersionId, activeGeneration, null, RagDocumentStatus.FAILED, revision + 1);
    }

    /** 建立删除墓碑，使该文档立即退出可检索范围。 */
    public RagDocumentEntity requestDeletion() {
        if (status == RagDocumentStatus.DELETING || status == RagDocumentStatus.DELETED) return this;
        if (status != RagDocumentStatus.READY && status != RagDocumentStatus.FAILED) {
            throw new AppException("RAG_DOCUMENT_BUSY", "文档仍在处理中，不能开始删除");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, activeVersionId, activeGeneration, null, RagDocumentStatus.DELETING, revision + 1);
    }

    /** 所有版本的外部对象和索引清理完成后关闭逻辑文档。 */
    public RagDocumentEntity deleted() {
        if (status == RagDocumentStatus.DELETED) return this;
        if (status != RagDocumentStatus.DELETING) {
            throw new AppException("RAG_DOCUMENT_DELETE_STATE_INVALID", "只有删除中的文档可以关闭");
        }
        return new RagDocumentEntity(tenantId, ownerUserId, visibility, knowledgeBaseId, documentId,
                displayName, null, 0L, null, RagDocumentStatus.DELETED, revision + 1);
    }
}
