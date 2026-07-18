package cn.bugstack.ai.domain.rag.model.valobj;

import java.nio.file.Path;

/**
 * 通过格式和内容校验的 RAG 上传文件。
 *
 * @param path 受控临时文件的规范化绝对路径
 * @param safeFileName 规范化安全文件名
 * @param extension 规范化扩展名
 * @param mimeType 由扩展名与 magic 共同确认的可信 MIME
 * @param sizeBytes 实际文件长度
 */
public record RagValidatedUploadFile(Path path,
                                     String safeFileName,
                                     String extension,
                                     String mimeType,
                                     long sizeBytes) {

    public RagValidatedUploadFile {
        if (path == null || !path.isAbsolute() || safeFileName == null || safeFileName.isBlank()
                || extension == null || extension.isBlank() || mimeType == null || mimeType.isBlank()
                || sizeBytes < 1) {
            throw new IllegalArgumentException("已校验 RAG 文件参数非法");
        }
    }
}
