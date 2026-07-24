package cn.bugstack.ai.api.dto.session;

import java.util.List;

/**
 * 会话RAG设置响应。
 */
public record SessionRagSettingResponseDTO(String sessionId,
                                           boolean enabled,
                                           String mode,
                                           long revision,
                                           boolean bindingConfigured,
                                           String targetType,
                                           String targetId,
                                           List<String> selectedBindingIds,
                                           List<EligibleBindingDTO> eligibleBindings,
                                           String message) {

    /**
     * 会话可选RAG绑定摘要。
     */
    public record EligibleBindingDTO(String bindingId,
                                     String knowledgeBaseId,
                                     String knowledgeBaseName,
                                     String profileId,
                                     String profileName,
                                     String status,
                                     boolean required,
                                     int maxTokens,
                                     int priority,
                                     long revision,
                                     boolean selected) {
    }
}
