package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;

import java.util.Map;

/**
 * 可重建的文档分块实体。
 *
 * @param tenantId 分块所属租户
 * @param ownerUserId 源文档的拥有者用户标识
 * @param visibility 分块在租户内的可见范围
 * @param knowledgeBaseId 知识库标识
 * @param documentId 逻辑文档标识
 * @param versionId 不可变文档版本标识
 * @param versionNumber 文档内递增的版本序号
 * @param generation 分块对应的知识库索引代际
 * @param chunkId 业务分块标识
 * @param chunkIndex 分块在当前版本中的顺序
 * @param parentChunkId 用于上下文扩展的父分块标识
 * @param previousChunkId 同版本中的前一分块标识
 * @param nextChunkId 同版本中的后一分块标识
 * @param content 可用于检索上下文的分块正文
 * @param tokenCount 按当前分词器计算的 Token 数
 * @param pageNumber 源文档页码，无页码格式可为空
 * @param headingPath 分块所在的标题层级路径
 * @param contentHash 用于激活校验的正文摘要
 * @param vectorPointId 与该分块对应的向量点标识
 * @param metadata 不含对象存储凭据的可展示元数据
 */
public record RagChunkEntity(String tenantId,
                             String ownerUserId,
                             RagVisibility visibility,
                             String knowledgeBaseId,
                             String documentId,
                             String versionId,
                             int versionNumber,
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

    /** 校验分块身份、索引代际、顺序和正文摘要。 */
    public RagChunkEntity {
        requireText(tenantId, "租户ID");
        requireText(ownerUserId, "切片拥有者用户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(versionId, "文档版本ID");
        requireText(chunkId, "分块ID");
        requireText(content, "分块正文");
        requireText(contentHash, "分块摘要");
        if (visibility == null || versionNumber < 1 || generation < 1 || chunkIndex < 0 || tokenCount < 0
                || (pageNumber != null && pageNumber < 1)) {
            throw new IllegalArgumentException("分块序号、Token 或页码非法");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 校验分块身份、正文与摘要的必填文本。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
