package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Qdrant 1.18.2 REST 本地协议契约测试。 */
public class QdrantVectorStoreAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldIdempotentlyCreateNamedDenseAndSparseCollectionWithoutApiKey() throws Exception {
        AtomicInteger collectionReads = new AtomicInteger();
        try (Fixture fixture = fixture(exchange -> {
            if ("GET".equals(exchange.getRequestMethod()) && collectionReads.getAndIncrement() == 0) {
                respond(exchange, 404, "{}"); return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, okOperation()); return;
            }
            respond(exchange, 200, schema(true));
        })) {
            fixture.adapter.ensureCollection();
            fixture.adapter.ensureCollection();
            Assert.assertEquals(3, fixture.requests.size());
            JsonNode body = fixture.requests.get(1).body();
            Assert.assertEquals(768, body.path("vectors").path("dense").path("size").asInt());
            Assert.assertEquals("Cosine", body.path("vectors").path("dense").path("distance").asText());
            Assert.assertTrue(body.path("sparse_vectors").path("sparse").path("index").path("on_disk").asBoolean());
            Assert.assertNull(fixture.requests.get(1).apiKey());
        }
    }

    @Test
    public void shouldUpsertWaitAndOverwriteForgedTrustedPayloadWithDeterministicUuid() throws Exception {
        try (Fixture fixture = fixture(this::schemaThenOperation)) {
            SparseEncoderPort.SparseVector sparse = new SparseEncoderPort.SparseVector(
                    new LinkedHashMap<>(Map.of(3, 0.8F, 8, 0.6F)));
            VectorStorePort.VectorPoint point = new VectorStorePort.VectorPoint("chunk-business-id", "kb-1",
                    "doc-1", "version-1", 4L, "chunk-1", dense(), sparse,
                    Map.of("tenant_id", "forged", "kb_id", "forged", "custom", "kept"));

            fixture.adapter.upsert("tenant-a", "version-1", List.of(point));

            Request request = fixture.requests.get(1);
            Assert.assertEquals("PUT", request.method());
            Assert.assertTrue(request.uri().endsWith("/points?wait=true"));
            JsonNode row = request.body().path("points").get(0);
            Assert.assertTrue(row.path("id").asText().matches("[0-9a-f-]{36}"));
            Assert.assertEquals(768, row.path("vector").path("dense").size());
            Assert.assertEquals(List.of(3, 8), objectMapper.convertValue(
                    row.path("vector").path("sparse").path("indices"), List.class));
            Assert.assertEquals("tenant-a", row.path("payload").path("tenant_id").asText());
            Assert.assertEquals("kb-1", row.path("payload").path("kb_id").asText());
            Assert.assertEquals("chunk-business-id", row.path("payload").path("point_id").asText());
            Assert.assertEquals("kept", row.path("payload").path("custom").asText());
        }
    }

    @Test
    public void shouldDeleteAndCountOnlyByTrustedTenantAndVersionFilters() throws Exception {
        try (Fixture fixture = fixture(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) respond(exchange, 200, schema(true));
            else if (exchange.getRequestURI().getPath().endsWith("/count"))
                respond(exchange, 200, "{\"status\":\"ok\",\"result\":{\"count\":17}}");
            else respond(exchange, 200, okOperation());
        })) {
            fixture.adapter.deleteVersion("tenant-a", "version-1");
            long count = fixture.adapter.countVersion("tenant-a", "version-1");

            Assert.assertEquals(17L, count);
            JsonNode deleteBody = fixture.requests.get(1).body();
            JsonNode countBody = fixture.requests.get(2).body();
            assertTenantVersionFilter(deleteBody.path("filter"));
            assertTenantVersionFilter(countBody.path("filter"));
            Assert.assertTrue(countBody.path("exact").asBoolean());
            Assert.assertTrue(fixture.requests.get(1).uri().endsWith("/delete?wait=true"));
            Assert.assertTrue(fixture.requests.get(2).uri().endsWith("/count"));
        }
    }

    @Test
    public void shouldUseHybridQueryPrefetchRrfAndRejectOutOfScopeResponse() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        try (Fixture fixture = fixture(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) { respond(exchange, 200, schema(true)); return; }
            String tenant = searches.getAndIncrement() == 0 ? "tenant-a" : "tenant-b";
            respond(exchange, 200, searchResponse(tenant));
        })) {
            VectorStorePort.VectorSearchCommand command = new VectorStorePort.VectorSearchCommand(
                    Set.of(new VectorStorePort.KnowledgeBaseScope("kb-1", 4L)), dense(),
                    new SparseEncoderPort.SparseVector(Map.of(3, 1F)), 5);
            List<VectorStorePort.VectorSearchHit> hits = fixture.adapter.search("tenant-a", command);

            Assert.assertEquals(1, hits.size());
            Assert.assertEquals("business-point-1", hits.get(0).pointId());
            JsonNode query = fixture.requests.get(1).body();
            Assert.assertEquals("rrf", query.path("query").path("fusion").asText());
            Assert.assertEquals(2, query.path("prefetch").size());
            Assert.assertEquals("dense", query.path("prefetch").get(0).path("using").asText());
            Assert.assertEquals("sparse", query.path("prefetch").get(1).path("using").asText());
            Assert.assertEquals(20, query.path("prefetch").get(0).path("limit").asInt());
            Assert.assertEquals("tenant_id", query.path("filter").path("must").get(0).path("key").asText());
            try {
                fixture.adapter.search("tenant-a", command);
                Assert.fail("预期拒绝越租户命中");
            } catch (AppException e) {
                Assert.assertEquals("RAG_QDRANT_SCOPE_VIOLATION", e.getCode());
            }
        }
    }

    @Test
    public void shouldRejectExistingCollectionWithWrongDenseSchema() throws Exception {
        try (Fixture fixture = fixture(exchange -> respond(exchange, 200,
                schema(false).replace("\"size\":768", "\"size\":384")))) {
            try {
                fixture.adapter.ensureCollection();
                Assert.fail("预期schema冲突");
            } catch (AppException e) {
                Assert.assertEquals("RAG_QDRANT_SCHEMA_MISMATCH", e.getCode());
            }
        }
    }

    private Fixture fixture(Responder responder) throws Exception {
        List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            JsonNode body = bytes.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(bytes);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body,
                    exchange.getRequestHeaders().getFirst("api-key")));
            responder.respond(exchange);
        });
        server.start();
        RagProperties properties = new RagProperties();
        properties.getQdrant().setEndpoint(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.getQdrant().setTimeout(Duration.ofSeconds(2));
        properties.getQdrant().setSparseOnDisk(true);
        QdrantVectorStoreAdapter adapter = new QdrantVectorStoreAdapter(
                properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        return new Fixture(server, adapter, requests);
    }

    private void schemaThenOperation(HttpExchange exchange) throws java.io.IOException {
        respond(exchange, 200, "GET".equals(exchange.getRequestMethod()) ? schema(true) : okOperation());
    }

    private void assertTenantVersionFilter(JsonNode filter) {
        Assert.assertEquals("tenant_id", filter.path("must").get(0).path("key").asText());
        Assert.assertEquals("tenant-a", filter.path("must").get(0).path("match").path("value").asText());
        Assert.assertEquals("version_id", filter.path("must").get(1).path("key").asText());
        Assert.assertEquals("version-1", filter.path("must").get(1).path("match").path("value").asText());
    }

    private List<Float> dense() { return Collections.nCopies(768, 0.01F); }
    private String okOperation() { return "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}"; }
    private String schema(boolean onDisk) {
        return "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{"
                + "\"vectors\":{\"dense\":{\"size\":768,\"distance\":\"Cosine\"}},"
                + "\"sparse_vectors\":{\"sparse\":{\"index\":{\"on_disk\":" + onDisk + "}}}}}}}";
    }
    private String searchResponse(String tenant) {
        return "{\"status\":\"ok\",\"result\":{\"points\":[{\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"version\":1,\"score\":0.91,\"payload\":{\"tenant_id\":\"" + tenant + "\","
                + "\"kb_id\":\"kb-1\",\"document_id\":\"doc-1\",\"version_id\":\"version-1\","
                + "\"generation\":4,\"chunk_id\":\"chunk-1\",\"point_id\":\"business-point-1\"}}]}}";
    }
    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface Responder { void respond(HttpExchange exchange) throws java.io.IOException; }
    private record Request(String method, String uri, JsonNode body, String apiKey) {}
    private record Fixture(HttpServer server, QdrantVectorStoreAdapter adapter,
                           List<Request> requests) implements AutoCloseable {
        @Override public void close() { server.stop(0); }
    }
}
