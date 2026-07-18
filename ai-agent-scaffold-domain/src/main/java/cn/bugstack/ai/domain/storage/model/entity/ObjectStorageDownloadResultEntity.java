package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 对象存储流式下载结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageDownloadResultEntity {

    /** 存储桶名称。 */
    private String bucket;

    /** 对象 Key。 */
    private String objectKey;

    /** 已原子发布的本地文件路径。 */
    private Path targetPath;

    /** 下载内容 SHA-256 摘要。 */
    private String sha256;

    /** 实际下载字节数。 */
    private long sizeBytes;
}
