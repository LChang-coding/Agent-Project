package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 创建命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCreateCommandEntity {

    /**
     * 操作用户上下文
     */
    private ToolUserContextEntity context;

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
     * 可见范围
     */
    private String visibility;

    /**
     * 版本号
     */
    private String version;

    /**
     * 上传包资产ID
     */
    private String assetId;
}
