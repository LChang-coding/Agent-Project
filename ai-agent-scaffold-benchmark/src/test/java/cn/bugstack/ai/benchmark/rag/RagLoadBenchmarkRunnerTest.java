package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagLoadBenchmarkRunnerTest {

    @TempDir Path temporary;
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void shouldExecuteBoundedConcurrentLoadAndPersistSingleWriterArtifacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path prepared = temporary.resolve("prepared");
        Files.createDirectories(prepared);
        Files.writeString(prepared.resolve("queries.jsonl"),
                "{\"queryId\":\"q1\",\"text\":\"alpha query\"}\n"
                        + "{\"queryId\":\"q2\",\"text\":\"beta query\"}\n", StandardCharsets.UTF_8);
        Path targets = temporary.resolve("targets.json");
        Map<String, String> targetValues = new LinkedHashMap<>();
        RagBenchmarkHttpClient.ProfileDefinition.ablations().forEach(definition ->
                targetValues.put(definition.variant(), "target-" + definition.variant()));
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "sourceRunId", "quality-run",
                "targets", targetValues));

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/rag/retrieval-debug", exchange ->
                handleDebug(exchange, active, maximum, requestCount));
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, baseUrl,
                "secret-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("load");

        RagLoadBenchmarkRunner.Result result = new RagLoadBenchmarkRunner(mapper, client).run(
                new RagLoadBenchmarkRunner.Configuration("load-run", baseUrl, "environment:TEST_TOKEN",
                        "commit-1", prepared, targets, output, 7L, List.of(3, 1), 1, 4,
                        Duration.ofSeconds(10)));

        assertEquals(32, result.records().size());
        assertEquals(40, requestCount.get());
        assertTrue(maximum.get() >= 2);
        assertTrue(maximum.get() <= 3);
        assertEquals(32, Files.readAllLines(output.resolve("load.jsonl")).size());
        assertEquals(8, Files.readAllLines(output.resolve("warmup.jsonl")).size());
        Map<String, Map<String, Integer>> queriesByVariant = new HashMap<>();
        for (String line : Files.readAllLines(output.resolve("load.jsonl"))) {
            JsonNode record = mapper.readTree(line);
            queriesByVariant.computeIfAbsent(record.path("variant").asText(), ignored -> new HashMap<>())
                    .merge(record.path("queryId").asText(), 1, Integer::sum);
            assertTrue(record.path("httpStatus").asInt() >= 200);
            assertTrue(record.path("responseBytes").asInt() > 0);
            assertFalse(record.path("startedAt").asText().isBlank());
            assertFalse(record.path("finishedAt").asText().isBlank());
        }
        assertEquals(1, queriesByVariant.values().stream().distinct().count());
        JsonNode report = mapper.readTree(output.resolve("load-report.json").toFile());
        assertEquals(32, report.path("levels").path("1").path("requestCount").asInt()
                + report.path("levels").path("3").path("requestCount").asInt());
        assertTrue(report.path("levels").path("3").path("throughputRequestsPerSecond").asDouble() > 0);
        JsonNode manifest = mapper.readTree(output.resolve("load-manifest.json").toFile());
        assertEquals("completed", manifest.path("status").asText());
        assertEquals("same-deterministic-query-per-variant-v1", manifest.path("queryPairing").asText());
        assertEquals("completion-order-flush-per-record-v1", manifest.path("rawPersistence").asText());
        assertEquals(120000, manifest.path("requestTimeoutMs").asLong());
        assertEquals(List.of(3, 1), mapper.convertValue(manifest.path("concurrencyLevels"), List.class));
        assertEquals("not_collected_by_test_client", manifest.path("serverResourceEvidence").asText());
        assertFalse(manifest.toString().contains("secret-token"));
    }

    @Test
    void shouldRejectDuplicateConcurrencyLevelsInsteadOfSilentlyNormalizingThem() {
        assertThrows(IllegalArgumentException.class, () -> new RagLoadBenchmarkRunner.Configuration(
                "load-duplicate", URI.create("http://127.0.0.1:8092/api"), "environment:TEST_TOKEN",
                "commit-1", temporary, temporary.resolve("targets.json"), temporary.resolve("out"), 7L,
                List.of(2, 1, 2), 1, 2, Duration.ofSeconds(10)));
    }

    @Test
    void shouldPersistFailedWarmupAndNeverStartMeasuredPhase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path prepared = temporary.resolve("prepared-failed");
        Files.createDirectories(prepared);
        Files.writeString(prepared.resolve("queries.jsonl"),
                "{\"queryId\":\"q1\",\"text\":\"alpha query\"}\n", StandardCharsets.UTF_8);
        Path targets = temporary.resolve("targets-failed.json");
        Map<String, String> targetValues = new LinkedHashMap<>();
        RagBenchmarkHttpClient.ProfileDefinition.ablations().forEach(definition ->
                targetValues.put(definition.variant(), "target-" + definition.variant()));
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "sourceRunId", "quality-run",
                "targets", targetValues));
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rag/retrieval-debug", exchange -> {
            requests.incrementAndGet();
            String data = "{\"retrievalId\":\"ret-failed\",\"degraded\":true,"
                    + "\"degradationReasons\":[\"rerank_unavailable\"],\"metrics\":{},"
                    + "\"citations\":[{\"headingPath\":\""
                    + RagBenchmarkArtifactWriter.marker("doc-alpha") + " — Alpha\"}]}";
            respond(exchange, 200, "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}");
        });
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, baseUrl,
                "secret-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("load-failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RagLoadBenchmarkRunner(mapper, client).run(
                        new RagLoadBenchmarkRunner.Configuration("load-failed", baseUrl,
                                "environment:TEST_TOKEN", "commit-1", prepared, targets, output, 7L,
                                List.of(1), 1, 2, Duration.ofSeconds(10))));

        assertEquals("RAG_BENCHMARK_LOAD_GATE_FAILED", failure.getMessage());
        assertTrue(requests.get() >= 1 && requests.get() <= 4);
        assertEquals(1, Files.readAllLines(output.resolve("warmup.jsonl")).size());
        assertEquals(0, Files.readAllLines(output.resolve("load.jsonl")).size());
        JsonNode manifest = mapper.readTree(output.resolve("load-manifest.json").toFile());
        assertEquals("failed", manifest.path("status").asText());
        assertEquals("RAG_BENCHMARK_LOAD_GATE_FAILED", manifest.path("errorCode").asText());
        assertEquals("warmup", manifest.path("failedSample").path("phase").asText());
        assertFalse(manifest.toString().contains("alpha query"));
    }

    private void handleDebug(HttpExchange exchange, AtomicInteger active, AtomicInteger maximum,
                             AtomicInteger requestCount) throws IOException {
        exchange.getRequestBody().readAllBytes();
        requestCount.incrementAndGet();
        int now = active.incrementAndGet();
        maximum.accumulateAndGet(now, Math::max);
        try {
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("fake server interrupted", exception);
            }
            String data = "{\"retrievalId\":\"ret\",\"degraded\":false,\"degradationReasons\":[],"
                    + "\"metrics\":{\"embeddingMs\":1,\"denseMs\":2,\"sparseMs\":1,\"fusionMs\":1,"
                    + "\"rerankMs\":1,\"totalMs\":6,\"denseCandidateCount\":3,"
                    + "\"sparseCandidateCount\":3,\"fusionCandidateCount\":3,"
                    + "\"rerankCandidateCount\":3},\"citations\":[{\"headingPath\":\""
                    + RagBenchmarkArtifactWriter.marker("doc-alpha") + " — Alpha\"}]}";
            respond(exchange, 200, "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}");
        } finally {
            active.decrementAndGet();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
