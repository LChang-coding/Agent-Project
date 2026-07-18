package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 对象存储受控下载命令。
 * <p>通过受控根目录和相对路径限定落盘范围，并要求调用方提供已知字节上限。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageDownloadCommandEntity {

    /** 存储桶名称。 */
    private String bucket;

    /** 对象 Key。 */
    private String objectKey;

    /** 允许落盘的受控根目录。 */
    private Path targetRoot;

    /** 相对于受控根目录的目标路径。 */
    private Path relativeTargetPath;

    /** 最大允许下载字节数。 */
    private long maxBytes;
}
