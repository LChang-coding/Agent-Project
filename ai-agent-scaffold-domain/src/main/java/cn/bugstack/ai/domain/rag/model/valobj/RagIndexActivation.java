package cn.bugstack.ai.domain.rag.model.valobj;

/** 经过索引验证后的文档版本原子激活参数。 */
public record RagIndexActivation(String knowledgeBaseId,
                                 String documentId,
                                 String versionId,
                                 long generation,
                                 long expectedVersionRevision,
                                 long expectedDocumentRevision,
                                 long expectedKnowledgeBaseRevision) {

    public RagIndexActivation {
        requireText(knowledgeBaseId, "knowledgeBaseId");
        requireText(documentId, "documentId");
        requireText(versionId, "versionId");
        if (generation < 1 || expectedVersionRevision < 0 || expectedDocumentRevision < 0
                || expectedKnowledgeBaseRevision < 0) {
            throw new IllegalArgumentException("RAG 索引激活版本参数非法");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }
}
