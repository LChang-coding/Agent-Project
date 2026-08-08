package cn.bugstack.ai.domain.rag.model.entity;

/**
 * 文档上传受理结果，不暴露对象存储内部位置。
 *
 * @param documentId 逻辑文档标识
 * @param versionId 本次上传创建的不可变版本标识
 * @param taskId 后台摄取任务标识
 * @param fileName 通过安全校验后的文件名
 * @param sizeBytes 服务端实际读取的文件字节数
 * @param status 当前摄取任务状态
 * @param deduplicated 是否由已存在的幂等任务返回
 */
public record RagDocumentUploadResult(String documentId, String versionId, String taskId,
                                      String fileName, long sizeBytes, String status, boolean deduplicated) {
}
