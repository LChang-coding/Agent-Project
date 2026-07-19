package cn.bugstack.ai.domain.context.model;

import java.util.List;

/**
 * 实际注入模型的 RAG 结构化证据。
 * <p>仅保存可公开标识，不保存正文、向量和对象存储位置。</p>
 */
public record RagContextEvidence(String retrievalId, List<CitationReference> citations) {

    public RagContextEvidence {
        if (retrievalId == null || retrievalId.isBlank()) {
            throw new IllegalArgumentException("RAG检索ID不能为空");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 实际注入模型的单条引用身份。 */
    public record CitationReference(String citationId,
                                    String knowledgeBaseId,
                                    String documentId,
                                    String documentName,
                                    String versionId,
                                    int documentVersion,
                                    long generation,
                                    String chunkId,
                                    String contentHash,
                                    Integer pageNumber,
                                    String headingPath) {

        public CitationReference {
            requireText(citationId, "引用ID");
            requireText(knowledgeBaseId, "知识库ID");
            requireText(documentId, "文档ID");
            requireText(documentName, "文档名");
            requireText(versionId, "版本ID");
            requireText(chunkId, "分块ID");
            requireText(contentHash, "内容摘要");
            if (documentVersion < 1 || generation < 1) {
                throw new IllegalArgumentException("RAG引用版本参数非法");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "不能为空");
            }
        }
    }
}
