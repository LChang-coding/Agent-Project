package cn.bugstack.ai.domain.rag.model.entity;

import java.util.Map;

/**
 * 可重建的文档分块实体。
 */
public record RagChunkEntity(String tenantId,
                             String knowledgeBaseId,
                             String documentId,
                             String versionId,
                             long generation,
                             String chunkId,
                             int chunkIndex,
                             String parentChunkId,
                             String previousChunkId,
                             String nextChunkId,
                             String content,
                             int tokenCount,
                             Integer pageNumber,
                             String headingPath,
                             String contentHash,
                             String vectorPointId,
                             Map<String, String> metadata) {

    public RagChunkEntity {
        requireText(tenantId, "租户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(versionId, "文档版本ID");
        requireText(chunkId, "分块ID");
        requireText(content, "分块正文");
        requireText(contentHash, "分块摘要");
        if (generation < 1 || chunkIndex < 0 || tokenCount < 0 || (pageNumber != null && pageNumber < 1)) {
            throw new IllegalArgumentException("分块序号、Token 或页码非法");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
