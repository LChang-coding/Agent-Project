package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象存储写入结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageResultEntity {

    /**
     * 存储桶名称
     */
    private String bucket;

    /**
     * 对象 Key
     */
    private String objectKey;

    /**
     * 文件 SHA-256 摘要
     */
    private String sha256;

    /**
     * 文件大小，单位字节
     */
    private Long sizeBytes;
}
