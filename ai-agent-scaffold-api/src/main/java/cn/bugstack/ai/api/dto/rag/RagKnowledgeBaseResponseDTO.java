package cn.bugstack.ai.api.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 知识库响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagKnowledgeBaseResponseDTO {
    private String knowledgeBaseId;
    private String name;
    private String description;
    private String visibility;
    private String status;
    private Integer embeddingDimension;
    private Long currentGeneration;
    private Long revision;
}
