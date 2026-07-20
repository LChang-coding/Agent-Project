package cn.bugstack.ai.domain.rag.model.entity;

/** 知识库删除屏障、绑定停用和任务账本的原子登记命令。 */
public record RagKnowledgeBaseDeleteRegistration(RagKnowledgeBaseEntity knowledgeBase,
                                                 long expectedKnowledgeBaseRevision,
                                                 RagKnowledgeBaseDeleteTaskEntity task) {

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
