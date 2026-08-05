package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库、文档、分块这三类 RAG 资源「谁能看见」的可见范围标记。
 *
 * <p>属于哪一层：领域层的值对象，只表达一条规则，不查库也不判断具体某个人有没有权限。</p>
 *
 * <p>谁会读它：检索链路（RagRetrievalService 组装可用绑定时）、会话 RAG 设置查询
 * （SessionRagSettingService 过滤可选知识库）、引用原文查看（RagAnswerCitationMetadataService）。
 * 三处的判断口径完全一致：值是 PRIVATE 时必须再比对 ownerUserId 与当前请求用户是否相同，
 * 不同就当作「这个资源不存在」直接跳过或报不可用。</p>
 *
 * <p>它不负责什么：不负责租户隔离。跨租户永远看不到彼此的数据，那是靠每个查询都必须带 tenantId 实现的，
 * 与本枚举无关。本枚举只解决「同一个租户内部，某份资料是只归上传者自己用，还是全租户共用」。</p>
 */
public enum RagVisibility {

    /**
     * 私有：只有 ownerUserId 记录的那个人能检索到、能查原文。
     *
   * <p>进入这个状态的动作：创建知识库或上传文档时选择不共享。</p>
     *
     * <p>处于该状态时：别人即使拿到了 bindingId 或 citationId 也读不到内容，
     * 检索阶段就会把它从候选里剔除；如果它是 required 绑定，别人的对话会直接报「必需知识库不可用」。</p>
     */
    PRIVATE,

    /**
     * 租户共享：同租户内所有被授权的 Agent、工作流和用户都能检索到。
     *
 * <p>进入这个状态的动作：创建资源时选择在租户内共享。</p>
     *
     * <p>处于该状态时：不再比对 ownerUserId，但依然只在本租户范围内可见，任何情况都不跨租户。</p>
     */
    TENANT
}
