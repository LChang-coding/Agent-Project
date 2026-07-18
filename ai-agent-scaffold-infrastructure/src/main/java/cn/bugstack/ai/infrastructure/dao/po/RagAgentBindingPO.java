package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Agent、工作流及节点到知识库的绑定持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagAgentBindingPO extends BasePO {
    private String tenantId;
    private String bindingId;
    private String targetType;
    private String targetId;
    private String knowledgeBaseId;
    private String profileId;
    private Integer priority;
    private Integer required;
    private Integer maxTokens;
    private String status;
    private Long revision;
    private String metadata;
}
