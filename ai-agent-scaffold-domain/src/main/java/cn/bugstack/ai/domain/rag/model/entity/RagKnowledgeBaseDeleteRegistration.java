package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 知识库删除状态与后台级联删除任务的原子登记命令。
 * <p>仓储在同一事务中把知识库改为删除中、停用关联绑定并创建删除任务。</p>
 *
 * @param knowledgeBase 已进入删除中状态的知识库
 * @param expectedKnowledgeBaseRevision 发起状态转换前的知识库版本号
 * @param task 待创建的知识库级联删除任务
 */
public record RagKnowledgeBaseDeleteRegistration(RagKnowledgeBaseEntity knowledgeBase,
                                                 long expectedKnowledgeBaseRevision,
                                                 RagKnowledgeBaseDeleteTaskEntity task) {

    /** 校验状态转换版本号以及知识库与任务的租户范围。 */
    public RagKnowledgeBaseDeleteRegistration {
        if (knowledgeBase == null || task == null || expectedKnowledgeBaseRevision < 0
                || knowledgeBase.status() != cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus.DELETING
                || knowledgeBase.revision() != expectedKnowledgeBaseRevision + 1
                || !knowledgeBase.tenantId().equals(task.tenantId())
                || !knowledgeBase.knowledgeBaseId().equals(task.knowledgeBaseId())) {
            throw new IllegalArgumentException("知识库删除登记范围非法");
        }
    }
}
