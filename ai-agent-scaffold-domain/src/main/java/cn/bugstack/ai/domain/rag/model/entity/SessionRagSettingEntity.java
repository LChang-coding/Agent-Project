package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;

import java.util.List;

/**
 * 会话级RAG设置及其绑定可用性。
 */
public record SessionRagSettingEntity(String sessionId,
                                      boolean enabled,
                                      SessionRagMode mode,
                                      long revision,
                                      boolean bindingConfigured,
                                      RagBindingTargetType targetType,
                                      String targetId,
                                      List<String> selectedBindingIds,
                                      List<SessionRagEligibleBindingEntity> eligibleBindings) {

    public SessionRagSettingEntity {
        selectedBindingIds = selectedBindingIds == null ? List.of() : List.copyOf(selectedBindingIds);
        eligibleBindings = eligibleBindings == null ? List.of() : List.copyOf(eligibleBindings);
    }
}
