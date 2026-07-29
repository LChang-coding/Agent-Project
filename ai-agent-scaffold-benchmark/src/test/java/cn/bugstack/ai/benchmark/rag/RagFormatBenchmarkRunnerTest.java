package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagFormatBenchmarkRunnerTest {

    private static final Pattern FILE_NAME = Pattern.compile("filename=\"([^\"]+)\"");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> queryToSource = new LinkedHashMap<>();
    private final Map<String, String> taskToInternal = new LinkedHashMap<>();
    private final List<String> internalDocuments = new ArrayList<>();
    private final AtomicInteger profileSequence = new AtomicInteger();
    private final AtomicInteger bindingSequence = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldRunTwoHundredPdfDocumentsAndMapInternalIdsBackToGold(@TempDir Path temporary)
            throws Exception {
        Path dataset = Path.of("../docs/rag/evaluation-data/pdf-docx-200").toAbsolutePath().normalize();
        readGold(dataset.resolve("gold/gold.jsonl"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rag", this::route);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        RagBenchmarkHttpClient client = new RagBenchmarkHttpClient(HttpClient.newHttpClient(), mapper, base,
                "test-token", Duration.ofSeconds(10), 8 * 1024 * 1024);
        RagFormatBenchmarkRunner.Configuration configuration = new RagFormatBenchmarkRunner.Configuration(
                "format-pdf-test", base, "environment:TEST", "0".repeat(40), dataset, "PDF",
                "IR_FULL", "document-ir-full-v1", "1".repeat(64), temporary.resolve("run"),
                20260729L, 0, Duration.ofMillis(1), Duration.ofSeconds(5));

        RagFormatBenchmarkRunner.Result result = new RagFormatBenchmarkRunner(mapper, client).run(configuration);

        assertEquals(200, result.completedDocumentCount());
        assertEquals(800, result.completedQueryResultCount());
        assertEquals(200, Files.readAllLines(configuration.outputDirectory()
                .resolve("document-results.jsonl")).size());
        assertEquals(800, Files.readAllLines(configuration.outputDirectory().resolve("run.jsonl")).size());
        result.quality().values().forEach(metrics -> assertEquals(1.0, metrics.recallAt10()));
    }

    private void readGold(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode value = mapper.readTree(line);
                queryToSource.put(value.path("query").asText(), value.path("documentId").asText());
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("/api/v1/rag/knowledge-bases".equals(path) && "POST".equals(method)) {
            respond(exchange, Map.of("knowledgeBaseId", "kb-format", "status", "active"));
            return;
        }
        if (path.endsWith("/documents") && "POST".equals(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            Matcher matcher = FILE_NAME.matcher(body);
            if (!matcher.find()) throw new IllegalStateException("multipart缺少filename");
            String sourceId = matcher.group(1).replaceFirst("^\\d{3}-scifact-", "").replaceFirst("\\.pdf$", "");
            String internal = "internal-" + sourceId;
            String task = "task-" + sourceId;
            internalDocuments.add(internal);
            taskToInternal.put(task, internal);
            respond(exchange, Map.of("documentId", internal, "taskId", task,
                    "status", "accepted", "deduplicated", false));
            return;
        }
        if (path.contains("/ingest-tasks/") && "GET".equals(method)) {
            String task = path.substring(path.lastIndexOf('/') + 1);
            respond(exchange, Map.of("taskId", task, "status", "completed", "stage", "indexed",
                    "processedChunks", 1, "totalChunks", 1, "revision", 1));
            return;
        }
        if (path.endsWith("/documents") && "GET".equals(method)) {
            List<Map<String, Object>> values = internalDocuments.stream().map(value -> Map.<String, Object>of(
                    "documentId", value, "status", "ready", "activeVersionId", "version-" + value,
                    "activeGeneration", 1)).toList();
            respond(exchange, values);
            return;
        }
        if ("/api/v1/rag/retrieval-profiles".equals(path)) {
            int id = profileSequence.incrementAndGet();
            respond(exchange, Map.of("profileId", "profile-" + id, "revision", 1));
            return;
        }
        if ("/api/v1/rag/bindings".equals(path)) {
            int id = bindingSequence.incrementAndGet();
            respond(exchange, Map.of("bindingId", "binding-" + id, "revision", 1));
            return;
        }
        if ("/api/v1/rag/retrieval-debug".equals(path)) {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String sourceId = queryToSource.get(request.path("query").asText());
            respond(exchange, Map.of(
                    "retrievalId", "ret-" + sourceId + "-" + System.nanoTime(),
                    "degraded", false,
                    "degradationReasons", List.of(),
                    "metrics", Map.of("totalMs", 1, "rerankMs", 1, "rerankCandidateCount", 1),
                    "citations", List.of(Map.of("documentId", "internal-" + sourceId, "headingPath", ""))));
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private void respond(HttpExchange exchange, Object data) throws IOException {
        byte[] body = mapper.writeValueAsBytes(Map.of("code", "0000", "info", "success", "data", data));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
