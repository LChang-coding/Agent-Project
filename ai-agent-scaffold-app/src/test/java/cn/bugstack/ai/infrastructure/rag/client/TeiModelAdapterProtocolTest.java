package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort.EmbeddingCommand;
import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort.EmbeddingInputType;
import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort.EmbeddingResult;
import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort.Candidate;
import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort.RerankCommand;
import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort.RerankResult;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** TEI Embedding/Reranker 本地 HTTP 协议测试。 */
public class TeiModelAdapterProtocolTest {

    private static final String API_KEY = "protocol-test-api-key";
    private static final String RESPONSE_SECRET = "remote-body-must-not-leak";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final ConcurrentLinkedQueue<Integer> responseStatuses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<byte[]> responseBodies = new ConcurrentLinkedQueue<>();
    private volatile byte[] responseBody;
    private volatile Thread interruptAfterResponseThread;
    private HttpServer server;
    private RagProperties properties;
    private TeiEmbeddingAdapter embeddingAdapter;
    private TeiRerankerAdapter rerankerAdapter;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", this::handle);
        server.createContext("/rerank", this::handle);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        properties = new RagProperties();
        configure(properties.getEmbedding(), endpoint);
        configure(properties.getReranker(), endpoint);
        properties.getEmbedding().setDimension(768);
        properties.getEmbedding().setBatchSize(2);
        properties.getEmbedding().setRequestTimeout(Duration.ofSeconds(1));
        properties.getEmbedding().setRetryInitialBackoff(Duration.ofMillis(1));
        properties.getEmbedding().setRetryMaxBackoff(Duration.ofMillis(2));
        properties.getEmbedding().setModelRevision("embedding-revision");
        properties.getReranker().setBatchSize(3);
        properties.getReranker().setRequestBatchSize(3);
        properties.getReranker().setRequestTimeout(Duration.ofSeconds(1));
        properties.getReranker().setMaxRetries(0);
        properties.getReranker().setRetryInitialBackoff(Duration.ofMillis(1));
        properties.getReranker().setRetryMaxBackoff(Duration.ofMillis(2));
        properties.getReranker().setModelRevision("reranker-revision");
        embeddingAdapter = new TeiEmbeddingAdapter(properties, objectMapper);
        rerankerAdapter = new TeiRerankerAdapter(properties, objectMapper);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void embeddingShouldSendBearerAndE5PrefixesAndParseTwoDimensionalVectors() throws Exception {
        respondJson(objectMapper.writeValueAsString(List.of(vector(1F), vector(2F))));

        EmbeddingResult queryResult = embeddingAdapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("  first  ", "second")));

        Assert.assertEquals(2, queryResult.vectors().size());
        Assert.assertEquals(768, queryResult.dimensions());
        Assert.assertEquals("embedding-revision", queryResult.modelRevision());
        CapturedRequest queryRequest = requests.get(0);
        Assert.assertEquals("/embed", queryRequest.path());
        Assert.assertEquals("POST", queryRequest.method());
        Assert.assertEquals("Bearer " + API_KEY, queryRequest.authorization());
        Assert.assertEquals("application/json", queryRequest.contentType());
        Assert.assertEquals("application/json", queryRequest.accept());
        Assert.assertEquals(List.of("query: first", "query: second"), textArray(queryRequest.body(), "inputs"));

        respondJson(objectMapper.writeValueAsString(List.of(vector(3F))));
        embeddingAdapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.PASSAGE, List.of("  document  ")));
        Assert.assertEquals(List.of("passage: document"), textArray(requests.get(1).body(), "inputs"));
    }

    @Test
    public void embeddingShouldRejectBatchBeforeCallingRemoteService() {
        AppException exception = expectAppException(() -> embeddingAdapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("one", "two", "three"))));

        Assert.assertEquals("RAG_EMBEDDING_BATCH_TOO_LARGE", exception.getCode());
        Assert.assertTrue(requests.isEmpty());
    }

    @Test
    public void embeddingShouldRejectWrongDimensionAndNonFiniteNumber() throws Exception {
        respondJson(objectMapper.writeValueAsString(List.of(List.of(1F, 2F))));
        Assert.assertEquals("RAG_EMBEDDING_RESPONSE_INVALID", expectEmbeddingFailure().getCode());

        StringBuilder invalidVector = new StringBuilder("[[1e1000");
        invalidVector.append(",0".repeat(767)).append("]]");
        respondJson(invalidVector.toString());
        Assert.assertEquals("RAG_EMBEDDING_RESPONSE_INVALID", expectEmbeddingFailure().getCode());
    }

    @Test
    public void embeddingShouldBoundResponseAndReportNon200WithoutLeakingBodyOrKey() {
        responseBody = new byte[8 * 1024 * 1024 + 1];
        Arrays.fill(responseBody, (byte) 'x');
        Assert.assertEquals("RAG_REMOTE_RESPONSE_TOO_LARGE", expectEmbeddingFailure().getCode());

        status.set(503);
        respondJson("{\"detail\":\"" + RESPONSE_SECRET + " " + API_KEY + "\"}");
        AppException exception = expectEmbeddingFailure();
        Assert.assertEquals("RAG_EMBEDDING_TRANSIENT_HTTP_ERROR", exception.getCode());
        assertSensitiveValuesHidden(exception);
    }

    @Test
    public void embeddingShouldRetryOnlyTransientStatusesWithinBound() throws Exception {
        respondJson(objectMapper.writeValueAsString(List.of(vector(1F))));
        responseStatuses.addAll(List.of(429, 503, 200));

        EmbeddingResult result = embeddingAdapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("question")));

        Assert.assertEquals(1, result.vectors().size());
        Assert.assertEquals(3, requests.size());

        requests.clear();
        properties.getEmbedding().setMaxRetries(2);
        status.set(429);
        AppException exhausted = expectEmbeddingFailure();
        Assert.assertEquals("RAG_EMBEDDING_TRANSIENT_HTTP_ERROR", exhausted.getCode());
        Assert.assertEquals(3, requests.size());

        requests.clear();
        status.set(401);
        AppException nonTransient = expectEmbeddingFailure();
        Assert.assertEquals("RAG_EMBEDDING_HTTP_ERROR", nonTransient.getCode());
        Assert.assertEquals(1, requests.size());
    }

    @Test
    public void embeddingRetryShouldPreserveThreadInterruption() {
        status.set(429);
        respondJson("{}");
        interruptAfterResponseThread = Thread.currentThread();
        try {
            AppException exception = expectEmbeddingFailure();
            Assert.assertEquals("RAG_EMBEDDING_INTERRUPTED", exception.getCode());
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1, requests.size());
        } finally {
            interruptAfterResponseThread = null;
            Thread.interrupted();
        }
    }

    @Test
    public void embeddingShouldRetryHttpTimeoutAndThenSucceed() throws Exception {
        properties.getEmbedding().setMaxRetries(1);
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(200);
        Mockito.when(response.body()).thenReturn(new ByteArrayInputStream(
                objectMapper.writeValueAsBytes(List.of(vector(1F)))));
        Mockito.when(httpClient.send(Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenThrow(new HttpTimeoutException("timeout"))
                .thenReturn(response);
        TeiEmbeddingAdapter adapter = new TeiEmbeddingAdapter(properties, objectMapper, httpClient);

        EmbeddingResult result = adapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("question")));

        Assert.assertEquals(1, result.vectors().size());
        Mockito.verify(httpClient, Mockito.times(2)).send(
                Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any());
    }

    @Test
    public void embeddingShouldBoundConnectionIoRetries() throws Exception {
        properties.getEmbedding().setMaxRetries(2);
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        Mockito.when(httpClient.send(Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenThrow(new IOException("connection reset"));
        TeiEmbeddingAdapter adapter = new TeiEmbeddingAdapter(properties, objectMapper, httpClient);

        AppException exception = expectAppException(() -> adapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("question"))));

        Assert.assertEquals("RAG_EMBEDDING_UNAVAILABLE", exception.getCode());
        Mockito.verify(httpClient, Mockito.times(3)).send(
                Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any());
    }

    @Test
    public void rerankerShouldSendRealTeiFieldsAndMapIndexesToTopK() throws Exception {
        respondJson("[{\"index\":1,\"score\":0.95},{\"index\":0,\"score\":0.4},{\"index\":2,\"score\":0.1}]");

        RerankResult result = rerankerAdapter.rerank(rerankCommand(2));

        Assert.assertEquals(2, result.candidates().size());
        Assert.assertEquals("chunk-2", result.candidates().get(0).chunkId());
        Assert.assertEquals(0.95D, result.candidates().get(0).score(), 0D);
        Assert.assertEquals(1, result.candidates().get(0).rank());
        Assert.assertEquals("chunk-1", result.candidates().get(1).chunkId());
        Assert.assertEquals(2, result.candidates().get(1).rank());
        Assert.assertEquals("reranker-revision", result.modelRevision());
        CapturedRequest request = requests.get(0);
        Assert.assertEquals("/rerank", request.path());
        Assert.assertEquals("POST", request.method());
        Assert.assertEquals("Bearer " + API_KEY, request.authorization());
        Assert.assertEquals("application/json", request.contentType());
        Assert.assertEquals("application/json", request.accept());
        Assert.assertEquals("find answer", request.body().get("query").asText());
        Assert.assertEquals(List.of("text one", "text two", "text three"), textArray(request.body(), "texts"));
        Assert.assertFalse(request.body().get("return_text").asBoolean());
        Assert.assertFalse(request.body().get("raw_scores").asBoolean());
        Assert.assertEquals(4, request.body().size());
    }

    @Test
    public void rerankerShouldSplitTransportBatchesAndGloballyRankScores() {
        properties.getReranker().setBatchSize(7);
        properties.getReranker().setRequestBatchSize(3);
        queueJson("[{\"index\":1,\"score\":0.4},{\"index\":0,\"score\":0.9},{\"index\":2,\"score\":0.2}]");
        queueJson("[{\"index\":2,\"score\":0.95},{\"index\":0,\"score\":0.8},{\"index\":1,\"score\":0.1}]");
        queueJson("[{\"index\":0,\"score\":0.7}]");
        List<Candidate> candidates = List.of(
                new Candidate("chunk-1", "text 1"), new Candidate("chunk-2", "text 2"),
                new Candidate("chunk-3", "text 3"), new Candidate("chunk-4", "text 4"),
                new Candidate("chunk-5", "text 5"), new Candidate("chunk-6", "text 6"),
                new Candidate("chunk-7", "text 7"));

        RerankResult result = rerankerAdapter.rerank(
                new RerankCommand("tenant", "trace", "query", candidates, 4));

        Assert.assertEquals(List.of("chunk-6", "chunk-1", "chunk-4", "chunk-7"),
                result.candidates().stream().map(candidate -> candidate.chunkId()).toList());
        Assert.assertEquals(List.of(1, 2, 3, 4),
                result.candidates().stream().map(candidate -> candidate.rank()).toList());
        Assert.assertEquals(3, requests.size());
        Assert.assertEquals(List.of("text 1", "text 2", "text 3"), textArray(requests.get(0).body(), "texts"));
        Assert.assertEquals(List.of("text 4", "text 5", "text 6"), textArray(requests.get(1).body(), "texts"));
        Assert.assertEquals(List.of("text 7"), textArray(requests.get(2).body(), "texts"));
    }

    @Test
    public void rerankerShouldFailWholeOperationWhenLaterBatchFails() {
        properties.getReranker().setBatchSize(4);
        properties.getReranker().setRequestBatchSize(3);
        queueJson("[{\"index\":0,\"score\":0.9},{\"index\":1,\"score\":0.8},{\"index\":2,\"score\":0.7}]");
        queueJson("{\"error\":\"" + RESPONSE_SECRET + "\"}");
        responseStatuses.add(200);
        responseStatuses.add(429);
        List<Candidate> candidates = List.of(
                new Candidate("chunk-1", "text 1"), new Candidate("chunk-2", "text 2"),
                new Candidate("chunk-3", "text 3"), new Candidate("chunk-4", "text 4"));

        AppException exception = expectAppException(() -> rerankerAdapter.rerank(
                new RerankCommand("tenant", "trace", "query", candidates, 2)));

        Assert.assertEquals("RAG_RERANK_TRANSIENT_HTTP_ERROR", exception.getCode());
        Assert.assertEquals(2, requests.size());
        assertSensitiveValuesHidden(exception);
    }

    @Test
    public void rerankerShouldRetryTransientStatusAndBoundExhaustion() {
        properties.getReranker().setMaxRetries(2);
        responseStatuses.addAll(List.of(503, 200));
        queueJson("{}");
        queueJson("[{\"index\":0,\"score\":0.9},{\"index\":1,\"score\":0.8},{\"index\":2,\"score\":0.7}]");

        RerankResult result = rerankerAdapter.rerank(rerankCommand(1));

        Assert.assertEquals("chunk-1", result.candidates().get(0).chunkId());
        Assert.assertEquals(2, requests.size());

        requests.clear();
        status.set(503);
        respondJson("{}");
        AppException exhausted = expectRerankFailure();
        Assert.assertEquals("RAG_RERANK_TRANSIENT_HTTP_ERROR", exhausted.getCode());
        Assert.assertEquals(3, requests.size());
    }

    @Test
    public void rerankerShouldRetryHttpTimeoutAndThenSucceed() throws Exception {
        properties.getReranker().setMaxRetries(1);
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(200);
        Mockito.when(response.body()).thenReturn(new ByteArrayInputStream(
                "[{\"index\":0,\"score\":0.9},{\"index\":1,\"score\":0.8},{\"index\":2,\"score\":0.7}]"
                        .getBytes(StandardCharsets.UTF_8)));
        Mockito.when(httpClient.send(Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenThrow(new HttpTimeoutException("timeout"))
                .thenReturn(response);
        TeiRerankerAdapter adapter = new TeiRerankerAdapter(properties, objectMapper, httpClient);

        RerankResult result = adapter.rerank(rerankCommand(1));

        Assert.assertEquals("chunk-1", result.candidates().get(0).chunkId());
        Mockito.verify(httpClient, Mockito.times(2)).send(
                Mockito.any(), Mockito.<HttpResponse.BodyHandler<java.io.InputStream>>any());
    }

    @Test
    public void rerankerShouldRejectDuplicateOutOfBoundsAndMissingIndexes() {
        assertInvalidRerank("[{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.8}]");
        assertInvalidRerank("[{\"index\":3,\"score\":0.9}]");
        assertInvalidRerank("[{\"score\":0.9}]");
        assertInvalidRerank("[{\"index\":0}]");
    }

    @Test
    public void rerankerShouldRejectNonFiniteScoreAndBatchOverflow() {
        assertInvalidRerank("[{\"index\":0,\"score\":1e1000}]");

        List<Candidate> candidates = List.of(
                new Candidate("1", "one"), new Candidate("2", "two"),
                new Candidate("3", "three"), new Candidate("4", "four"));
        AppException exception = expectAppException(() -> rerankerAdapter.rerank(
                new RerankCommand("tenant", "trace", "query", candidates, 1)));
        Assert.assertEquals("RAG_RERANK_BATCH_TOO_LARGE", exception.getCode());
    }

    @Test
    public void rerankerShouldBoundResponseAndReportNon200WithoutLeakingBodyOrKey() {
        responseBody = new byte[4 * 1024 * 1024 + 1];
        Arrays.fill(responseBody, (byte) 'x');
        Assert.assertEquals("RAG_REMOTE_RESPONSE_TOO_LARGE", expectRerankFailure().getCode());

        status.set(401);
        respondJson("{\"detail\":\"" + RESPONSE_SECRET + " " + API_KEY + "\"}");
        AppException exception = expectRerankFailure();
        Assert.assertEquals("RAG_RERANK_HTTP_ERROR", exception.getCode());
        assertSensitiveValuesHidden(exception);
    }

    private void configure(RagProperties.RemoteService config, URI endpoint) {
        config.setEndpoint(endpoint);
        config.setApiKey(API_KEY);
        config.setTimeout(Duration.ofSeconds(5));
        config.setMaxConcurrency(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        JsonNode body = objectMapper.readTree(requestBody);
        requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Accept"), body));
        byte[] queuedBody = responseBodies.poll();
        byte[] currentResponse = queuedBody != null ? queuedBody
                : responseBody == null ? new byte[0] : responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        Integer queuedStatus = responseStatuses.poll();
        int responseStatus = queuedStatus == null ? status.get() : queuedStatus;
        try {
            exchange.sendResponseHeaders(responseStatus, currentResponse.length);
            exchange.getResponseBody().write(currentResponse);
        } catch (IOException ignored) {
            // 客户端超限后会主动关闭响应流。
        } finally {
            exchange.close();
            Thread thread = interruptAfterResponseThread;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private void respondJson(String json) {
        responseBody = json.getBytes(StandardCharsets.UTF_8);
    }

    private void queueJson(String json) {
        responseBodies.add(json.getBytes(StandardCharsets.UTF_8));
    }

    private List<Float> vector(float firstValue) {
        List<Float> vector = new ArrayList<>(768);
        vector.add(firstValue);
        for (int i = 1; i < 768; i++) {
            vector.add(0F);
        }
        return vector;
    }

    private List<String> textArray(JsonNode body, String field) {
        List<String> values = new ArrayList<>();
        body.get(field).forEach(node -> values.add(node.asText()));
        return values;
    }

    private RerankCommand rerankCommand(int topK) {
        return new RerankCommand("tenant", "trace", "find answer", List.of(
                new Candidate("chunk-1", "text one"),
                new Candidate("chunk-2", "text two"),
                new Candidate("chunk-3", "text three")), topK);
    }

    private AppException expectEmbeddingFailure() {
        return expectAppException(() -> embeddingAdapter.embed(new EmbeddingCommand(
                "tenant", "trace", EmbeddingInputType.QUERY, List.of("question"))));
    }

    private AppException expectRerankFailure() {
        return expectAppException(() -> rerankerAdapter.rerank(rerankCommand(1)));
    }

    private void assertInvalidRerank(String json) {
        respondJson(json);
        AppException exception = expectRerankFailure();
        Assert.assertEquals("RAG_RERANK_RESPONSE_INVALID", exception.getCode());
    }

    private AppException expectAppException(ThrowingRunnable runnable) {
        try {
            runnable.run();
            Assert.fail("预期适配器拒绝非法请求或响应");
            return null;
        } catch (AppException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("应转换为 AppException", e);
        }
    }

    private void assertSensitiveValuesHidden(AppException exception) {
        String rendered = exception.toString() + " " + exception.getInfo();
        Assert.assertFalse(rendered.contains(RESPONSE_SECRET));
        Assert.assertFalse(rendered.contains(API_KEY));
    }

    private record CapturedRequest(String path, String method, String authorization,
                                   String contentType, String accept, JsonNode body) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
