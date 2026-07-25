package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 版本实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVersionEntity {

    /** 数据库主键。 */
    private Long id;
    /** 版本所属租户。 */
    private String tenantId;
    /** 定义所有者快照。 */
    private String ownerUserId;
    /** 关联 Skill 业务 ID。 */
    private String skillId;
    /** 版本记录稳定业务 ID。 */
    private String versionId;
    /** 用户可见版本号。 */
    private String version;
    /** 上传包资产业务 ID。 */
    private String assetId;
    /** 原包所在对象存储桶。 */
    private String bucket;
    /** 原包对象键。 */
    private String objectKey;
    /** 原始文件名。 */
    private String fileName;
    /** 原包 SHA-256。 */
    private String sha256;
    /** 原包字节数。 */
    private Long sizeBytes;
    /** 校验后的 Skill manifest JSON。 */
    private String manifestJson;
    /** 版本生命周期状态。 */
    private String status;
    /** 扩展元数据 JSON。 */
    private String metadata;
}
