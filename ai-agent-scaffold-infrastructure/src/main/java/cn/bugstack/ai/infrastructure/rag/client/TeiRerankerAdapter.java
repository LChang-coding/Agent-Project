package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Hugging Face TEI Reranker HTTP 适配器。 */
@Component
public class TeiRerankerAdapter implements RerankerPort {

    private static final long MAX_RESPONSE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_QUERY_CHARS = 4096;
    private static final int MAX_CANDIDATE_CHARS = 16000;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Semaphore concurrency;

    @Autowired
    public TeiRerankerAdapter(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getReranker().getTimeout()).build());
    }

    TeiRerankerAdapter(RagProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.concurrency = new Semaphore(properties.getReranker().getMaxConcurrency(), true);
    }

    @Override
    public RerankResult rerank(RerankCommand command) {
        RagProperties.Reranker config = properties.getReranker();
        if (command.candidates().size() > config.getBatchSize()) {
            throw new AppException("RAG_RERANK_BATCH_TOO_LARGE", "重排候选超过服务上限");
        }
        if (command.query().length() > MAX_QUERY_CHARS || command.candidates().stream()
                .anyMatch(candidate -> candidate.text().length() > MAX_CANDIDATE_CHARS)) {
            throw new AppException("RAG_RERANK_TEXT_TOO_LARGE", "重排文本超过客户端安全上限");
        }
        TeiEmbeddingAdapter.requireApiKey(config.getApiKey());
        TeiEmbeddingAdapter.acquire(concurrency, config.getTimeout(), "Reranker");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", command.query());
            payload.put("texts", command.candidates().stream().map(Candidate::text).toList());
            payload.put("return_text", false);
            payload.put("raw_scores", false);
            HttpRequest request = HttpRequest.newBuilder(TeiEmbeddingAdapter.endpoint(config.getEndpoint(), "rerank"))
                    .timeout(config.getTimeout()).header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload))).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = TeiEmbeddingAdapter.readBounded(response.body(), MAX_RESPONSE_BYTES);
            if (response.statusCode() != 200) {
                throw new AppException("RAG_RERANK_HTTP_ERROR", "Reranker 服务返回状态 " + response.statusCode());
            }
            List<TeiScore> scores = objectMapper.readValue(body, new TypeReference<>() { });
            return new RerankResult(toCandidates(command, scores), config.getModelRevision());
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_RERANK_INTERRUPTED", "Reranker 请求被中断", e);
        } catch (Exception e) {
            throw new AppException("RAG_RERANK_UNAVAILABLE", "Reranker 服务调用失败", e);
        } finally {
            concurrency.release();
        }
    }

    private List<ScoredCandidate> toCandidates(RerankCommand command, List<TeiScore> scores) {
        if (scores == null || scores.size() < command.topK()) {
            throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回候选数量不足");
        }
        List<ScoredCandidate> result = new ArrayList<>();
        boolean[] seen = new boolean[command.candidates().size()];
        for (TeiScore score : scores) {
            if (score == null || score.index() == null || score.score() == null
                    || score.index() < 0 || score.index() >= command.candidates().size()
                    || seen[score.index()] || !Double.isFinite(score.score())) {
                throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回索引或分数非法");
            }
            seen[score.index()] = true;
            if (result.size() < command.topK()) {
                result.add(new ScoredCandidate(command.candidates().get(score.index()).chunkId(),
                        score.score(), result.size() + 1));
            }
        }
        if (result.size() != command.topK()) {
            throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回唯一候选不足");
        }
        return result;
    }

    private record TeiScore(Integer index, Double score) {
    }
}
