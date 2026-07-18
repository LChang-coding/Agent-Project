package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

/**
 * Agent 或工作流到知识库的可信绑定实体。
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
