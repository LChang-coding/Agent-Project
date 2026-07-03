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

    /**
     * 知识库状态：active/disabled/indexing
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
