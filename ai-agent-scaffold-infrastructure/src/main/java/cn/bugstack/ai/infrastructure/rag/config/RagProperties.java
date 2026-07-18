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

    /** RAG Outbox 可靠发布配置。 */
    @Valid
    @NotNull
    private Outbox outbox = new Outbox();

    /** RAG 摄取 Worker 资源与恢复边界。 */
    @Valid
    @NotNull
    private Worker worker = new Worker();

    /** 检索审计正文留存策略；默认只留摘要和指标。 */
    @Valid
    @NotNull
    private Audit audit = new Audit();

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
                ", outbox=" + outbox +
                ", worker=" + worker +
                ", audit=" + audit +
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

        /** Sparse 倒排索引是否只常驻磁盘。 */
        private boolean sparseOnDisk = true;

        /** 单次 Qdrant 请求体上限。 */
        @Min(1024)
        private long maxRequestBytes = 16L * 1024 * 1024;

        /** 单次 Qdrant 响应体上限。 */
        @Min(1024)
        private long maxResponseBytes = 16L * 1024 * 1024;

        /** 检索 topK 硬上限。 */
        @Min(1)
        private int maxSearchTopK = 200;

        /** Hybrid 每路预取相对 topK 的倍数。 */
        @Min(1)
        private int prefetchMultiplier = 4;

        /** 创建 Qdrant 默认配置。 */
        public Qdrant() {
            setEndpoint(URI.create("http://127.0.0.1:6333"));
            setTimeout(Duration.ofSeconds(3));
            setMaxConcurrency(4);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Qdrant 配置。 */
        @Override
        public String toString() {
            return summary("Qdrant") + ", collection='" + collection + "', batchSize=" + batchSize
                    + ", sparseOnDisk=" + sparseOnDisk + ", maxSearchTopK=" + maxSearchTopK + '}';
        }
    }

    /** Embedding 配置。 */
    @Getter
    @Setter
    public static class Embedding extends RemoteService {

        /** 已部署模型不可变 revision。 */
        @NotBlank
        private String modelRevision = "d128750597153bb5987e10b1c3493a34e5a4502a";

        /** 向量维度，必须与固定模型 revision 一致。 */
        @Min(1)
        private int dimension = 768;

        /** 单批向量化文本数量。 */
        @Min(1)
        private int batchSize = 16;

        /** 创建 Embedding 默认配置。 */
        public Embedding() {
            setEndpoint(URI.create("http://127.0.0.1:8081"));
            setTimeout(Duration.ofSeconds(10));
            setMaxConcurrency(2);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Embedding 配置。 */
        @Override
        public String toString() {
            return summary("Embedding") + ", modelRevision='" + modelRevision
                    + "', dimension=" + dimension + ", batchSize=" + batchSize + '}';
        }
    }

    /** Reranker 配置。 */
    @Getter
    @Setter
    public static class Reranker extends RemoteService {

        /** 已部署模型不可变 revision。 */
        @NotBlank
        private String modelRevision = "2cfc18c9415c912f9d8155881c133215df768a70";

        /** 单批重排候选数量。 */
        @Min(1)
        private int batchSize = 16;

        /** 创建 Reranker 默认配置。 */
        public Reranker() {
            setEndpoint(URI.create("http://127.0.0.1:8082"));
            setTimeout(Duration.ofSeconds(10));
            setMaxConcurrency(2);
        }

        /** 输出脱敏摘要；无参数；返回不含密钥原文的 Reranker 配置。 */
        @Override
        public String toString() {
            return summary("Reranker") + ", modelRevision='" + modelRevision
                    + "', batchSize=" + batchSize + '}';
        }
    }

    /** Docling 配置。 */
    @Getter
    @Setter
    public static class Docling extends RemoteService {

        /** 部署解析器版本。 */
        @NotBlank
        private String parserRevision = "docling-serve-1.26.0";

        /** 单文档页数硬上限。 */
        @Min(1)
        private int maxPages = 500;

        /** 解析响应最大字节数。 */
        @Min(1024)
        private long maxResponseBytes = 64L * 1024 * 1024;

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
            return summary("Docling") + ", parserRevision='" + parserRevision
                    + "', batchSize=" + batchSize + ", maxPages=" + maxPages
                    + ", maxResponseBytes=" + maxResponseBytes + '}';
        }
    }

    /** RAG Kafka 配置。 */
    @Getter
    @Setter
    public static class Kafka {

        /** 摄取作业唤醒 Topic。 */
        @NotBlank
        private String topic = "rag.ingest.request.v1";

        /** 摄取 Worker 消费组。 */
        @NotBlank
        private String groupId = "ai-agent-rag-ingest";

        /** 是否启用 Kafka 低延迟唤醒；Worker 仍可独立依靠 MySQL 补偿扫描。 */
        private boolean listenerEnabled;

        /** 输出 Kafka 摘要；无参数；返回 Topic 信息。 */
        @Override
        public String toString() {
            return "Kafka{topic='" + topic + "', groupId='" + groupId
                    + "', listenerEnabled=" + listenerEnabled + "}";
        }
    }

    /** RAG Outbox 可靠发布边界。 */
    @Getter
    @Setter
    public static class Outbox {

        /** 是否启用发布器。 */
        private boolean enabled;

        /** 到期扫描间隔。 */
        @Min(10)
        private long pollDelayMs = 1000L;

        /** 单次扫描上限。 */
        @Min(1)
        private int batchSize = 20;

        /** 发布租约时长。 */
        @Min(1000)
        private long leaseDurationMs = 30000L;

        /** Kafka ACK 最长等待时间。 */
        @Min(100)
        private long ackTimeoutMs = 10000L;

        /** 首次重试基础延迟。 */
        @Min(1)
        private long retryBaseDelayMs = 1000L;

        /** 重试延迟上限。 */
        @Min(1)
        private long retryMaxDelayMs = 300000L;

        /** 抖动比例，取值 0 到 0.5。 */
        private double retryJitterRatio = 0.2D;

        /** 校验 ACK 必须在租约内完成。 */
        @AssertTrue(message = "RAG Outbox ACK超时必须小于租约时长")
        public boolean isAckWithinLease() {
            return ackTimeoutMs < leaseDurationMs;
        }

        /** 校验退避上限和抖动范围。 */
        @AssertTrue(message = "RAG Outbox重试退避配置不合法")
        public boolean isRetryBoundaryValid() {
            return retryMaxDelayMs >= retryBaseDelayMs
                    && retryJitterRatio >= 0D && retryJitterRatio <= 0.5D;
        }

        /** 输出不含敏感值的配置摘要。 */
        @Override
        public String toString() {
            return "Outbox{enabled=" + enabled + ", pollDelayMs=" + pollDelayMs
                    + ", batchSize=" + batchSize + ", leaseDurationMs=" + leaseDurationMs
                    + ", ackTimeoutMs=" + ackTimeoutMs + '}';
        }
    }

    /** RAG 摄取 Worker 配置；首期固定单线程。 */
    @Getter
    @Setter
    public static class Worker {

        /** 是否启动 Kafka/数据库摄取唤醒。 */
        private boolean enabled;

        /** 数据库恢复扫描间隔。 */
        @Min(100)
        private long pollDelayMs = 2000L;

        /** 每次扫描的任务标识数上限。 */
        @Min(1)
        private int scanBatchSize = 10;

        /** 任务租约，需覆盖单次 Docling 超时。 */
        @Min(15000)
        private long leaseDurationMs = 180000L;

        /** 心跳间隔。 */
        @Min(1000)
        private long heartbeatIntervalMs = 30000L;

        /** 可重试故障的初始退避。 */
        @Min(100)
        private long retryBaseDelayMs = 5000L;

        /** 可重试故障的最大退避。 */
        @Min(100)
        private long retryMaxDelayMs = 300000L;

        /** Child chunk 字符/近似 Token 上限。 */
        @Min(128)
        private int childMaxChars = 1800;
        @Min(64)
        private int childMaxTokens = 420;

        /** Parent chunk 字符/近似 Token 上限。 */
        @Min(256)
        private int parentMaxChars = 6000;
        @Min(128)
        private int parentMaxTokens = 1400;

        /** 超长块分割重叠字符数。 */
        @Min(0)
        private int overlapChars = 160;

        @AssertTrue(message = "RAG Worker心跳必须早于租约过期")
        public boolean isHeartbeatWithinLease() {
            return heartbeatIntervalMs * 2 < leaseDurationMs;
        }

        @AssertTrue(message = "RAG Worker重试退避或分块预算不合法")
        public boolean isBoundaryValid() {
            return retryMaxDelayMs >= retryBaseDelayMs
                    && childMaxChars <= parentMaxChars && childMaxTokens <= parentMaxTokens
                    && overlapChars < childMaxChars;
        }

        @Override
        public String toString() {
            return "Worker{enabled=" + enabled + ", pollDelayMs=" + pollDelayMs
                    + ", scanBatchSize=" + scanBatchSize + ", concurrency=1, leaseDurationMs="
                    + leaseDurationMs + ", heartbeatIntervalMs=" + heartbeatIntervalMs + '}';
        }
    }

    /** RAG 检索审计留存配置。 */
    @Getter
    @Setter
    public static class Audit {
        /** 默认关闭查询正文和引用正文持久化，降低敏感数据扩散。 */
        private boolean storeQueryText;
        private boolean storeCitationContent;

        @Override
        public String toString() {
            return "Audit{storeQueryText=" + storeQueryText
                    + ", storeCitationContent=" + storeCitationContent + '}';
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
