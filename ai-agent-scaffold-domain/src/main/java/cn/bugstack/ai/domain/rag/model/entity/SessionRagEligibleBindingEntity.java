package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 会话RAG设置接口展示的可选绑定摘要。
 * <p>同时返回知识库、检索 Profile、required、预算、优先级、revision 和当前选择状态，
 * 前端标识不能反向作为授权依据。</p>
 *
 * @param bindingId 绑定标识
 * @param knowledgeBaseId 知识库标识
 * @param knowledgeBaseName 知识库展示名称
 * @param retrievalProfileId 检索配置标识
 * @param retrievalProfileName 检索配置展示名称
 * @param status 绑定可用性状态
 * @param required 运行时不可缺少该绑定所指知识库
 * @param maxTokens 该绑定的上下文 Token 上限
 * @param priority 绑定选择优先级
 * @param revision 绑定版本号
 * @param selected 当前会话是否已选中该绑定
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
