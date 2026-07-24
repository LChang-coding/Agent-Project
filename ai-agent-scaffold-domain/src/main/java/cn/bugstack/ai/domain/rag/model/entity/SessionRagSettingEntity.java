package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

/**
 * 会话级RAG设置及其绑定可用性。
 */
public record SessionRagSettingEntity(String sessionId,
                                      boolean enabled,
                                      boolean bindingConfigured,
                                      RagBindingTargetType targetType,
                                      String targetId) {
}
