package cn.bugstack.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 最终回答的 RAG 引用校验摘要。 */
@Data
@Builder
public class RagCitationValidationDTO {
    private String status;
    private List<String> retrievalIds;
    private List<String> allowedCitationIds;
    private List<String> usedCitationIds;
    private List<String> invalidCitationIds;
    private List<CitationDTO> citations;

    /** 可安全展示的引用身份，不包含正文和存储位置。 */
    @Data
    @Builder
    public static class CitationDTO {
        private String citationId;
        private String knowledgeBaseId;
        private String documentId;
        private String documentName;
        private String versionId;
        private Integer documentVersion;
        private Long generation;
        private String chunkId;
        private Integer pageNumber;
        private String headingPath;
    }
}
