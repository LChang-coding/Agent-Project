package cn.bugstack.ai.api.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagKnowledgeBaseDeleteTaskResponseDTO {
    private String taskId;
    private String knowledgeBaseId;
    private String requestedByUserId;
    private String status;
    private String stage;
    private Integer totalDocuments;
    private Integer completedDocuments;
    private String currentDocumentId;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextRetryAt;
    private String errorCode;
    private String errorMessage;
    private Long revision;
}
