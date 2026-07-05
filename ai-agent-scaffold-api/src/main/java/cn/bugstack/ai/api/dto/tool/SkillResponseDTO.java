package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * Skill 定义响应。
 */
@Data
public class SkillResponseDTO {

    /**
     * Skill ID。
     */
    private String skillId;

    /**
     * Skill 名称。
     */
    private String skillName;

    /**
     * Skill 编码。
     */
    private String skillCode;

    /**
     * Skill 描述。
     */
    private String description;

    /**
     * 可见范围。
     */
    private String visibility;

    /**
     * 当前版本。
     */
    private String currentVersion;

    /**
     * 已发布版本。
     */
    private String publishedVersion;

    /**
     * 状态。
     */
    private String status;
}
