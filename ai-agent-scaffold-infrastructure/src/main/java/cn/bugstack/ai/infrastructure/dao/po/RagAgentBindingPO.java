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
    /** 绑定所属租户。 */
    private String tenantId;
    /** 绑定业务 ID。 */
    private String bindingId;
    /** 目标类型：AGENT 或 WORKFLOW。 */
    private String targetType;
    /** 被绑定的 Agent/Workflow ID。 */
    private String targetId;
    /** 参与该目标检索的知识库。 */
    private String knowledgeBaseId;
    /** 该绑定使用的检索策略。 */
    private String profileId;
    /** 多知识库合并时的优先级。 */
    private Integer priority;
    /** 失败时是否阻断整个检索。 */
    private Integer required;
    /** 该绑定最多贡献的上下文 Token。 */
    private Integer maxTokens;
    /** active/disabled 状态。 */
    private String status;
    /** 乐观并发修订号。 */
    private Long revision;
    /** 扩展配置 JSON。 */
    private String metadata;
}
