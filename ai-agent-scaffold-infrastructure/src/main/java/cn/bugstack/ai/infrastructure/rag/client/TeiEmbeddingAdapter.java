package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        acquire(concurrency, config.getTimeout(), "Embedding");
        try {
            List<String> inputs = command.inputs().stream()
                    .map(input -> prefix(command.inputType(), input)).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", inputs);
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.getEndpoint(), "embed"))
                    .timeout(config.getTimeout()).header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload))).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(response.body(), MAX_RESPONSE_BYTES);
            if (response.statusCode() != 200) {
                throw new AppException("RAG_EMBEDDING_HTTP_ERROR", "Embedding 服务返回状态 " + response.statusCode());
            }
            List<List<Float>> vectors = objectMapper.readValue(body, new TypeReference<>() { });
            validate(vectors, inputs.size(), config.getDimension());
            return new EmbeddingResult(vectors, config.getDimension(), config.getModelRevision());
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_EMBEDDING_INTERRUPTED", "Embedding 请求被中断", e);
        } catch (Exception e) {
            throw new AppException("RAG_EMBEDDING_UNAVAILABLE", "Embedding 服务调用失败", e);
        } finally {
            concurrency.release();
        }
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
}
