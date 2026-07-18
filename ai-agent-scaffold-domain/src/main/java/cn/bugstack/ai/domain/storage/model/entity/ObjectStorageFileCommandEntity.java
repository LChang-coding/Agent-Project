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

    private String bucket;
    private String objectKey;
    private Path sourcePath;
    private long sizeBytes;
    private String contentType;
}
