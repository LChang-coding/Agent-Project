package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

/** 隐藏租约、fencing 和内部异常的摄取任务响应。 */
@Data
@Builder
public class RagIngestTaskResponseDTO {
    private String taskId;
    private String knowledgeBaseId;
    private String documentId;
    private String versionId;
    private String operation;
    private String stage;
    private String status;
    private Integer processedChunks;
    private Integer totalChunks;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String errorCode;
    private String cancelReason;
    private Long revision;
}
