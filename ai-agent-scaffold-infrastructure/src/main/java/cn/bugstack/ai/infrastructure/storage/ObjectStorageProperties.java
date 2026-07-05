package cn.bugstack.ai.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.storage")
public class ObjectStorageProperties {

    /**
     * 存储类型：local/minio
     */
    private String type = "local";

    /**
     * 本地降级存储目录
     */
    private String localRoot = "./data/object-storage";

    /**
     * MinIO 配置
     */
    private Minio minio = new Minio();

    /**
     * MinIO 连接配置。
     */
    @Data
    public static class Minio {

        /**
         * MinIO 服务地址
         */
        private String endpoint = "http://69.165.65.123:9000";

        /**
         * MinIO access key
         */
        private String accessKey;

        /**
         * MinIO secret key
         */
        private String secretKey;

        /**
         * Skill 包存储桶
         */
        private String skillBucket = "ai-agent-skills";

        /**
         * 通用资产存储桶
         */
        private String assetBucket = "ai-agent-assets";
    }
}
