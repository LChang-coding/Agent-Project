package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 租户知识库实体。
 *
 * @param tenantId 知识库所属租户
 * @param ownerUserId 知识库拥有者用户标识
 * @param knowledgeBaseId 知识库标识
 * @param name 知识库展示名称
 * @param description 知识库用途说明
 * @param visibility 知识库在租户内的可见范围
 * @param status 知识库生命周期状态
 * @param retrievalProfileId 默认检索配置标识
 * @param embeddingDimension 向量索引的固定维度
 * @param collectionAlias 向量存储中的集合别名
 * @param currentGeneration 当前允许检索的索引代际
 * @param revision 乐观并发控制版本号
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

    /** 校验知识库身份、向量维度、索引代际和状态。 */
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

    /** 校验知识库身份与名称。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /**
     * 推进知识库当前可检索的索引代际，禁止倒退。
     * @param generation 已通过索引完整性校验的目标代际
     * @return 状态为 ACTIVE、当前代际已更新的新知识库
     */
    public RagKnowledgeBaseEntity activateGeneration(long generation) {
        if (generation < 1 || generation < currentGeneration) {
            throw new IllegalArgumentException("知识库generation不能倒退");
        }
        return new RagKnowledgeBaseEntity(tenantId, ownerUserId, knowledgeBaseId, name, description,
                visibility, RagKnowledgeBaseStatus.ACTIVE, retrievalProfileId, embeddingDimension,
                collectionAlias, generation, revision + 1);
    }

    /**
     * 将知识库转为删除中，使其退出检索范围并禁止新的子资源操作。
     * @return 状态为 DELETING 的新知识库；已处于删除中时返回当前对象
     */
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

    /**
     * 全部外部数据和数据库子项均验证清理后，将知识库转为删除终态。
     * @return 状态为 DELETED 的新知识库；已是删除终态时返回当前对象
     */
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
