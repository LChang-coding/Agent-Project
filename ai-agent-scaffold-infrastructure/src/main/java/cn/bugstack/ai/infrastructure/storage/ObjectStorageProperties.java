package cn.bugstack.ai.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置。
 * <p>只承载运行参数；访问密钥必须由外部配置注入，禁止写入源码或日志。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.storage")
public class ObjectStorageProperties {

    /**
     * 存储类型：local/minio；其他值按本地模式处理。
     */
    private String type = "local";

    /**
     * 本地降级存储根目录；对象键必须被约束在该目录内。
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
         * MinIO 服务地址；环境间通过配置覆盖。
         */
        private String endpoint = "http://69.165.65.123:9000";

        /**
         * MinIO access key；仅从受控配置源注入。
         */
        private String accessKey;

        /**
         * MinIO secret key；不得输出到日志或接口。
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

        /**
         * RAG 原始文档存储桶
         */
        private String ragBucket = "ai-agent-rag";
    }
}
