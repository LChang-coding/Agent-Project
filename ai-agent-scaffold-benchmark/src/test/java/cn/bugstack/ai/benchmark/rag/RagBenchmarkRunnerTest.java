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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkRunnerTest {

    @TempDir Path temporary;
    private HttpServer server;
    private CountDownLatch responseRelease;

    @AfterEach
    void stopServer() {
        if (responseRelease != null) responseRelease.countDown();
        if (server != null) server.stop(0);
    }

    @Test
    void shouldExecuteFourAblationsAndPersistAuditableArtifacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixture = Path.of("../docs/test-fixtures/rag/beir-mini").toAbsolutePath().normalize();
        RagBenchmarkDataset dataset = new BeirDatasetLoader(mapper).load(fixture.resolve("corpus.jsonl"),
                fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv"),
                BeirDatasetLoader.Limits.defaults());
        Path prepared = temporary.resolve("prepared");
        new RagBenchmarkArtifactWriter(mapper).write(dataset, prepared,
                new RagBenchmarkArtifactWriter.Configuration("mini", "https://example.invalid/mini", "rev-1",
                        "fixture-only", "full", 7, 1024 * 1024),
                new RagBenchmarkArtifactWriter.SourceFiles(fixture.resolve("corpus.jsonl"),
                        fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv")));

        AtomicInteger profileSequence = new AtomicInteger();
        AtomicInteger bindingSequence = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, profileSequence, bindingSequence));
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("run");

        RagBenchmarkRunner.Result result = new RagBenchmarkRunner(mapper, client).run(
                new RagBenchmarkRunner.Configuration("mini-run", base, "environment:TEST_TOKEN", "commit-abc", prepared,
                        output, 20260719L, 0, Duration.ofMillis(1), Duration.ofSeconds(5)));

        assertEquals(4, result.metrics().size());
        assertEquals(8, Files.readAllLines(output.resolve("run.jsonl")).size());
        assertEquals(2, result.statistics().get("dense").requestCount());
        assertTrue(Files.isRegularFile(output.resolve("metrics.json")));
        JsonNode targets = mapper.readTree(output.resolve("targets.json").toFile());
        assertEquals("mini-run", targets.path("sourceRunId").asText());
        assertEquals(4, targets.path("targets").size());
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("completed", manifest.path("status").asText());
        assertEquals("environment:TEST_TOKEN", manifest.path("credentialSource").asText());
        assertEquals("commit-abc", manifest.path("codeRevision").asText());
        assertTrue(manifest.toString().indexOf("test-token") < 0);
    }

    @Test
    void evaluateShouldFailWarmupBeforeCreatingMeasuredRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixture = Path.of("../docs/test-fixtures/rag/beir-mini").toAbsolutePath().normalize();
        RagBenchmarkDataset dataset = new BeirDatasetLoader(mapper).load(fixture.resolve("corpus.jsonl"),
                fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv"),
                BeirDatasetLoader.Limits.defaults());
        Path prepared = temporary.resolve("prepared-gate");
        new RagBenchmarkArtifactWriter(mapper).write(dataset, prepared,
                new RagBenchmarkArtifactWriter.Configuration("mini", "https://example.invalid/mini", "rev-1",
                        "fixture-only", "full", 7, 1024 * 1024),
                new RagBenchmarkArtifactWriter.SourceFiles(fixture.resolve("corpus.jsonl"),
                        fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv")));
        Path targets = temporary.resolve("targets.json");
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "targets", Map.of(
                "dense", "target-dense", "sparse", "target-sparse", "hybrid_rrf", "target-hybrid",
                "hybrid_rrf_rerank", "target-rerank")));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String data = "{\"retrievalId\":\"ret\",\"degraded\":true,"
                    + "\"degradationReasons\":[\"rerank_fallback\"],\"metrics\":{\"embeddingMs\":1,"
                    + "\"denseMs\":1,\"sparseMs\":1,\"fusionMs\":1,\"rerankMs\":1,\"totalMs\":5,"
                    + "\"denseCandidateCount\":3,\"sparseCandidateCount\":3,"
                    + "\"fusionCandidateCount\":3,\"rerankCandidateCount\":3},\"citations\":[{"
                    + "\"headingPath\":\"" + RagBenchmarkArtifactWriter.marker("doc-alpha")
                    + " — Alpha\"}]}";
            respond(exchange, 200, "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("failed-evaluation");

        RagBenchmarkWarmupGate.WarmupGateException exception = assertThrows(
                RagBenchmarkWarmupGate.WarmupGateException.class,
                () -> new RagBenchmarkRunner(mapper, client).evaluate(
                        new RagBenchmarkRunner.EvaluationConfiguration("gate-run", base,
                                "environment:TEST_TOKEN", "commit-abc", prepared, targets, output,
                                20260719L, 1)));

        assertEquals(RagBenchmarkWarmupGate.ERROR_CODE, exception.code());
        assertEquals(4, Files.readAllLines(output.resolve("warmup.jsonl")).size());
        assertFalse(Files.exists(output.resolve("run.jsonl")));
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("failed", manifest.path("status").asText());
        assertEquals(RagBenchmarkWarmupGate.ERROR_CODE, manifest.path("errorCode").asText());
    }

    @Test
    void evaluateShouldClassifyRequestTimeoutBeforeCreatingMeasuredRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixture = Path.of("../docs/test-fixtures/rag/beir-mini").toAbsolutePath().normalize();
        RagBenchmarkDataset dataset = new BeirDatasetLoader(mapper).load(fixture.resolve("corpus.jsonl"),
                fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv"),
                BeirDatasetLoader.Limits.defaults());
        Path prepared = temporary.resolve("prepared-timeout");
        new RagBenchmarkArtifactWriter(mapper).write(dataset, prepared,
                new RagBenchmarkArtifactWriter.Configuration("mini", "https://example.invalid/mini", "rev-1",
                        "fixture-only", "full", 7, 1024 * 1024),
                new RagBenchmarkArtifactWriter.SourceFiles(fixture.resolve("corpus.jsonl"),
                        fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv")));
        Path targets = temporary.resolve("timeout-targets.json");
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "targets", Map.of(
                "dense", "target-dense", "sparse", "target-sparse", "hybrid_rrf", "target-hybrid",
                "hybrid_rrf_rerank", "target-rerank")));

        responseRelease = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                responseRelease.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofMillis(100), 1024 * 1024);
        Path output = temporary.resolve("timed-out-evaluation");

        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> new RagBenchmarkRunner(mapper, client).evaluate(
                        new RagBenchmarkRunner.EvaluationConfiguration("timeout-run", base,
                                "environment:TEST_TOKEN", "commit-abc", prepared, targets, output,
                                20260719L, 1)));
        responseRelease.countDown();

        assertFalse(Files.exists(output.resolve("run.jsonl")));
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("failed", manifest.path("status").asText());
        assertEquals("HttpTimeoutException", manifest.path("errorType").asText());
        assertEquals(RagBenchmarkRunner.REQUEST_TIMEOUT_ERROR_CODE, manifest.path("errorCode").asText());
    }

    @Test
    void evaluateShouldResumeStrictPrefixWithoutRepeatingCompletedRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixture = Path.of("../docs/test-fixtures/rag/beir-mini").toAbsolutePath().normalize();
        RagBenchmarkDataset dataset = new BeirDatasetLoader(mapper).load(fixture.resolve("corpus.jsonl"),
                fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv"),
                BeirDatasetLoader.Limits.defaults());
        Path prepared = temporary.resolve("prepared-resume");
        new RagBenchmarkArtifactWriter(mapper).write(dataset, prepared,
                new RagBenchmarkArtifactWriter.Configuration("mini", "https://example.invalid/mini", "rev-1",
                        "fixture-only", "full", 7, 1024 * 1024),
                new RagBenchmarkArtifactWriter.SourceFiles(fixture.resolve("corpus.jsonl"),
                        fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv")));

        AtomicInteger debugCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            debugCalls.incrementAndGet();
            String data = "{\"retrievalId\":\"ret-live-" + debugCalls.get() + "\",\"degraded\":false,"
                    + "\"degradationReasons\":[],\"metrics\":{\"embeddingMs\":1,\"denseMs\":1,"
                    + "\"sparseMs\":1,\"fusionMs\":1,\"rerankMs\":1,\"totalMs\":5,"
                    + "\"denseCandidateCount\":3,\"sparseCandidateCount\":3,"
                    + "\"fusionCandidateCount\":3,\"rerankCandidateCount\":3},\"citations\":[{"
                    + "\"headingPath\":\"" + RagBenchmarkArtifactWriter.marker("doc-alpha")
                    + " — Alpha\"}]}";
            respond(exchange, 200, "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        Map<String, String> targetValues = new LinkedHashMap<>();
        RagBenchmarkHttpClient.ProfileDefinition.ablations().forEach(
                definition -> targetValues.put(definition.variant(), "target-" + definition.variant()));
        Path targets = temporary.resolve("resume-targets.json");
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "targets", targetValues));
        String targetsSha = sha256(targets);

        List<QueryFixture> queries = new ArrayList<>();
        for (String line : Files.readAllLines(prepared.resolve("queries.jsonl"))) {
            JsonNode node = mapper.readTree(line);
            queries.add(new QueryFixture(node.path("queryId").asText(), node.path("text").asText()));
        }
        queries.sort(java.util.Comparator.comparing(QueryFixture::queryId));
        Collections.shuffle(queries, new Random(20260719L));

        Path source = temporary.resolve("resume-source");
        Files.createDirectories(source);
        mapper.writeValue(source.resolve("targets.json").toFile(),
                Map.of("schemaVersion", 1, "sourceSha256", targetsSha, "targets", targetValues));
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(mapper);
        AtomicInteger retrievals = new AtomicInteger();
        for (String variant : targetValues.keySet()) {
            runIO.append(source.resolve("warmup.jsonl"), healthySourceRecord("source-run", variant,
                    queries.get(0), retrievals.incrementAndGet()));
        }
        List<RagBenchmarkRunIO.RunRecord> expectedMeasured = new ArrayList<>();
        List<String> variants = new ArrayList<>(targetValues.keySet());
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((queryIndex + offset) % variants.size());
                expectedMeasured.add(healthySourceRecord("source-run", variant, queries.get(queryIndex),
                        retrievals.incrementAndGet()));
            }
        }
        for (int index = 0; index < 3; index++) {
            runIO.append(source.resolve("run.jsonl"), expectedMeasured.get(index));
        }

        JsonNode preparedManifest = mapper.readTree(prepared.resolve("manifest.json").toFile());
        Path markdown;
        try (var markdownFiles = Files.list(prepared.resolve("documents"))) {
            markdown = markdownFiles.findFirst().orElseThrow();
        }
        Map<String, Object> sourceManifest = new LinkedHashMap<>();
        sourceManifest.put("schemaVersion", 1);
        sourceManifest.put("runId", "source-run");
        sourceManifest.put("status", "failed");
        sourceManifest.put("baseUrl", base.toString());
        sourceManifest.put("codeRevision", "source-code");
        sourceManifest.put("dataset", preparedManifest.path("datasetName").asText());
        sourceManifest.put("sourceRevision", preparedManifest.path("sourceRevision").asText());
        sourceManifest.put("documentCount", preparedManifest.path("documentCount").asInt());
        sourceManifest.put("queryCount", preparedManifest.path("queryCount").asInt());
        sourceManifest.put("markdownFile", markdown.getFileName().toString());
        sourceManifest.put("markdownBytes", Files.size(markdown));
        sourceManifest.put("markdownSha256", sha256(markdown));
        sourceManifest.put("seed", 20260719L);
        sourceManifest.put("warmupQueries", 1);
        sourceManifest.put("queryThreads", 1);
        sourceManifest.put("uploadThreads", 1);
        sourceManifest.put("workerThreads", 1);
        sourceManifest.put("variants", RagBenchmarkHttpClient.ProfileDefinition.ablations());
        sourceManifest.put("mode", "evaluate_existing_targets");
        sourceManifest.put("targetsSha256", targetsSha);
        sourceManifest.put("errorType", "HttpTimeoutException");
        mapper.writeValue(source.resolve("run-manifest.json").toFile(), sourceManifest);

        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("resumed-output");
        new RagBenchmarkRunner(mapper, client).evaluate(new RagBenchmarkRunner.EvaluationConfiguration(
                "resumed-run", base, "environment:TEST_TOKEN", "current-code", prepared, targets,
                output, 20260719L, 1, source, 5));

        assertEquals(5, debugCalls.get());
        assertEquals(8, Files.readAllLines(output.resolve("run.jsonl")).size());
        assertEquals(Files.readAllLines(source.resolve("run.jsonl")),
                Files.readAllLines(output.resolve("run.jsonl")).subList(0, 3));
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("completed", manifest.path("status").asText());
        assertEquals(3, manifest.path("resume").path("resumedRecordCount").asInt());
        assertEquals(5, manifest.path("requestTimeoutSeconds").asInt());
    }

    @Test
    void evaluateShouldFailResumeGateBeforeAnyRetrievalRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixture = Path.of("../docs/test-fixtures/rag/beir-mini").toAbsolutePath().normalize();
        RagBenchmarkDataset dataset = new BeirDatasetLoader(mapper).load(fixture.resolve("corpus.jsonl"),
                fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv"),
                BeirDatasetLoader.Limits.defaults());
        Path prepared = temporary.resolve("prepared-rejected-resume");
        new RagBenchmarkArtifactWriter(mapper).write(dataset, prepared,
                new RagBenchmarkArtifactWriter.Configuration("mini", "https://example.invalid/mini", "rev-1",
                        "fixture-only", "full", 7, 1024 * 1024),
                new RagBenchmarkArtifactWriter.SourceFiles(fixture.resolve("corpus.jsonl"),
                        fixture.resolve("queries.jsonl"), fixture.resolve("qrels.tsv")));
        Path targets = temporary.resolve("rejected-resume-targets.json");
        mapper.writeValue(targets.toFile(), Map.of("schemaVersion", 1, "targets", Map.of(
                "dense", "target-dense", "sparse", "target-sparse", "hybrid_rrf", "target-hybrid",
                "hybrid_rrf_rerank", "target-rerank")));

        AtomicInteger debugCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            debugCalls.incrementAndGet();
            respond(exchange, 500, "{\"code\":\"SHOULD_NOT_BE_CALLED\",\"info\":\"unexpected\"}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temporary.resolve("rejected-resume-output");

        RagBenchmarkResumeGate.ResumeGateException exception = assertThrows(
                RagBenchmarkResumeGate.ResumeGateException.class,
                () -> new RagBenchmarkRunner(mapper, client).evaluate(
                        new RagBenchmarkRunner.EvaluationConfiguration("rejected-resume", base,
                                "environment:TEST_TOKEN", "current-code", prepared, targets, output,
                                20260719L, 1, temporary.resolve("missing-source"), 5)));

        assertEquals(RagBenchmarkResumeGate.ERROR_CODE, exception.code());
        assertEquals(0, debugCalls.get());
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("failed", manifest.path("status").asText());
        assertEquals(RagBenchmarkResumeGate.ERROR_CODE, manifest.path("errorCode").asText());
        assertFalse(Files.exists(output.resolve("run.jsonl")));
    }

    private RagBenchmarkRunIO.RunRecord healthySourceRecord(String runId, String variant,
                                                             QueryFixture query, int retrievalSequence) {
        return new RagBenchmarkRunIO.RunRecord(runId, variant, query.queryId(), sha256(query.text()),
                "ret-source-" + retrievalSequence, List.of("doc-alpha"), 1, false, List.of(), null,
                Map.of("totalMs", 1L, "rerankMs", 1L), Map.of("rerankCandidateCount", 1));
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record QueryFixture(String queryId, String text) {}

    private void handle(HttpExchange exchange, AtomicInteger profiles, AtomicInteger bindings) throws IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        String data;
        if (path.equals("/api/v1/rag/knowledge-bases") && exchange.getRequestMethod().equals("POST")) {
            data = "{\"knowledgeBaseId\":\"kb-1\",\"status\":\"active\"}";
        } else if (path.equals("/api/v1/rag/knowledge-bases/kb-1/documents")
                && exchange.getRequestMethod().equals("POST")) {
            data = "{\"documentId\":\"doc-1\",\"taskId\":\"task-1\",\"status\":\"pending\",\"deduplicated\":false}";
        } else if (path.equals("/api/v1/rag/ingest-tasks/task-1")) {
            data = "{\"taskId\":\"task-1\",\"status\":\"completed\",\"stage\":\"completed\",\"processedChunks\":3,\"totalChunks\":3,\"revision\":2}";
        } else if (path.equals("/api/v1/rag/knowledge-bases/kb-1/documents")) {
            data = "[{\"documentId\":\"doc-1\",\"status\":\"ready\",\"activeVersionId\":\"ver-1\",\"activeGeneration\":1}]";
        } else if (path.equals("/api/v1/rag/retrieval-profiles")) {
            data = "{\"profileId\":\"profile-" + profiles.incrementAndGet() + "\",\"revision\":0}";
        } else if (path.equals("/api/v1/rag/bindings")) {
            data = "{\"bindingId\":\"binding-" + bindings.incrementAndGet() + "\",\"revision\":0}";
        } else if (path.equals("/api/v1/rag/retrieval-debug")) {
            data = "{\"retrievalId\":\"ret\",\"degraded\":false,\"degradationReasons\":[],"
                    + "\"metrics\":{\"embeddingMs\":1,\"denseMs\":2,\"sparseMs\":1,\"fusionMs\":1,"
                    + "\"rerankMs\":1,\"totalMs\":6,\"denseCandidateCount\":3,\"sparseCandidateCount\":3,"
                    + "\"fusionCandidateCount\":3,\"rerankCandidateCount\":3},\"citations\":[{\"headingPath\":\""
                    + RagBenchmarkArtifactWriter.marker("doc-alpha") + " — Alpha\"}]}";
        } else {
            respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"info\":\"not found\"}");
            return;
        }
        respond(exchange, 200, "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
