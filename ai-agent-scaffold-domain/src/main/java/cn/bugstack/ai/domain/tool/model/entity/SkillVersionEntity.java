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

    private Long id;
    private String tenantId;
    private String ownerUserId;
    private String skillId;
    private String versionId;
    private String version;
    private String assetId;
    private String bucket;
    private String objectKey;
    private String fileName;
    private String sha256;
    private Long sizeBytes;
    private String manifestJson;
    private String status;
    private String metadata;
}
