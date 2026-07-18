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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkRunnerTest {

    @TempDir Path temporary;
    private HttpServer server;

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

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
        JsonNode manifest = mapper.readTree(output.resolve("run-manifest.json").toFile());
        assertEquals("completed", manifest.path("status").asText());
        assertEquals("environment:TEST_TOKEN", manifest.path("credentialSource").asText());
        assertEquals("commit-abc", manifest.path("codeRevision").asText());
        assertTrue(manifest.toString().indexOf("test-token") < 0);
    }

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
