package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 会话RAG设置接口展示的可选绑定摘要。
 * <p>同时返回知识库、检索 Profile、required、预算、优先级、revision 和当前选择状态，
 * 前端标识不能反向作为授权依据。</p>
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
