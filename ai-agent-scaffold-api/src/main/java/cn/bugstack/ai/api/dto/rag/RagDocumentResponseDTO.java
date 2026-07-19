package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

/** 不暴露对象存储位置的 RAG 文档响应。 */
@Data
@Builder
public class RagDocumentResponseDTO {
    private String documentId;
    private String knowledgeBaseId;
    private String displayName;
    private String status;
    private String activeVersionId;
    private Long activeGeneration;
    private Long targetGeneration;
    private Long revision;
    private Integer pageCount;
    private Integer chunkCount;
}
