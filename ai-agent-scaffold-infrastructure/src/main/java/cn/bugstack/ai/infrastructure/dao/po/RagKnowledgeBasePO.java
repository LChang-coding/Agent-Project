package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 租户知识库定义、向量集合别名和当前索引代次。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagKnowledgeBasePO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 知识库拥有者用户ID
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
     * 知识库名称
     */
    private String knowledgeBaseName;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * Embedding 模型
     */
    private String embeddingModel;

    /** 向量集合固定维度。 */
    private Integer embeddingDimension;

    /** 指向当前物理集合的稳定 Qdrant 别名。 */
    private String collectionAlias;

    /** 知识库当前激活代次。 */
    private Long currentGeneration;

    /** 知识库默认检索策略 ID。 */
    private String retrievalProfileId;

    /** 聚合根乐观并发修订号。 */
    private Long revision;

    /**
     * 知识库状态：active/disabled/indexing
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
