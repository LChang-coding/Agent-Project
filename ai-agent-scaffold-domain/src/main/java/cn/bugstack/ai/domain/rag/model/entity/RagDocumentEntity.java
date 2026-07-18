package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;

/**
 * 知识库逻辑文档实体。
 */
public record RagDocumentEntity(String tenantId,
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
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(displayName, "文档名称");
        if (activeGeneration < 0 || targetGeneration != null && targetGeneration < 1
                || status == null || revision < 0) {
            throw new IllegalArgumentException("文档状态或版本非法");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
