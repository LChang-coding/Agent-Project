package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Skill 版本持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SkillVersionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 版本发布用户ID
     */
    private String ownerUserId;

    /**
     * Skill 业务ID
     */
    private String skillId;

    /**
     * Skill 版本业务ID
     */
    private String versionId;

    /**
     * 版本号
     */
    private String version;

    /**
     * 关联 artifact_asset 业务ID
     */
    private String assetId;

    /**
     * Skill 包对象存储桶
     */
    private String bucket;

    /**
     * Skill 包对象 Key
     */
    private String objectKey;

    /**
     * 原始文件名
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

    /**
     * SKILL.md front matter 或解析结果
     */
    private String manifestJson;

    /**
     * 版本状态：draft/active/disabled/archived
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
