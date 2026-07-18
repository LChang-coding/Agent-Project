package cn.bugstack.ai.domain.rag.model.entity;

import java.util.List;
import java.util.Map;

/**
 * 可注入模型、可评测且不暴露存储凭证的 RAG 检索结果。
 */
public record RagRetrievalResult(String retrievalId,
                                 List<Citation> citations,
                                 int estimatedTokenCount,
                                 boolean degraded,
                                 List<String> degradationReasons,
                                 Metrics metrics) {

    public RagRetrievalResult {
        requireText(retrievalId, "检索ID");
        citations = citations == null ? List.of() : List.copyOf(citations);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        if (estimatedTokenCount < 0 || metrics == null) {
            throw new IllegalArgumentException("RAG检索结果参数非法");
        }
    }

    public static RagRetrievalResult empty(String retrievalId, long totalMs) {
        return new RagRetrievalResult(retrievalId, List.of(), 0, false, List.of(),
                new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, totalMs));
    }

    /** 最终引用；context 可包含同版本父块和相邻块，chunkId 始终指向主命中。 */
    public record Citation(String citationId,
                           int rank,
                           String knowledgeBaseId,
                           String documentId,
                           String documentName,
                           String versionId,
                           int documentVersion,
                           long generation,
                           String chunkId,
                           String context,
                           Integer pageNumber,
                           String headingPath,
                           String contentHash,
                           Double denseScore,
                           Double sparseScore,
                           double fusionScore,
                           Double rerankScore,
                           Map<String, String> metadata) {
        public Citation {
            requireText(citationId, "引用ID");
            requireText(knowledgeBaseId, "知识库ID");
            requireText(documentId, "文档ID");
            requireText(documentName, "文档名");
            requireText(versionId, "版本ID");
            requireText(chunkId, "分块ID");
            requireText(context, "引用正文");
            requireText(contentHash, "内容摘要");
            if (rank < 1 || documentVersion < 1 || generation < 1 || !Double.isFinite(fusionScore)
                    || denseScore != null && !Double.isFinite(denseScore)
                    || sparseScore != null && !Double.isFinite(sparseScore)
                    || rerankScore != null && !Double.isFinite(rerankScore)) {
                throw new IllegalArgumentException("RAG引用参数非法");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** 不含查询正文的阶段指标，供压测和消融分析使用。 */
    public record Metrics(int denseCandidateCount,
                          int sparseCandidateCount,
                          int fusionCandidateCount,
                          int rerankCandidateCount,
                          long embeddingMs,
                          long denseMs,
                          long sparseMs,
                          long fusionMs,
                          long rerankMs,
                          long totalMs) {
        public Metrics {
            if (denseCandidateCount < 0 || sparseCandidateCount < 0 || fusionCandidateCount < 0
                    || rerankCandidateCount < 0 || embeddingMs < 0 || denseMs < 0 || sparseMs < 0
                    || fusionMs < 0 || rerankMs < 0 || totalMs < 0) {
                throw new IllegalArgumentException("RAG检索指标非法");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
