package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流 Skill 工具实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowSkillToolEntity {

    /**
     * Skill 业务ID。
     */
    private String skillId;

    /**
     * Skill 名称。
     */
    private String skillName;

    /**
     * 来源类型：resource/directory/git/upload。
     */
    private String sourceType;

    /**
     * 来源地址。
     */
    private String sourceUri;
}
