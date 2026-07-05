package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 新版本创建命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVersionCreateCommandEntity {

    /**
     * 操作用户上下文
     */
    private ToolUserContextEntity context;

    /**
     * Skill 业务ID
     */
    private String skillId;

    /**
     * 版本号
     */
    private String version;

    /**
     * 上传包资产ID
     */
    private String assetId;
}
