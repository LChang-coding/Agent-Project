package cn.bugstack.ai.api.dto.session;

/**
 * 会话RAG设置响应。
 */
public record SessionRagSettingResponseDTO(String sessionId,
                                           boolean enabled,
                                           boolean bindingConfigured,
                                           String targetType,
                                           String targetId,
                                           String message) {
}
