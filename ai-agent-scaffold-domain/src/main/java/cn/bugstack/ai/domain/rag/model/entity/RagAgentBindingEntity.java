package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

/**
 * Agent 或工作流到知识库的可信绑定实体。
 *
 * @param tenantId 绑定所属租户
 * @param bindingId 绑定唯一标识
 * @param targetType 绑定目标类型
 * @param targetId Agent 或工作流标识
 * @param knowledgeBaseId 可检索的知识库标识
 * @param retrievalProfileId 本绑定使用的检索配置标识
 * @param required 知识库不可用时是否中止本次检索
 * @param maxTokens 本绑定允许占用的最大上下文 Token 数
 * @param priority 绑定检索和组装的优先级
 * @param revision 乐观并发控制版本号
 */
public record RagAgentBindingEntity(String tenantId,
                                    String bindingId,
                                    RagBindingTargetType targetType,
                                    String targetId,
                                    String knowledgeBaseId,
                                    String retrievalProfileId,
                                    boolean required,
                                    int maxTokens,
                                    int priority,
                                    long revision) {

    /** 校验绑定身份、预算和版本号。 */
    public RagAgentBindingEntity {
        requireText(tenantId, "租户ID");
        requireText(bindingId, "绑定ID");
        requireText(targetId, "绑定目标ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(retrievalProfileId, "检索配置ID");
        if (targetType == null || maxTokens < 1 || priority < 0 || revision < 0) {
            throw new IllegalArgumentException("知识库绑定参数非法");
        }
    }

    /** 校验绑定身份和引用资源的必填文本。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
