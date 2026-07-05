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

    private Long id;
    private String tenantId;
    private String ownerUserId;
    private String visibility;
    private String skillId;
    private String skillName;
    private String skillCode;
    private String description;
    private String sourceType;
    private String sourceUri;
    private String version;
    private String currentVersion;
    private String publishedVersion;
    private String activeVersionId;
    private String status;
    private String metadata;
}
