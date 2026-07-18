package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

/** RAG 文档上传受理响应。 */
@Data
@Builder
public class RagDocumentUploadResponseDTO {
    private String documentId;
    private String versionId;
    private String taskId;
    private String fileName;
    private Long sizeBytes;
    private String status;
    private Boolean deduplicated;
}
