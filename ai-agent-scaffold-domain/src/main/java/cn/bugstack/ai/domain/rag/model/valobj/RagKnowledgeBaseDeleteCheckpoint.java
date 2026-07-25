package cn.bugstack.ai.domain.rag.model.valobj;

/** 不包含正文和凭据的知识库删除进度。 */
public record RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage stage,
                                               int totalDocuments,
                                               int completedDocuments,
                                               String currentDocumentId) {

    public RagKnowledgeBaseDeleteCheckpoint {
        if (stage == null || totalDocuments < 0 || completedDocuments < 0
                || completedDocuments > totalDocuments
                || currentDocumentId != null && (currentDocumentId.isBlank()
                || currentDocumentId.length() > 64)) {
            throw new IllegalArgumentException("知识库删除检查点非法");
        }
        if (stage == RagKnowledgeBaseDeleteStage.COMPLETED
                && completedDocuments != totalDocuments) {
            throw new IllegalArgumentException("知识库删除完成检查点数量不一致");
        }
    }

    /** 以知识库当前文档总数创建初始删除检查点。 */
    public static RagKnowledgeBaseDeleteCheckpoint initial(int totalDocuments) {
        return new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.RECEIVED,
                totalDocuments, 0, null);
    }
}
