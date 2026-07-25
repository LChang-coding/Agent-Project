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

    /** 单个压缩包允许的最大文件条目数，防止 Zip Bomb。 */
    private int maxEntries = 256;
    /** 单个解压条目的最大字节数。 */
    private int maxEntryBytes = 1024 * 1024;
}
