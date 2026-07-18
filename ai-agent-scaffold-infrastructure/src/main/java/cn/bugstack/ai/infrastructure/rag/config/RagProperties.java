package cn.bugstack.ai.infrastructure.rag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * RAG 基础设施配置。
 * <p>统一约束 Qdrant、Embedding、Reranker、Docling 和摄取事件的连接与资源边界。</p>
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

    /** RAG 总开关。 */
    private boolean enabled;

    /** Qdrant 连接和写入边界。 */
    @Valid
    @NotNull
    private Qdrant qdrant = new Qdrant();

    /** Embedding 服务连接和批处理边界。 */
    @Valid
    @NotNull
    private Embedding embedding = new Embedding();

    /** Reranker 服务连接和批处理边界。 */
    @Valid
    @NotNull
    private Reranker reranker = new Reranker();

    /** Docling 服务连接和文档解析边界。 */
    @Valid
    @NotNull
    private Docling docling = new Docling();

    /** RAG 摄取事件配置。 */
    @Valid
    @NotNull
    private Kafka kafka = new Kafka();

    /**
     * 校验启用时的认证边界；无参数；返回是否可以安全连接全部 RAG 服务。
     */
    @AssertTrue(message = "RAG 启用时必须为 Embedding、Reranker 和 Docling 配置认证密钥")
    public boolean isAuthenticationCompleteWhenEnabled() {
        // Qdrant 是否启用认证由部署环境决定；当前联调服务器按明确要求允许匿名访问。
        return !enabled || hasText(embedding.getApiKey())
                && hasText(reranker.getApiKey())
                && hasText(docling.getApiKey());
    }

    /**
     * 输出脱敏配置摘要；无参数；返回不含密钥原文的配置信息。
     */
    @Override
    public String toString() {
        return "RagProperties{" +
                "enabled=" + enabled +
                ", qdrant=" + qdrant +
                ", embedding=" + embedding +
                ", reranker=" + reranker +
                ", docling=" + docling +
                ", kafka=" + kafka +
                '}';
    }

    /** Qdrant 配置。 */
    @Getter
    @Setter
    public static class Qdrant extends RemoteService {

        /** 向量集名称。 */
        @NotBlank
        private String collection = "ai_agent_rag_e5_v1";

        /** 单批 upsert 的 point 数量。 */
        @Min(1)
        private int batchSize = 64;

        /** 创建 Qdrant 默认配置。 */
        public Qdrant() {
            setEndpoint(URI.create("http://127.0.0.1:6333"));
            setTimeout(Duration.ofSeconds(3));
            setMaxConcurrency(4);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Qdrant 配置。 */
        @Override
        public String toString() {
            return summary("Qdrant") + ", collection='" + collection + "', batchSize=" + batchSize + '}';
        }
    }

    /** Embedding 配置。 */
    @Getter
    @Setter
    public static class Embedding extends RemoteService {

        /** 向量维度，必须与固定模型 revision 一致。 */
        @Min(1)
        private int dimension = 768;

        /** 单批向量化文本数量。 */
        @Min(1)
        private int batchSize = 32;

        /** 创建 Embedding 默认配置。 */
        public Embedding() {
            setEndpoint(URI.create("http://127.0.0.1:8081"));
            setTimeout(Duration.ofSeconds(10));
            setMaxConcurrency(2);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Embedding 配置。 */
        @Override
        public String toString() {
            return summary("Embedding") + ", dimension=" + dimension + ", batchSize=" + batchSize + '}';
        }
    }

    /** Reranker 配置。 */
    @Getter
    @Setter
    public static class Reranker extends RemoteService {

        /** 单批重排候选数量。 */
        @Min(1)
        private int batchSize = 32;

        /** 创建 Reranker 默认配置。 */
        public Reranker() {
            setEndpoint(URI.create("http://127.0.0.1:8082"));
            setTimeout(Duration.ofSeconds(10));
            setMaxConcurrency(2);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Reranker 配置。 */
        @Override
        public String toString() {
            return summary("Reranker") + ", batchSize=" + batchSize + '}';
        }
    }

    /** Docling 配置。 */
    @Getter
    @Setter
    public static class Docling extends RemoteService {

        /** 单次解析的文档数量，首期保持为 1。 */
        @Min(1)
        private int batchSize = 1;

        /** 创建 Docling 默认配置。 */
        public Docling() {
            setEndpoint(URI.create("http://127.0.0.1:5001/v1"));
            setTimeout(Duration.ofSeconds(120));
            setMaxConcurrency(1);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Docling 配置。 */
        @Override
        public String toString() {
            return summary("Docling") + ", batchSize=" + batchSize + '}';
        }
    }

    /** RAG Kafka 配置。 */
    @Getter
    @Setter
    public static class Kafka {

        /** 摄取作业唤醒 Topic。 */
        @NotBlank
        private String topic = "rag.ingest.request.v1";

        /** 输出 Kafka 摘要；无参数；返回 Topic 信息。 */
        @Override
        public String toString() {
            return "Kafka{topic='" + topic + "'}";
        }
    }

    /** RAG 远程服务通用边界。 */
    @Getter
    @Setter
    public abstract static class RemoteService {

        /** 服务端点。 */
        @NotNull
        private URI endpoint;

        /** 服务密钥，只能由运行环境注入。 */
        private String apiKey;

        /** 单次远程请求超时。 */
        @NotNull
        private Duration timeout;

        /** 该服务最大并发请求数。 */
        @Min(1)
        private int maxConcurrency = 1;

        /**
         * 校验端点协议；无参数；返回是否为 HTTP(S) 地址。
         */
        @AssertTrue(message = "RAG 远程服务端点必须使用 http 或 https")
        public boolean isHttpEndpoint() {
            return endpoint != null && ("http".equalsIgnoreCase(endpoint.getScheme())
                    || "https".equalsIgnoreCase(endpoint.getScheme()));
        }

        /**
         * 校验超时边界；无参数；返回超时是否为正数。
         */
        @AssertTrue(message = "RAG 远程服务超时必须大于 0")
        public boolean isTimeoutPositive() {
            return timeout != null && !timeout.isZero() && !timeout.isNegative();
        }

        /**
         * 构造脱敏服务摘要；参数是服务名；返回不含密钥原文的摘要前缀。
         */
        protected String summary(String serviceName) {
            return serviceName + "{" +
                    "endpoint=" + endpoint +
                    ", apiKey=" + (hasText(apiKey) ? "<configured>" : "<empty>") +
                    ", timeout=" + timeout +
                    ", maxConcurrency=" + maxConcurrency;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
