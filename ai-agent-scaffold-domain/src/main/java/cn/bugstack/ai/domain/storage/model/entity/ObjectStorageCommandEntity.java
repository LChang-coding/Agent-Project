
package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象存储写入命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageCommandEntity {

    /**
     * 存储桶名称
     */
    private String bucket;

    /**
     * 对象 Key
     */
    private String objectKey;

    /**
     * 文件内容
     */
    private byte[] bytes;

    /**
     * 内容类型
     */
    private String contentType;
}
