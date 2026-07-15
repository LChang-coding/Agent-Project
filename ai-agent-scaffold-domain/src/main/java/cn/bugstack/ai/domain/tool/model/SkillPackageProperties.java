package cn.bugstack.ai.domain.tool.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill 包解压边界配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.skill-package")
public class SkillPackageProperties {

    private int maxEntries = 256;
    private int maxEntryBytes = 1024 * 1024;
}
