package cn.bugstack.ai.domain.rag.model.entity;

/** 文档上传受理结果，不暴露对象存储内部位置。 */
public record RagDocumentUploadResult(String documentId, String versionId, String taskId,
                                      String fileName, long sizeBytes, String status, boolean deduplicated) {
}
