package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDiagnosticCaseRunnerTest {

    @TempDir Path temp;
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rag/retrieval-debug", exchange -> respond(exchange, success("""
                {"retrievalId":"ret-1","degraded":false,"degradationReasons":[],
                 "metrics":{"totalMs":2,"denseCandidateCount":1,"fusionCandidateCount":1},
                 "diagnostics":{"enabled":true,"truncated":false,"capturedCount":1,"maxCapturedCount":2048,
                   "candidates":[{"bindingId":"b1","profileId":"p1","stage":"dense_raw","rank":1,
                     "knowledgeBaseId":"kb1","documentId":"doc-1","versionId":"v1","generation":1,
                     "chunkId":"c1","headingPath":"%s — title","denseScore":0.8,"outcome":"returned_by_vector_store"}]},
                 "citations":[{"headingPath":"%s — title"}]}
                """.formatted(RagBenchmarkArtifactWriter.marker("doc-1"),
                        RagBenchmarkArtifactWriter.marker("doc-1")))));
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void shouldPersistFourVariantsWithHealthyDiagnostics() throws Exception {
        Path cases = temp.resolve("cases.json");
        Path targets = temp.resolve("targets.json");
        Files.writeString(cases, """
                {"cases":{"persistent_miss":[{"queryId":"q1","question":"question one"}],
                "dense_only_success":[{"queryId":"q1","question":"question one"}]}}
                """);
        Files.writeString(targets, """
                {"targets":{"dense":"t1","sparse":"t2","hybrid_rrf":"t3","hybrid_rrf_rerank":"t4"}}
                """);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), new ObjectMapper(),
                base, "token", Duration.ofSeconds(5), 1024 * 1024);
        Path output = temp.resolve("output");

        RagDiagnosticCaseRunner.Result result = new RagDiagnosticCaseRunner(new ObjectMapper(), client).run(
                new RagDiagnosticCaseRunner.Configuration("diag-1", "commit-a", cases, targets, output, 10, 5));

        assertEquals(1, result.queryCount());
        assertEquals(4, result.recordCount());
        assertEquals(4, Files.readAllLines(result.records()).size());
        assertEquals("completed", new ObjectMapper().readTree(result.manifest().toFile()).path("status").asText());
        assertTrue(Files.readString(result.records()).contains("dense_raw"));
    }

    @Test
    void shouldRejectCandidateWithoutBenchmarkDocumentMarker() throws Exception {
        server.removeContext("/api/v1/rag/retrieval-debug");
        server.createContext("/api/v1/rag/retrieval-debug", exchange -> respond(exchange, success("""
                {"retrievalId":"ret-1","degraded":false,"degradationReasons":[],
                 "metrics":{"totalMs":2,"denseCandidateCount":1,"fusionCandidateCount":1},
                 "diagnostics":{"enabled":true,"truncated":false,"capturedCount":1,"maxCapturedCount":2048,
                   "candidates":[{"bindingId":"b1","profileId":"p1","stage":"dense_raw","rank":1,
                     "knowledgeBaseId":"kb1","documentId":"doc-1","versionId":"v1","generation":1,
                     "chunkId":"c1","headingPath":"ordinary heading","denseScore":0.8,
                     "outcome":"returned_by_vector_store"}]},
                 "citations":[{"headingPath":"%s — title"}]}
                """.formatted(RagBenchmarkArtifactWriter.marker("doc-1")))));
        Path cases = temp.resolve("bad-cases.json");
        Path targets = temp.resolve("bad-targets.json");
        Files.writeString(cases, """
                {"cases":{"persistent_miss":[{"queryId":"q1","question":"question one"}]}}
                """);
        Files.writeString(targets, """
                {"targets":{"dense":"t1","sparse":"t2","hybrid_rrf":"t3","hybrid_rrf_rerank":"t4"}}
                """);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), new ObjectMapper(),
                base, "token", Duration.ofSeconds(5), 1024 * 1024);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new RagDiagnosticCaseRunner(new ObjectMapper(), client).run(
                        new RagDiagnosticCaseRunner.Configuration("diag-bad", "commit-a", cases, targets,
                                temp.resolve("bad-output"), 10, 5)));

        assertTrue(error.getMessage().contains("诊断请求不健康"));
        assertTrue(error.getMessage().contains("missingBenchmarkDocumentIds=1"));
        assertTrue(error.getMessage().contains("diagnosticCapturedCount=1"));
        assertEquals("failed", new ObjectMapper().readTree(temp.resolve("bad-output/diagnostic-manifest.json")
                .toFile()).path("status").asText());
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String success(String data) { return "{\"code\":\"0000\",\"info\":\"ok\",\"data\":" + data + "}"; }
}
