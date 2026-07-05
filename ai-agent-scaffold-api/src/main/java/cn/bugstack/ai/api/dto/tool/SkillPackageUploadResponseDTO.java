package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * Skill 包上传响应。
 */
@Data
public class SkillPackageUploadResponseDTO {

    /**
     * 资产ID。
     */
    private String assetId;

    /**
     * 存储桶。
     */
    private String bucket;

    /**
     * 对象 Key。
     */
    private String objectKey;

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * SHA-256 摘要。
     */
    private String sha256;

    /**
     * 文件大小。
     */
    private Long sizeBytes;
}
