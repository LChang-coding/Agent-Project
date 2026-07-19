package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Hugging Face TEI Dense Embedding HTTP 适配器。 */
@Component
public class TeiEmbeddingAdapter implements EmbeddingPort {

    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Semaphore concurrency;

    @Autowired
    public TeiEmbeddingAdapter(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getEmbedding().getTimeout()).build());
    }

    TeiEmbeddingAdapter(RagProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.concurrency = new Semaphore(properties.getEmbedding().getMaxConcurrency(), true);
    }

    @Override
    public EmbeddingResult embed(EmbeddingCommand command) {
        RagProperties.Embedding config = properties.getEmbedding();
        if (command.inputs().size() > config.getBatchSize()) {
            throw new AppException("RAG_EMBEDDING_BATCH_TOO_LARGE", "Embedding 批次超过服务上限");
        }
        requireApiKey(config.getApiKey());
        try {
            List<String> inputs = command.inputs().stream()
                    .map(input -> prefix(command.inputType(), input)).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", inputs);
            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            long deadlineNanos = System.nanoTime() + config.getTimeout().toNanos();
            for (int attempt = 0; ; attempt++) {
                try {
                    RawResponse response = send(requestBody, deadlineNanos, config);
                    if (response.statusCode() == 200) {
                        List<List<Float>> vectors;
                        try {
                            vectors = objectMapper.readValue(response.body(), new TypeReference<>() { });
                        } catch (IOException e) {
                            throw new AppException("RAG_EMBEDDING_RESPONSE_INVALID",
                                    "Embedding返回内容不是合法JSON", e);
                        }
                        validate(vectors, inputs.size(), config.getDimension());
                        return new EmbeddingResult(vectors, config.getDimension(), config.getModelRevision());
                    }
                    boolean transientStatus = isTransientStatus(response.statusCode());
                    if (!transientStatus || attempt >= config.getMaxRetries()) {
                        throw new AppException(transientStatus
                                ? "RAG_EMBEDDING_TRANSIENT_HTTP_ERROR" : "RAG_EMBEDDING_HTTP_ERROR",
                                "Embedding 服务返回状态 " + response.statusCode()
                                        + "，尝试次数 " + (attempt + 1));
                    }
                } catch (HttpTimeoutException e) {
                    if (attempt >= config.getMaxRetries()) {
                        throw new AppException("RAG_EMBEDDING_TIMEOUT",
                                "Embedding单次请求超时，尝试次数 " + (attempt + 1), e);
                    }
                } catch (IOException e) {
                    if (attempt >= config.getMaxRetries()) {
                        throw new AppException("RAG_EMBEDDING_UNAVAILABLE",
                                "Embedding调用失败，尝试次数 " + (attempt + 1), e);
                    }
                }
                backoff(attempt, deadlineNanos, config);
            }
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_EMBEDDING_INTERRUPTED", "Embedding 请求被中断", e);
        } catch (Exception e) {
            throw new AppException("RAG_EMBEDDING_UNAVAILABLE", "Embedding 服务调用失败", e);
        }
    }

    private RawResponse send(byte[] requestBody, long deadlineNanos, RagProperties.Embedding config)
            throws Exception {
        Duration remaining = remaining(deadlineNanos);
        acquire(concurrency, remaining, "Embedding");
        try {
            Duration afterAcquire = remaining(deadlineNanos);
            Duration requestTimeout = afterAcquire.compareTo(config.getRequestTimeout()) < 0
                    ? afterAcquire : config.getRequestTimeout();
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.getEndpoint(), "embed"))
                    .timeout(requestTimeout).header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new RawResponse(response.statusCode(), readBounded(response.body(), MAX_RESPONSE_BYTES));
        } finally {
            concurrency.release();
        }
    }

    private void backoff(int retryIndex, long deadlineNanos, RagProperties.Embedding config)
            throws InterruptedException {
        long multiplier = 1L << Math.min(retryIndex, 30);
        long desiredNanos;
        try {
            desiredNanos = Math.multiplyExact(config.getRetryInitialBackoff().toNanos(), multiplier);
        } catch (ArithmeticException ignored) {
            desiredNanos = Long.MAX_VALUE;
        }
        desiredNanos = Math.min(desiredNanos, config.getRetryMaxBackoff().toNanos());
        long remainingNanos = remaining(deadlineNanos).toNanos();
        if (desiredNanos >= remainingNanos) {
            throw new AppException("RAG_EMBEDDING_TIMEOUT", "Embedding请求超过总时限");
        }
        Thread.sleep(desiredNanos / 1_000_000L, (int) (desiredNanos % 1_000_000L));
    }

    private Duration remaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new AppException("RAG_EMBEDDING_TIMEOUT", "Embedding请求超过总时限");
        }
        return Duration.ofNanos(remainingNanos);
    }

    private boolean isTransientStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private void validate(List<List<Float>> vectors, int expectedCount, int dimensions) {
        if (vectors == null || vectors.size() != expectedCount || vectors.stream().anyMatch(vector ->
                vector == null || vector.size() != dimensions || vector.stream().anyMatch(value ->
                        value == null || !Float.isFinite(value)))) {
            throw new AppException("RAG_EMBEDDING_RESPONSE_INVALID", "Embedding 返回数量或维度不一致");
        }
    }

    private String prefix(EmbeddingInputType type, String input) {
        return (type == EmbeddingInputType.QUERY ? "query: " : "passage: ") + input.trim();
    }

    static URI endpoint(URI base, String path) {
        return URI.create(base.toString().replaceAll("/+$", "") + "/" + path);
    }

    static byte[] readBounded(InputStream input, long maxBytes) throws Exception {
        try (input) {
            byte[] body = input.readNBytes(Math.toIntExact(maxBytes + 1));
            if (body.length > maxBytes) {
                throw new AppException("RAG_REMOTE_RESPONSE_TOO_LARGE", "RAG 远程响应超过安全上限");
            }
            return body;
        }
    }

    static void acquire(Semaphore semaphore, Duration timeout, String service) {
        try {
            if (!semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AppException("RAG_REMOTE_BUSY", service + " 并发槽位等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_REMOTE_INTERRUPTED", service + " 并发等待被中断", e);
        }
    }

    static void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AppException("RAG_REMOTE_AUTH_MISSING", "RAG 模型服务认证未配置");
        }
    }

    private record RawResponse(int statusCode, byte[] body) {
    }
}
