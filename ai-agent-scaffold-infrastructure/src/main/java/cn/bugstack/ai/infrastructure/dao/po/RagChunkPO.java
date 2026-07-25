package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 文档版本中可检索分块的正文、结构位置和向量映射。 */
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

    /** 来源文档的人类可见版本号。 */
    private Integer documentVersion;

    /** 来源不可变文档版本 ID。 */
    private String versionId;

    /** 来源索引代次。 */
    private Long generation;

    /**
     * 切片业务ID
     */
    private String chunkId;

    /**
     * 文档内切片序号
     */
    private Integer chunkIndex;

    /** 层级分块的父块 ID。 */
    private String parentChunkId;

    /** 同文档前一分块 ID。 */
    private String previousChunkId;

    /** 同文档后一分块 ID。 */
    private String nextChunkId;

    /** 标题层级组成的结构路径。 */
    private String sectionPath;

    /** 分块起始页。 */
    private Integer pageFrom;

    /** 分块结束页。 */
    private Integer pageTo;

    /** 规范化全文中的起始字符偏移。 */
    private Integer charStart;

    /** 规范化全文中的结束字符偏移。 */
    private Integer charEnd;

    /**
     * 切片内容
     */
    private String content;

    /** 分块正文摘要，用于幂等和证据核验。 */
    private String contentHash;

    /**
     * 切片 token 数
     */
    private Integer tokenCount;

    /**
     * 向量库中的向量ID
     */
    private String embeddingId;

    /** Qdrant 点 ID；与业务 chunkId 分离。 */
    private String vectorPointId;

    /**
     * 切片状态：active/deleted
     */
    private String status;

    /** 行级乐观并发修订号。 */
    private Long revision;

    /**
     * 扩展信息
     */
    private String metadata;
}
