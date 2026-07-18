package cn.bugstack.ai.domain.rag.model.valobj;

import java.nio.file.Path;

/**
 * 已落入受控临时目录、等待安全校验的上传文件。
 *
 * @param path 受控临时文件路径
 * @param declaredSize 请求声明的文件长度
 * @param originalFileName 原始文件名
 * @param declaredMimeType 请求声明的 MIME
 */
public record RagUploadFileCandidate(Path path,
                                     long declaredSize,
                                     String originalFileName,
                                     String declaredMimeType) {

    public RagUploadFileCandidate {
        if (path == null || declaredSize < 0) {
            throw new IllegalArgumentException("RAG 上传文件参数非法");
        }
    }
}
