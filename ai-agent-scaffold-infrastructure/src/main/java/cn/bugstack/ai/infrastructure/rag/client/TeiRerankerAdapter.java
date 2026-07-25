package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Hugging Face TEI Reranker HTTP 适配器。 */
@Component
public class TeiRerankerAdapter implements RerankerPort {

    /** 限制响应体以及送入模型的查询、候选文本长度。 */
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
    /** 分批重排并恢复全局索引；任一批协议异常都拒绝半结果。 */
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
        long deadlineNanos = System.nanoTime() + config.getTimeout().toNanos();
        TeiEmbeddingAdapter.acquire(concurrency, config.getTimeout(), "Reranker");
        try {
            List<IndexedScore> scores = new ArrayList<>(command.candidates().size());
            for (int start = 0; start < command.candidates().size(); start += config.getRequestBatchSize()) {
                int end = Math.min(start + config.getRequestBatchSize(), command.candidates().size());
                scores.addAll(requestBatchWithRetry(command.query(), command.candidates().subList(start, end), start,
                        deadlineNanos, config));
            }
            return new RerankResult(toCandidates(command, scores), config.getModelRevision());
        } catch (AppException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new AppException("RAG_RERANK_TIMEOUT", "Reranker 请求超过总时限", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_RERANK_INTERRUPTED", "Reranker 请求被中断", e);
        } catch (Exception e) {
            throw new AppException("RAG_RERANK_UNAVAILABLE", "Reranker 服务调用失败", e);
        } finally {
            concurrency.release();
        }
    }

    /** 仅对超时、连接错误和瞬态 HTTP 状态重试。 */
    private List<IndexedScore> requestBatchWithRetry(String query, List<Candidate> candidates, int offset,
                                                     long deadlineNanos, RagProperties.Reranker config)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("texts", candidates.stream().map(Candidate::text).toList());
        payload.put("return_text", false);
        payload.put("raw_scores", false);
        byte[] requestBody = objectMapper.writeValueAsBytes(payload);
        int attempt = 0;
        while (true) {
            try {
                RawResponse response = sendBatch(requestBody, deadlineNanos, config);
                if (response.statusCode() == 200) {
                    return parseScores(response.body(), candidates.size(), offset);
                }
                if (!isTransient(response.statusCode()) || attempt >= config.getMaxRetries()) {
                    String code = isTransient(response.statusCode())
                            ? "RAG_RERANK_TRANSIENT_HTTP_ERROR" : "RAG_RERANK_HTTP_ERROR";
                    throw new AppException(code, "Reranker 服务返回状态 " + response.statusCode()
                            + "，尝试次数 " + (attempt + 1));
                }
            } catch (HttpTimeoutException e) {
                if (attempt >= config.getMaxRetries()) {
                    throw new AppException("RAG_RERANK_TIMEOUT",
                            "Reranker 单批请求超时，尝试次数 " + (attempt + 1), e);
                }
            } catch (IOException e) {
                if (attempt >= config.getMaxRetries()) {
                    throw new AppException("RAG_RERANK_UNAVAILABLE",
                            "Reranker 单批调用失败，尝试次数 " + (attempt + 1), e);
                }
            }
            backoff(attempt++, deadlineNanos, config);
        }
    }

    private RawResponse sendBatch(byte[] requestBody, long deadlineNanos, RagProperties.Reranker config)
            throws Exception {
        Duration remaining = remaining(deadlineNanos);
        Duration requestTimeout = remaining.compareTo(config.getRequestTimeout()) < 0
                ? remaining : config.getRequestTimeout();
        HttpRequest request = HttpRequest.newBuilder(TeiEmbeddingAdapter.endpoint(config.getEndpoint(), "rerank"))
                .timeout(requestTimeout).header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new RawResponse(response.statusCode(),
                TeiEmbeddingAdapter.readBounded(response.body(), MAX_RESPONSE_BYTES));
    }

    /** 校验 TEI 返回索引唯一且位于当前批次范围内。 */
    private List<IndexedScore> parseScores(byte[] body, int candidateCount, int offset) {
        List<TeiScore> values;
        try {
            values = objectMapper.readValue(body, new TypeReference<>() { });
        } catch (IOException e) {
            throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回内容不是合法JSON", e);
        }
        if (values == null || values.size() != candidateCount) {
            throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回候选数量不一致");
        }
        List<IndexedScore> result = new ArrayList<>(values.size());
        boolean[] seen = new boolean[candidateCount];
        for (TeiScore score : values) {
            if (score == null || score.index() == null || score.score() == null
                    || score.index() < 0 || score.index() >= candidateCount
                    || seen[score.index()] || !Double.isFinite(score.score())) {
                throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回索引或分数非法");
            }
            seen[score.index()] = true;
            result.add(new IndexedScore(offset + score.index(), score.score()));
        }
        return result;
    }

    private boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private void backoff(int retryIndex, long deadlineNanos, RagProperties.Reranker config)
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
            throw new AppException("RAG_RERANK_TIMEOUT", "Reranker 请求超过总时限");
        }
        Thread.sleep(desiredNanos / 1_000_000L, (int) (desiredNanos % 1_000_000L));
    }

    /** 按得分降序还原原候选，不覆盖其他召回通道证据。 */
    private List<ScoredCandidate> toCandidates(RerankCommand command, List<IndexedScore> scores) {
        if (scores.size() != command.candidates().size()) {
            throw new AppException("RAG_RERANK_RESPONSE_INVALID", "Reranker 返回候选数量不足");
        }
        scores.sort(Comparator.comparingDouble(IndexedScore::score).reversed()
                .thenComparingInt(IndexedScore::candidateIndex));
        List<ScoredCandidate> result = new ArrayList<>(command.topK());
        for (int index = 0; index < command.topK(); index++) {
            IndexedScore score = scores.get(index);
            result.add(new ScoredCandidate(command.candidates().get(score.candidateIndex()).chunkId(),
                    score.score(), index + 1));
        }
        return List.copyOf(result);
    }

    private Duration remaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new AppException("RAG_RERANK_TIMEOUT", "Reranker 请求超过总时限");
        }
        return Duration.ofNanos(remainingNanos);
    }

    private record TeiScore(Integer index, Double score) {
    }

    private record RawResponse(int statusCode, byte[] body) {
    }

    private record IndexedScore(int candidateIndex, double score) {
    }
}
