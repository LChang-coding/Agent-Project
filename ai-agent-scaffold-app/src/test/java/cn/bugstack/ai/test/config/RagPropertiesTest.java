package cn.bugstack.ai.test.config;

import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * RAG 配置边界测试。
 */
public class RagPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** 校验低资源默认值；验证 RAG 关闭时不要求密钥且保持 768 维。 */
    @Test
    public void shouldAcceptDisabledDefaults() {
        RagProperties properties = new RagProperties();

        Assert.assertFalse(properties.isEnabled());
        Assert.assertEquals(768, properties.getEmbedding().getDimension());
        Assert.assertEquals("ai_agent_rag_e5_v1", properties.getQdrant().getCollection());
        Assert.assertEquals("rag.ingest.request.v1", properties.getKafka().getTopic());
        Assert.assertFalse(properties.getKafka().isListenerEnabled());
        Assert.assertFalse(properties.getWorker().isEnabled());
        Assert.assertEquals(10, properties.getWorker().getScanBatchSize());
        Assert.assertFalse(properties.getAudit().isStoreQueryText());
        Assert.assertFalse(properties.getAudit().isStoreCitationContent());
        Assert.assertTrue(properties.getWorker().getLeaseDurationMs()
                > properties.getDocling().getTimeout().toMillis());
        Assert.assertTrue(validator.validate(properties).isEmpty());
    }

    /** 校验认证失败关闭；验证开启 RAG 后模型和解析服务不能缺少密钥。 */
    @Test
    public void shouldRejectEnabledConfigurationWithoutCredentials() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);

        Set<ConstraintViolation<RagProperties>> violations = validator.validate(properties);

        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "authenticationCompleteWhenEnabled".equals(violation.getPropertyPath().toString())));
    }

    /** 校验完整配置；验证合法端点、密钥和资源边界可通过校验。 */
    @Test
    public void shouldAcceptCompleteEnabledConfiguration() {
        RagProperties properties = enabledProperties();

        Assert.assertTrue(validator.validate(properties).isEmpty());
    }

    /** 校验 Qdrant 认证兼容；验证当前无 API Key 的联调部署可以启用 RAG。 */
    @Test
    public void shouldAllowQdrantWithoutApiKey() {
        RagProperties properties = enabledProperties();
        properties.getQdrant().setApiKey(null);

        Assert.assertTrue(validator.validate(properties).isEmpty());
    }

    /** 校验 Spring Boot 类型绑定；验证端点、时长和数值不以原始字符串流入业务代码。 */
    @Test
    public void shouldBindTypedConfigurationValues() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "ai.rag.enabled", "true",
                "ai.rag.embedding.endpoint", "https://rag.internal.example/embed",
                "ai.rag.embedding.timeout", "2500ms",
                "ai.rag.embedding.max-concurrency", "3",
                "ai.rag.embedding.batch-size", "24",
                "ai.rag.embedding.max-retries", "4",
                "ai.rag.embedding.retry-initial-backoff", "100ms",
                "ai.rag.embedding.retry-max-backoff", "1500ms",
                "ai.rag.embedding.dimension", "768",
                "ai.rag.kafka.listener-enabled", "true"
        ));

        RagProperties properties = new Binder(source).bind("ai.rag", RagProperties.class)
                .orElseThrow(() -> new AssertionError("RAG 配置未绑定"));

        Assert.assertTrue(properties.isEnabled());
        Assert.assertEquals(URI.create("https://rag.internal.example/embed"), properties.getEmbedding().getEndpoint());
        Assert.assertEquals(Duration.ofMillis(2500), properties.getEmbedding().getTimeout());
        Assert.assertEquals(3, properties.getEmbedding().getMaxConcurrency());
        Assert.assertEquals(24, properties.getEmbedding().getBatchSize());
        Assert.assertEquals(4, properties.getEmbedding().getMaxRetries());
        Assert.assertEquals(Duration.ofMillis(100), properties.getEmbedding().getRetryInitialBackoff());
        Assert.assertEquals(Duration.ofMillis(1500), properties.getEmbedding().getRetryMaxBackoff());
        Assert.assertEquals(768, properties.getEmbedding().getDimension());
        Assert.assertTrue(properties.getKafka().isListenerEnabled());
    }

    /** 校验连接与批处理边界；验证非 HTTP 端点、非正超时、并发和批次均会被拒绝。 */
    @Test
    public void shouldRejectInvalidRemoteAndBatchBoundaries() {
        RagProperties properties = enabledProperties();
        properties.getEmbedding().setEndpoint(URI.create("file:///tmp/model"));
        properties.getEmbedding().setTimeout(Duration.ZERO);
        properties.getEmbedding().setMaxConcurrency(0);
        properties.getEmbedding().setBatchSize(0);
        properties.getEmbedding().setDimension(0);
        properties.getEmbedding().setMaxRetries(-1);
        properties.getEmbedding().setRetryInitialBackoff(Duration.ofSeconds(2));
        properties.getEmbedding().setRetryMaxBackoff(Duration.ofSeconds(1));

        Set<ConstraintViolation<RagProperties>> violations = validator.validate(properties);

        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.httpEndpoint".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.timeoutPositive".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.maxConcurrency".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.batchSize".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.dimension".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.maxRetries".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "embedding.retryBackoffValid".equals(violation.getPropertyPath().toString())));
    }

    /** 校验日志脱敏；验证 toString 只输出密钥已配置状态。 */
    @Test
    public void shouldNotExposeSecretsInToString() {
        RagProperties properties = enabledProperties();
        String summary = properties.toString();

        Assert.assertFalse(summary.contains("qdrant-secret"));
        Assert.assertFalse(summary.contains("embedding-secret"));
        Assert.assertFalse(summary.contains("reranker-secret"));
        Assert.assertFalse(summary.contains("docling-secret"));
        Assert.assertTrue(summary.contains("apiKey=<configured>"));
    }

    /** 校验 Worker 租约、心跳、退避和分块预算的联合约束。 */
    @Test
    public void shouldRejectInvalidWorkerBoundaries() {
        RagProperties properties = enabledProperties();
        properties.getWorker().setLeaseDurationMs(15000L);
        properties.getWorker().setHeartbeatIntervalMs(8000L);
        properties.getWorker().setRetryBaseDelayMs(5000L);
        properties.getWorker().setRetryMaxDelayMs(1000L);
        properties.getWorker().setChildMaxChars(2000);
        properties.getWorker().setParentMaxChars(1000);

        Set<ConstraintViolation<RagProperties>> violations = validator.validate(properties);

        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "worker.heartbeatWithinLease".equals(violation.getPropertyPath().toString())));
        Assert.assertTrue(violations.stream().anyMatch(violation ->
                "worker.boundaryValid".equals(violation.getPropertyPath().toString())));
    }

    private RagProperties enabledProperties() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getQdrant().setApiKey("qdrant-secret");
        properties.getEmbedding().setApiKey("embedding-secret");
        properties.getReranker().setApiKey("reranker-secret");
        properties.getDocling().setApiKey("docling-secret");
        return properties;
    }
}
