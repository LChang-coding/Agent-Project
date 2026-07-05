package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * Skill 版本创建请求。
 */
@Data
public class SkillVersionCreateRequestDTO {

    /**
     * 版本号。
     */
    private String version;

    /**
     * 已上传包资产ID。
     */
    private String assetId;
}
