package cn.bugstack.ai.domain.rag.model.valobj;

/** 知识库级联删除检查点阶段：接收、逐文档删除、零残留验证、完成。 */
public enum RagKnowledgeBaseDeleteStage {
    RECEIVED,
    DELETING_DOCUMENTS,
    VERIFYING,
    COMPLETED
}
