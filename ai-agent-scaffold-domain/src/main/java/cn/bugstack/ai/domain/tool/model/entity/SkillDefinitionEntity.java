package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 定义实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinitionEntity {

    /** 数据库主键。 */
    private Long id;
    /** 定义所属租户。 */
    private String tenantId;
    /** private Skill 的所有者。 */
    private String ownerUserId;
    /** private 或 tenant_public。 */
    private String visibility;
    /** Skill 稳定业务 ID。 */
    private String skillId;
    /** 展示名称。 */
    private String skillName;
    /** 租户内可引用编码。 */
    private String skillCode;
    /** 人类可读用途说明。 */
    private String description;
    /** 包来源类型。 */
    private String sourceType;
    /** 当前源资产定位。 */
    private String sourceUri;
    /** 兼容字段：当前版本号。 */
    private String version;
    /** 最近编辑版本号。 */
    private String currentVersion;
    /** 已发布版本号。 */
    private String publishedVersion;
    /** 当前激活版本记录 ID。 */
    private String activeVersionId;
    /** 定义生命周期状态。 */
    private String status;
    /** 扩展元数据 JSON。 */
    private String metadata;
}
