package cn.bugstack.ai.domain.rag.model.valobj;

/** 知识库级联删除检查点阶段。 */
public enum RagKnowledgeBaseDeleteStage {
    RECEIVED,
    DELETING_DOCUMENTS,
    VERIFYING,
    COMPLETED
}
