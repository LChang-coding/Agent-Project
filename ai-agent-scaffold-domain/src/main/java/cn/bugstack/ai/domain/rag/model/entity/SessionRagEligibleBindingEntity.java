package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 会话RAG设置接口展示的可选绑定摘要。
 */
public record SessionRagEligibleBindingEntity(String bindingId,
                                              String knowledgeBaseId,
                                              String knowledgeBaseName,
                                              String retrievalProfileId,
                                              String retrievalProfileName,
                                              String status,
                                              boolean required,
                                              int maxTokens,
                                              int priority,
                                              long revision,
                                              boolean selected) {
}
