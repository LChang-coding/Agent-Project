package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 包上传结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPackageUploadResultEntity {

    /**
     * 资产业务ID
     */
    private String assetId;

    /**
     * 存储桶
     */
    private String bucket;

    /**
     * 对象 Key
     */
    private String objectKey;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件 SHA-256 摘要
     */
    private String sha256;

    /**
     * 文件大小，单位字节
     */
    private Long sizeBytes;
}
