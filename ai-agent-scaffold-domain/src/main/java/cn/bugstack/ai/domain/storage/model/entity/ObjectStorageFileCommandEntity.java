package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 基于暂存文件的对象存储写入命令，避免大文件进入 JVM 堆。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageFileCommandEntity {

    /** 目标存储桶。 */
    private String bucket;
    /** 目标对象键。 */
    private String objectKey;
    /** 受控暂存文件路径。 */
    private Path sourcePath;
    /** 调用方已知且必须匹配的文件长度。 */
    private long sizeBytes;
    /** 对象内容类型。 */
    private String contentType;
}
