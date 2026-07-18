package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagChunkPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 切片归属用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * 知识库业务ID
     */
    private String knowledgeBaseId;

    /**
     * 文档业务ID
     */
    private String documentId;

    private Integer documentVersion;

    private String versionId;

    private Long generation;

    /**
     * 切片业务ID
     */
    private String chunkId;

    /**
     * 文档内切片序号
     */
    private Integer chunkIndex;

    private String parentChunkId;

    private String previousChunkId;

    private String nextChunkId;

    private String sectionPath;

    private Integer pageFrom;

    private Integer pageTo;

    private Integer charStart;

    private Integer charEnd;

    /**
     * 切片内容
     */
    private String content;

    private String contentHash;

    /**
     * 切片 token 数
     */
    private Integer tokenCount;

    /**
     * 向量库中的向量ID
     */
    private String embeddingId;

    private String vectorPointId;

    /**
     * 切片状态：active/deleted
     */
    private String status;

    private Long revision;

    /**
     * 扩展信息
     */
    private String metadata;
}
