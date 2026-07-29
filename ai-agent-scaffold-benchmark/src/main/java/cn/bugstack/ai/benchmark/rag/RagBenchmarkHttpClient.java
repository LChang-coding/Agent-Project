package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只用于 benchmark 的生产 HTTP API 客户端；不记录或返回 Bearer 凭证。 */
public final class RagBenchmarkHttpClient {

    private static final String SUCCESS_CODE = "0000";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final BearerTokenProvider tokenProvider;
    private final Duration timeout;
    private final int maxResponseBytes;

    public RagBenchmarkHttpClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
                                  String bearerToken, Duration timeout, int maxResponseBytes) {
        if (httpClient == null || objectMapper == null || baseUri == null || bearerToken == null
                || bearerToken.isBlank() || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("benchmark HTTP客户端参数非法");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", ""));
        this.tokenProvider = BearerTokenProvider.fixed(bearerToken);
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    RagBenchmarkHttpClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
                           BearerTokenProvider tokenProvider, Duration timeout, int maxResponseBytes) {
        if (httpClient == null || objectMapper == null || baseUri == null || tokenProvider == null
                || tokenProvider.currentToken() == null || tokenProvider.currentToken().isBlank()
                || timeout == null || timeout.isZero() || timeout.isNegative() || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("benchmark HTTP客户端参数非法");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", ""));
        this.tokenProvider = tokenProvider;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) throws IOException, InterruptedException {
        JsonNode data = postJson("/v1/rag/knowledge-bases", Map.of("name", name, "description", description));
        return new KnowledgeBase(requiredText(data, "knowledgeBaseId"), requiredText(data, "status"));
    }

    public Upload uploadMarkdown(String knowledgeBaseId, Path file) throws IOException, InterruptedException {
        return uploadDocument(knowledgeBaseId, file);
    }

    public Upload uploadDocument(String knowledgeBaseId, Path file) throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("benchmark上传文件不存在");
        String contentType = contentType(file);
        String boundary = "rag-benchmark-" + UUID.randomUUID();
        String fileName = file.getFileName().toString();
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n"),
                HttpRequest.BodyPublishers.ofFile(file), bytes("\r\n--" + boundary + "--\r\n"));
        HttpRequest request = request("/v1/rag/knowledge-bases/" + encodePath(knowledgeBaseId) + "/documents")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
        JsonNode data = send(request).data();
        return new Upload(requiredText(data, "documentId"), requiredText(data, "taskId"),
                requiredText(data, "status"), data.path("deduplicated").asBoolean(false));
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        throw new IllegalArgumentException("benchmark只允许Markdown、PDF或DOCX");
    }

    public IngestTask getTask(String taskId) throws IOException, InterruptedException {
        JsonNode data = send(request("/v1/rag/ingest-tasks/" + encodePath(taskId)).GET().build()).data();
        return new IngestTask(requiredText(data, "taskId"), requiredText(data, "status"),
                requiredText(data, "stage"), data.path("processedChunks").asInt(),
                data.path("totalChunks").asInt(), optionalText(data, "errorCode"), data.path("revision").asLong());
    }

    public Document getDocument(String knowledgeBaseId, String documentId) throws IOException, InterruptedException {
        JsonNode data = send(request("/v1/rag/knowledge-bases/" + encodePath(knowledgeBaseId) + "/documents")
                .GET().build()).data();
        if (!data.isArray()) throw new BenchmarkProtocolException("RAG_BENCHMARK_RESPONSE_INVALID", "文档列表不是数组");
        for (JsonNode value : data) {
            if (documentId.equals(optionalText(value, "documentId"))) {
                return new Document(documentId, requiredText(value, "status"),
                        optionalText(value, "activeVersionId"), value.path("activeGeneration").asLong());
            }
        }
        throw new BenchmarkProtocolException("RAG_BENCHMARK_DOCUMENT_NOT_FOUND", "摄取任务完成但文档不存在");
    }

    public Profile createProfile(String name, ProfileDefinition value) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("mode", value.mode());
        payload.put("fusionStrategy", "rrf");
        payload.put("denseWeight", value.denseEnabled() ? 1 : 0);
        payload.put("sparseWeight", value.sparseEnabled() ? 1 : 0);
        payload.put("denseTopK", 100);
        payload.put("sparseTopK", 100);
        payload.put("fusionTopK", 10);
        payload.put("rerankEnabled", value.rerankEnabled());
        payload.put("rerankTopK", 10);
        payload.put("finalTopK", 10);
        payload.put("neighborWindow", 0);
        payload.put("maxContextTokens", 32768);
        payload.put("scoreThreshold", null);
        payload.put("queryRewriteEnabled", false);
        payload.put("deduplicateEnabled", true);
        JsonNode data = postJson("/v1/rag/retrieval-profiles", payload);
        return new Profile(requiredText(data, "profileId"), data.path("revision").asLong(), value);
    }

    public Binding createBinding(String targetId, String knowledgeBaseId, String profileId)
            throws IOException, InterruptedException {
        JsonNode data = postJson("/v1/rag/bindings", Map.of("targetType", "workflow", "targetId", targetId,
                "knowledgeBaseId", knowledgeBaseId, "profileId", profileId, "required", true,
                "maxTokens", 32768, "priority", 100));
        return new Binding(requiredText(data, "bindingId"), targetId, data.path("revision").asLong());
    }

    public DebugResult debug(String targetId, String query) throws IOException, InterruptedException {
        ApiResponse response = postJsonDetailed("/v1/rag/retrieval-debug", Map.of("targetType", "workflow",
                "targetId", targetId, "query", query, "maxContextTokens", 32768));
        JsonNode data = response.data();
        Set<String> uniqueDocuments = new LinkedHashSet<>();
        List<String> headings = new ArrayList<>();
        for (JsonNode citation : data.path("citations")) {
            String heading = optionalText(citation, "headingPath");
            String documentId = RagBenchmarkArtifactWriter.documentIdFromHeading(heading);
            if (documentId == null || documentId.isBlank()) documentId = optionalText(citation, "documentId");
            if (documentId == null || documentId.isBlank()) {
                throw new BenchmarkProtocolException("RAG_BENCHMARK_DOCUMENT_ID_INVALID",
                        "引用既无benchmark标识也无documentId");
            }
            uniqueDocuments.add(documentId);
            headings.add(heading);
        }
        JsonNode metrics = data.path("metrics");
        Map<String, Long> timings = new LinkedHashMap<>();
        for (String name : List.of("embeddingMs", "denseMs", "sparseMs", "fusionMs", "rerankMs", "totalMs",
                "configurationMs", "hydrationMs", "assemblyMs", "auditMs", "serviceMs")) {
            timings.put(name, metrics.path(name).asLong());
        }
        Map<String, Integer> candidates = new LinkedHashMap<>();
        for (String name : List.of("denseCandidateCount", "sparseCandidateCount", "fusionCandidateCount",
                "rerankCandidateCount")) candidates.put(name, metrics.path(name).asInt());
        List<String> reasons = new ArrayList<>();
        data.path("degradationReasons").forEach(value -> reasons.add(value.asText()));
        JsonNode diagnostics = data.path("diagnostics");
        List<DiagnosticCandidate> diagnosticCandidates = new ArrayList<>();
        for (JsonNode candidate : diagnostics.path("candidates")) {
            String candidateHeading = optionalText(candidate, "headingPath");
            diagnosticCandidates.add(new DiagnosticCandidate(requiredText(candidate, "bindingId"),
                    requiredText(candidate, "profileId"), requiredText(candidate, "stage"),
                    candidate.path("rank").asInt(), requiredText(candidate, "knowledgeBaseId"),
                    requiredText(candidate, "documentId"), requiredText(candidate, "versionId"),
                    candidate.path("generation").asLong(), requiredText(candidate, "chunkId"),
                    candidateHeading, RagBenchmarkArtifactWriter.documentIdFromHeading(candidateHeading),
                    optionalDouble(candidate, "denseScore"), optionalDouble(candidate, "sparseScore"),
                    optionalDouble(candidate, "fusionScore"), optionalDouble(candidate, "rerankScore"),
                    requiredText(candidate, "outcome")));
        }
        return new DebugResult(requiredText(data, "retrievalId"), List.copyOf(uniqueDocuments),
                List.copyOf(headings), data.path("degraded").asBoolean(false), List.copyOf(reasons),
                Map.copyOf(timings), Map.copyOf(candidates), diagnostics.path("truncated").asBoolean(false),
                diagnostics.path("capturedCount").asInt(), diagnostics.path("maxCapturedCount").asInt(),
                List.copyOf(diagnosticCandidates), response.httpStatus(), response.responseBytes());
    }

    private JsonNode postJson(String path, Object payload) throws IOException, InterruptedException {
        return postJsonDetailed(path, payload).data();
    }

    private ApiResponse postJsonDetailed(String path, Object payload) throws IOException, InterruptedException {
        byte[] body = objectMapper.writeValueAsBytes(payload);
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private ApiResponse send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body = readBounded(response.body());
        if (response.statusCode() == 401) {
            String rejectedToken = authorizationToken(request);
            String refreshedToken = tokenProvider.refresh(rejectedToken);
            HttpRequest retry = HttpRequest.newBuilder(request,
                            (name, value) -> !"Authorization".equalsIgnoreCase(name))
                    .header("Authorization", "Bearer " + refreshedToken).build();
            response = httpClient.send(retry, HttpResponse.BodyHandlers.ofInputStream());
            body = readBounded(response.body());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BenchmarkProtocolException("RAG_BENCHMARK_HTTP_" + response.statusCode(),
                    "RAG接口HTTP状态异常", response.statusCode(), body.length);
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new BenchmarkProtocolException("RAG_BENCHMARK_RESPONSE_INVALID", "RAG接口响应不是合法JSON",
                    response.statusCode(), body.length);
        }
        String code = optionalText(envelope, "code");
        if (!SUCCESS_CODE.equals(code)) {
            throw new BenchmarkApiException(code.isBlank() ? "RAG_BENCHMARK_API_FAILED" : code,
                    optionalText(envelope, "info"), response.statusCode(), body.length);
        }
        JsonNode data = envelope.get("data");
        if (data == null || data.isNull()) {
            throw new BenchmarkProtocolException("RAG_BENCHMARK_RESPONSE_INVALID", "RAG接口成功响应缺少data",
                    response.statusCode(), body.length);
        }
        return new ApiResponse(data, response.statusCode(), body.length);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri + path)).timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokenProvider.currentToken());
    }

    private String authorizationToken(HttpRequest request) {
        String value = request.headers().firstValue("Authorization").orElse("");
        return value.startsWith("Bearer ") ? value.substring(7) : value;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() + read > maxResponseBytes) {
                    throw new BenchmarkProtocolException("RAG_BENCHMARK_RESPONSE_TOO_LARGE", "RAG接口响应超过上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private HttpRequest.BodyPublisher bytes(String value) {
        return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }
    private String encodePath(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("RAG路径标识非法");
        }
        return value;
    }
    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) throw new BenchmarkProtocolException("RAG_BENCHMARK_RESPONSE_INVALID", "RAG响应字段缺失");
        return value;
    }
    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    public record KnowledgeBase(String knowledgeBaseId, String status) {}
    public record Upload(String documentId, String taskId, String status, boolean deduplicated) {}
    public record Document(String documentId, String status, String activeVersionId, long activeGeneration) {
        public boolean ready() { return "ready".equalsIgnoreCase(status) && !activeVersionId.isBlank(); }
    }
    public record IngestTask(String taskId, String status, String stage, int processedChunks,
                             int totalChunks, String errorCode, long revision) {
        public boolean completed() { return "completed".equalsIgnoreCase(status); }
        public boolean terminalFailure() {
            return Set.of("failed", "dead", "cancelled").contains(status.toLowerCase());
        }
    }
    public record Profile(String profileId, long revision, ProfileDefinition definition) {}
    public record Binding(String bindingId, String targetId, long revision) {}
    public record DebugResult(String retrievalId, List<String> rankedDocumentIds, List<String> citationHeadings,
                              boolean degraded, List<String> degradationReasons, Map<String, Long> timingsMs,
                              Map<String, Integer> candidateCounts, boolean diagnosticsTruncated,
                              int diagnosticCapturedCount, int diagnosticMaxCapturedCount,
                              List<DiagnosticCandidate> diagnosticCandidates, int httpStatus, int responseBytes) {}
    public record DiagnosticCandidate(String bindingId, String profileId, String stage, int rank,
                                      String knowledgeBaseId, String documentId, String versionId,
                                      long generation, String chunkId, String headingPath, String benchmarkDocumentId,
                                      Double denseScore, Double sparseScore,
                                      Double fusionScore, Double rerankScore, String outcome) {}

    private record ApiResponse(JsonNode data, int httpStatus, int responseBytes) {}

    interface BearerTokenProvider {
        String currentToken();
        String refresh(String rejectedToken) throws IOException, InterruptedException;

        static BearerTokenProvider fixed(String token) {
            return new BearerTokenProvider() {
                @Override public String currentToken() { return token; }
                @Override public String refresh(String rejectedToken) { return token; }
                @Override public String toString() { return "FixedBearerTokenProvider"; }
            };
        }
    }
    public record ProfileDefinition(String variant, String mode, boolean denseEnabled,
                                    boolean sparseEnabled, boolean rerankEnabled) {
        public static List<ProfileDefinition> ablations() {
            return List.of(new ProfileDefinition("dense", "dense", true, false, false),
                    new ProfileDefinition("sparse", "sparse", false, true, false),
                    new ProfileDefinition("hybrid_rrf", "hybrid", true, true, false),
                    new ProfileDefinition("hybrid_rrf_rerank", "hybrid", true, true, true));
        }
    }

    public static class BenchmarkApiException extends RuntimeException {
        private final String code;
        private final Integer httpStatus;
        private final Integer responseBytes;
        public BenchmarkApiException(String code, String message) {
            this(code, message, null, null);
        }
        public BenchmarkApiException(String code, String message, Integer httpStatus, Integer responseBytes) {
            super(message == null || message.isBlank() ? "RAG接口返回业务错误" : message);
            this.code = code;
            this.httpStatus = httpStatus;
            this.responseBytes = responseBytes;
        }
        public String code() { return code; }
        public Integer httpStatus() { return httpStatus; }
        public Integer responseBytes() { return responseBytes; }
    }
    public static class BenchmarkProtocolException extends RuntimeException {
        private final String code;
        private final Integer httpStatus;
        private final Integer responseBytes;
        public BenchmarkProtocolException(String code, String message) {
            this(code, message, null, null);
        }
        public BenchmarkProtocolException(String code, String message, Integer httpStatus, Integer responseBytes) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
            this.responseBytes = responseBytes;
        }
        public String code() { return code; }
        public Integer httpStatus() { return httpStatus; }
        public Integer responseBytes() { return responseBytes; }
    }
}
