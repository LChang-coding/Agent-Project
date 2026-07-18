package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkHttpClientTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rag/retrieval-debug", exchange -> respond(exchange, 200, success("""
                {"retrievalId":"ret-1","degraded":false,"degradationReasons":[],
                 "metrics":{"embeddingMs":2,"denseMs":3,"sparseMs":0,"fusionMs":1,"rerankMs":0,
                            "totalMs":7,"denseCandidateCount":12,"sparseCandidateCount":0,
                            "fusionCandidateCount":10,"rerankCandidateCount":0},
                 "citations":[{"headingPath":"%s — title"},{"headingPath":"%s — another chunk"}],
                 "padding":"%s"}
                """.formatted(RagBenchmarkArtifactWriter.marker("doc-1"),
                    RagBenchmarkArtifactWriter.marker("doc-1"), "x".repeat(2000)))));
        server.createContext("/api/v1/rag/knowledge-bases/kb-1/documents", exchange -> respond(exchange, 200,
                success("[{\"documentId\":\"doc-1\",\"status\":\"ready\",\"activeVersionId\":\"ver-1\",\"activeGeneration\":1}]")));
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void shouldDecodeAndDeduplicateCitationsAndKeepBearerOutOfResult() throws Exception {
        RagBenchmarkHttpClient client = client(1024 * 1024);
        RagBenchmarkHttpClient.DebugResult result = client.debug("target-1", "query");

        assertEquals("Bearer secret-token", authorization.get());
        assertEquals(java.util.List.of("doc-1"), result.rankedDocumentIds());
        assertEquals(7, result.timingsMs().get("totalMs"));
        assertEquals(12, result.candidateCounts().get("denseCandidateCount"));
        assertTrue(result.toString().indexOf("secret-token") < 0);
        assertTrue(client.getDocument("kb-1", "doc-1").ready());
    }

    @Test
    void shouldRejectResponseBeyondConfiguredBound() {
        RagBenchmarkHttpClient client = client(1024);
        assertThrows(RagBenchmarkHttpClient.BenchmarkProtocolException.class,
                () -> client.debug("target-1", "query"));
    }

    private RagBenchmarkHttpClient client(int maxBytes) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        return new RagBenchmarkHttpClient(HttpClient.newHttpClient(), new ObjectMapper(), base,
                "secret-token", Duration.ofSeconds(5), maxBytes);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String success(String data) {
        return "{\"code\":\"0000\",\"info\":\"success\",\"data\":" + data + "}";
    }
}
