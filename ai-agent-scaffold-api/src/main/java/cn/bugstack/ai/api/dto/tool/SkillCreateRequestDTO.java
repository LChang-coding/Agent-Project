package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * Skill 创建请求。
 */
@Data
public class SkillCreateRequestDTO {

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
     * 可见范围：private/tenant_public。
     */
    private String visibility;

    /**
     * 版本号。
     */
    private String version;

    /**
     * 已上传包资产ID。
     */
    private String assetId;
}
