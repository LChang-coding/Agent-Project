package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Skill 稳定定义及草稿、发布、激活版本指针。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SkillDefinitionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * Skill 拥有者用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * Skill 业务ID
     */
    private String skillId;

    /**
     * Skill 名称
     */
    private String skillName;

    /**
     * Skill 编码
     */
    private String skillCode;

    /**
     * Skill 描述
     */
    private String description;

    /**
     * 来源类型：builtin/upload/git/markdown
     */
    private String sourceType;

    /**
     * 来源地址
     */
    private String sourceUri;

    /**
     * 版本号
     */
    private String version;

    /**
     * 当前草稿版本号
     */
    private String currentVersion;

    /**
     * 当前发布版本号
     */
    private String publishedVersion;

    /**
     * 当前生效版本业务ID
     */
    private String activeVersionId;

    /**
     * Skill 状态：draft/active/disabled/archived/pending_review
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
