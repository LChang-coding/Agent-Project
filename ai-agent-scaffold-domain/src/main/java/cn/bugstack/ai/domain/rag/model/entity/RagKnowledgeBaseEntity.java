package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;

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
}
