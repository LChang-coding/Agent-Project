package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 租户知识库实体。
 */
public record RagKnowledgeBaseEntity(String tenantId,
                                     String ownerUserId,
                                     String knowledgeBaseId,
                                     String name,
                                     String description,
                                     RagVisibility visibility,
                                     RagKnowledgeBaseStatus status,
                                     String retrievalProfileId,
                                     int embeddingDimension,
                                     String collectionAlias,
                                     long currentGeneration,
                                     long revision) {

    public RagKnowledgeBaseEntity {
        requireText(tenantId, "租户ID");
        requireText(ownerUserId, "拥有者用户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(name, "知识库名称");
        if (visibility == null || status == null || embeddingDimension < 1
                || currentGeneration < 0 || revision < 0) {
            throw new IllegalArgumentException("知识库状态或版本非法");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 推进知识库当前可见代引代，禁止倒退。 */
    public RagKnowledgeBaseEntity activateGeneration(long generation) {
        if (generation < 1 || generation < currentGeneration) {
            throw new IllegalArgumentException("知识库generation不能倒退");
        }
        return new RagKnowledgeBaseEntity(tenantId, ownerUserId, knowledgeBaseId, name, description,
                visibility, RagKnowledgeBaseStatus.ACTIVE, retrievalProfileId, embeddingDimension,
                collectionAlias, generation, revision + 1);
    }

    /** 建立不可取消的级联删除屏障。 */
    public RagKnowledgeBaseEntity requestDeletion() {
        if (status == RagKnowledgeBaseStatus.DELETING) return this;
        if (status == RagKnowledgeBaseStatus.DELETED) {
            throw new AppException("RAG_KNOWLEDGE_BASE_ALREADY_DELETED", "知识库已经删除");
        }
        if (status != RagKnowledgeBaseStatus.ACTIVE && status != RagKnowledgeBaseStatus.DISABLED) {
            throw new AppException("RAG_KNOWLEDGE_BASE_DELETE_STATE_INVALID", "知识库当前不能开始删除");
        }
        return new RagKnowledgeBaseEntity(tenantId, ownerUserId, knowledgeBaseId, name, description,
                visibility, RagKnowledgeBaseStatus.DELETING, retrievalProfileId, embeddingDimension,
                collectionAlias, currentGeneration, revision + 1);
    }

    /** 全部外部副作用和数据库子项验证清理后关闭墓碑。 */
    public RagKnowledgeBaseEntity deleted() {
        if (status == RagKnowledgeBaseStatus.DELETED) return this;
        if (status != RagKnowledgeBaseStatus.DELETING) {
            throw new AppException("RAG_KNOWLEDGE_BASE_DELETE_STATE_INVALID", "知识库尚未进入删除状态");
        }
        return new RagKnowledgeBaseEntity(tenantId, ownerUserId, knowledgeBaseId, name, description,
                visibility, RagKnowledgeBaseStatus.DELETED, retrievalProfileId, embeddingDimension,
                collectionAlias, currentGeneration, revision + 1);
    }
}
