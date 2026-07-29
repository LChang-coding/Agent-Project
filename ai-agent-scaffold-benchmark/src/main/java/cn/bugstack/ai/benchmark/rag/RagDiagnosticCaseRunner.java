package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对已选失败case执行有界真实debug请求并逐条保留内部阶段证据。 */
public final class RagDiagnosticCaseRunner {

    private final ObjectMapper mapper;
    private final RagBenchmarkHttpClient client;

    public RagDiagnosticCaseRunner(ObjectMapper mapper, RagBenchmarkHttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    public Result run(Configuration configuration) throws Exception {
        configuration.validate();
        if (Files.exists(configuration.outputDirectory())) throw new IllegalArgumentException("诊断输出目录必须不存在");
        Files.createDirectories(configuration.outputDirectory());
        Path manifestPath = configuration.outputDirectory().resolve("diagnostic-manifest.json");
        Path recordsPath = configuration.outputDirectory().resolve("diagnostic.jsonl");
        Map<String, QueryCase> queries = readCases(configuration.caseReport(), configuration.maxQueries());
        Map<String, String> targets = readTargets(configuration.targets());
        Instant started = Instant.now();
        writeManifest(manifestPath, manifest("running", configuration, queries.size(), 0, started, null, null));
        int completed = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(recordsPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (QueryCase query : queries.values()) {
                for (String variant : RagFailureCaseReporter.VARIANTS) {
                    long requestStarted = System.nanoTime();
                    RagBenchmarkHttpClient.DebugResult result = client.debug(targets.get(variant), query.question());
                    if (result.degraded() || result.rankedDocumentIds().isEmpty()
                            || result.diagnosticMaxCapturedCount() < 1
                            || result.diagnosticsTruncated()
                            || result.diagnosticCapturedCount() != result.diagnosticCandidates().size()
                            || result.diagnosticCandidates().stream().anyMatch(value ->
                            value.benchmarkDocumentId() == null || value.benchmarkDocumentId().isBlank())) {
                        throw new IllegalStateException("诊断请求不健康: " + query.queryId() + "/" + variant
                                + " " + healthSummary(result));
                    }
                    DiagnosticRecord record = new DiagnosticRecord(configuration.runId(), query.queryId(),
                            query.question(), query.categories(), variant, result.retrievalId(),
                            result.rankedDocumentIds(), (System.nanoTime() - requestStarted) / 1_000_000L,
                            result.timingsMs(), result.candidateCounts(), result.diagnosticCandidates(),
                            result.httpStatus(), result.responseBytes());
                    writer.write(mapper.writeValueAsString(record));
                    writer.newLine();
                    writer.flush();
                    completed++;
                }
            }
        } catch (Exception exception) {
            writeManifest(manifestPath, manifest("failed", configuration, queries.size(), completed, started,
                    Instant.now(), exception.getClass().getSimpleName()));
            throw exception;
        }
        String recordsSha256 = sha256(recordsPath);
        writeManifest(manifestPath, manifest("completed", configuration, queries.size(), completed, started,
                Instant.now(), recordsSha256));
        return new Result(queries.size(), completed, recordsPath, manifestPath, recordsSha256);
    }

    private String healthSummary(RagBenchmarkHttpClient.DebugResult result) {
        long missingBenchmarkDocumentIds = result.diagnosticCandidates().stream().filter(value ->
                value.benchmarkDocumentId() == null || value.benchmarkDocumentId().isBlank()).count();
        return "retrievalId=" + safeIdentity(result.retrievalId())
                + " degraded=" + result.degraded()
                + " rankedDocuments=" + result.rankedDocumentIds().size()
                + " diagnosticsTruncated=" + result.diagnosticsTruncated()
                + " diagnosticCapturedCount=" + result.diagnosticCapturedCount()
                + " diagnosticCandidateSize=" + result.diagnosticCandidates().size()
                + " diagnosticMaxCapturedCount=" + result.diagnosticMaxCapturedCount()
                + " missingBenchmarkDocumentIds=" + missingBenchmarkDocumentIds;
    }

    private String safeIdentity(String value) {
        return value == null || !value.matches("[A-Za-z0-9_.-]{1,160}") ? "unavailable" : value;
    }

    private Map<String, QueryCase> readCases(Path path, int maxQueries) throws IOException {
        JsonNode cases = mapper.readTree(path.toFile()).path("cases");
        if (!cases.isObject()) throw new IllegalArgumentException("失败案例报告缺少cases对象");
        Map<String, MutableQueryCase> values = new LinkedHashMap<>();
        cases.fields().forEachRemaining(category -> category.getValue().forEach(node -> {
            String queryId = node.path("queryId").asText();
            String question = node.path("question").asText();
            if (queryId.isBlank() || question.isBlank()) throw new IllegalArgumentException("失败案例缺少queryId/question");
            MutableQueryCase value = values.computeIfAbsent(queryId, ignored -> new MutableQueryCase(queryId, question));
            if (!value.question.equals(question)) throw new IllegalArgumentException("同queryId问题文本不一致");
            value.categories.add(category.getKey());
        }));
        Map<String, QueryCase> selected = new LinkedHashMap<>();
        values.values().stream().limit(maxQueries).forEach(value -> selected.put(value.queryId,
                new QueryCase(value.queryId, value.question, List.copyOf(value.categories))));
        if (selected.isEmpty()) throw new IllegalArgumentException("失败案例报告没有可诊断查询");
        return selected;
    }

    private Map<String, String> readTargets(Path path) throws IOException {
        JsonNode values = mapper.readTree(path.toFile()).path("targets");
        Map<String, String> targets = new LinkedHashMap<>();
        for (String variant : RagFailureCaseReporter.VARIANTS) {
            String target = values.path(variant).asText();
            if (!target.matches("[A-Za-z0-9_.-]{1,120}")) throw new IllegalArgumentException("targets缺少合法变体: " + variant);
            targets.put(variant, target);
        }
        if (!values.isObject() || values.size() != 4 || targets.values().stream().distinct().count() != 4) {
            throw new IllegalArgumentException("targets必须恰好包含4个唯一目标");
        }
        return targets;
    }

    private Map<String, Object> manifest(String status, Configuration configuration, int queryCount,
                                         int completedRecords, Instant started, Instant finished,
                                         String resultOrError) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1);
        values.put("status", status);
        values.put("runId", configuration.runId());
        values.put("codeRevision", configuration.codeRevision());
        values.put("caseReportSha256", sha256(configuration.caseReport()));
        values.put("targetsSha256", sha256(configuration.targets()));
        values.put("queryCount", queryCount);
        values.put("expectedRecordCount", queryCount * 4);
        values.put("completedRecordCount", completedRecords);
        values.put("requestTimeoutSeconds", configuration.requestTimeoutSeconds());
        values.put("startedAt", started.toString());
        values.put("finishedAt", finished == null ? null : finished.toString());
        values.put("diagnosticJsonlSha256", "completed".equals(status) ? resultOrError : null);
        values.put("failureType", "failed".equals(status) ? resultOrError : null);
        return values;
    }

    private void writeManifest(Path path, Map<String, Object> values) throws IOException {
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), values);
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Configuration(String runId, String codeRevision, Path caseReport, Path targets,
                                Path outputDirectory, int maxQueries, int requestTimeoutSeconds) {
        void validate() {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,120}")
                    || codeRevision == null || codeRevision.isBlank() || maxQueries < 1 || maxQueries > 100
                    || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 3600
                    || caseReport == null || targets == null || !Files.isRegularFile(caseReport)
                    || !Files.isRegularFile(targets) || outputDirectory == null) {
                throw new IllegalArgumentException("诊断运行配置非法");
            }
        }
    }

    public record DiagnosticRecord(String runId, String queryId, String question, List<String> categories,
                                   String variant, String retrievalId, List<String> rankedDocumentIds,
                                   long elapsedMs, Map<String, Long> stageTimingsMs,
                                   Map<String, Integer> candidateCounts,
                                   List<RagBenchmarkHttpClient.DiagnosticCandidate> diagnostics,
                                   int httpStatus, int responseBytes) {}
    public record Result(int queryCount, int recordCount, Path records, Path manifest, String recordsSha256) {}
    private record QueryCase(String queryId, String question, List<String> categories) {}
    private static final class MutableQueryCase {
        private final String queryId;
        private final String question;
        private final Set<String> categories = new LinkedHashSet<>();
        private MutableQueryCase(String queryId, String question) { this.queryId = queryId; this.question = question; }
    }
}
