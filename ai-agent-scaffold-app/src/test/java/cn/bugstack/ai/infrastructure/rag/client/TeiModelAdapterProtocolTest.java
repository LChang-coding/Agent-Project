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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** TEI Embedding/Reranker 本地 HTTP 协议测试。 */
public class TeiModelAdapterProtocolTest {

    private static final String API_KEY = "protocol-test-api-key";
    private static final String RESPONSE_SECRET = "remote-body-must-not-leak";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile byte[] responseBody;
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
        properties.getEmbedding().setModelRevision("embedding-revision");
        properties.getReranker().setBatchSize(3);
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
        Assert.assertEquals("RAG_EMBEDDING_HTTP_ERROR", exception.getCode());
        assertSensitiveValuesHidden(exception);
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
        byte[] currentResponse = responseBody == null ? new byte[0] : responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status.get(), currentResponse.length);
            exchange.getResponseBody().write(currentResponse);
        } catch (IOException ignored) {
            // 客户端超限后会主动关闭响应流。
        } finally {
            exchange.close();
        }
    }

    private void respondJson(String json) {
        responseBody = json.getBytes(StandardCharsets.UTF_8);
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
