package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 管理员 RAG 调试结果；不含向量、对象 Key、密钥或中间件错误体。 */
@Data
@Builder
public class RagRetrievalDebugResponseDTO {
    private String retrievalId;
    private Integer estimatedTokenCount;
    private Boolean degraded;
    private List<String> degradationReasons;
    private Metrics metrics;
    private List<Citation> citations;
    private Diagnostics diagnostics;

    @Data
    @Builder
    public static class Metrics {
        private Integer denseCandidateCount;
        private Integer sparseCandidateCount;
        private Integer fusionCandidateCount;
        private Integer rerankCandidateCount;
        private Long embeddingMs;
        private Long denseMs;
        private Long sparseMs;
        private Long fusionMs;
        private Long rerankMs;
        private Long totalMs;
        private Long configurationMs;
        private Long hydrationMs;
        private Long assemblyMs;
        private Long auditMs;
        private Long serviceMs;
    }

    @Data
    @Builder
    public static class Citation {
        private String citationId;
        private Integer rank;
        private String knowledgeBaseId;
        private String documentId;
        private String documentName;
        private Integer documentVersion;
        private Long generation;
        private String chunkId;
        private String context;
        private Integer pageNumber;
        private String headingPath;
        private Double denseScore;
        private Double sparseScore;
        private Double fusionScore;
        private Double rerankScore;
        private Map<String, String> metadata;
    }

    @Data
    @Builder
    public static class Diagnostics {
        private Boolean enabled;
        private Boolean truncated;
        private Integer capturedCount;
        private Integer maxCapturedCount;
        private List<Candidate> candidates;
    }

    @Data
    @Builder
    public static class Candidate {
        private String bindingId;
        private String profileId;
        private String stage;
        private Integer rank;
        private String knowledgeBaseId;
        private String documentId;
        private String versionId;
        private Long generation;
        private String chunkId;
        private String headingPath;
        private Double denseScore;
        private Double sparseScore;
        private Double fusionScore;
        private Double rerankScore;
        private String outcome;
    }
}
