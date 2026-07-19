package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对失败评测的健康前缀执行强一致性校验，避免手工拼接或跨配置续跑。 */
final class RagBenchmarkResumeGate {

    static final String ERROR_CODE = "RAG_BENCHMARK_RESUME_GATE_FAILED";
    private static final String REQUEST_TIMEOUT_ERROR_CODE = "RAG_BENCHMARK_REQUEST_TIMEOUT";
    private static final String IO_ERROR_CODE = "RAG_BENCHMARK_IO";
    private static final String RERANK_VARIANT = "hybrid_rrf_rerank";
    private static final long MAX_METADATA_BYTES = 10L * 1024 * 1024;
    private static final long MAX_WARMUP_BYTES = 128L * 1024 * 1024;
    private static final long MAX_RUN_BYTES = 2L * 1024 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    RagBenchmarkResumeGate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ResumeState validate(Path sourceDirectory, Map<String, Object> currentManifest, String targetsSha256,
                         Map<String, String> currentTargets, List<ExpectedRecord> expectedWarmup,
                         List<ExpectedRecord> expectedMeasured, Set<String> documentIds) throws IOException {
        try {
            return validateChecked(sourceDirectory, currentManifest, targetsSha256, currentTargets,
                    expectedWarmup, expectedMeasured, documentIds);
        } catch (ResumeGateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new ResumeGateException("source_artifact_invalid", exception);
        }
    }

    private ResumeState validateChecked(Path sourceDirectory, Map<String, Object> currentManifest,
                                        String targetsSha256, Map<String, String> currentTargets,
                                        List<ExpectedRecord> expectedWarmup,
                                        List<ExpectedRecord> expectedMeasured,
                                        Set<String> documentIds) throws IOException {
        if (sourceDirectory == null || Files.isSymbolicLink(sourceDirectory)
                || !Files.isDirectory(sourceDirectory)) {
            throw new ResumeGateException("source_directory_missing");
        }
        Path sourceManifestPath = sourceDirectory.resolve("run-manifest.json");
        Path sourceTargetsPath = sourceDirectory.resolve("targets.json");
        Path sourceWarmupPath = sourceDirectory.resolve("warmup.jsonl");
        Path sourceRunPath = sourceDirectory.resolve("run.jsonl");
        if (!regularFile(sourceManifestPath, MAX_METADATA_BYTES)
                || !regularFile(sourceTargetsPath, MAX_METADATA_BYTES)
                || !regularFile(sourceWarmupPath, MAX_WARMUP_BYTES)
                || !regularFile(sourceRunPath, MAX_RUN_BYTES)
                || Files.exists(sourceDirectory.resolve("metrics.json"))) {
            throw new ResumeGateException("source_files_not_resumable");
        }

        JsonNode sourceManifest = objectMapper.readTree(sourceManifestPath.toFile());
        String sourceStatus = sourceManifest.path("status").asText();
        String sourceErrorCode = sourceManifest.path("errorCode").asText();
        String sourceErrorType = sourceManifest.path("errorType").asText();
        boolean requestTimeout = REQUEST_TIMEOUT_ERROR_CODE.equals(sourceErrorCode)
                || "HttpTimeoutException".equals(sourceErrorType);
        boolean resumableIo = requestTimeout || IO_ERROR_CODE.equals(sourceErrorCode);
        if (!"failed".equals(sourceStatus) || !resumableIo) {
            throw new ResumeGateException("source_termination_not_request_timeout");
        }

        requireManifestValue(sourceManifest, "schemaVersion", currentManifest.get("schemaVersion"));
        requireManifestValue(sourceManifest, "baseUrl", currentManifest.get("baseUrl"));
        requireManifestValue(sourceManifest, "dataset", currentManifest.get("dataset"));
        requireManifestValue(sourceManifest, "sourceRevision", currentManifest.get("sourceRevision"));
        requireManifestValue(sourceManifest, "documentCount", currentManifest.get("documentCount"));
        requireManifestValue(sourceManifest, "queryCount", currentManifest.get("queryCount"));
        requireManifestValue(sourceManifest, "markdownFile", currentManifest.get("markdownFile"));
        requireManifestValue(sourceManifest, "markdownBytes", currentManifest.get("markdownBytes"));
        requireManifestValue(sourceManifest, "markdownSha256", currentManifest.get("markdownSha256"));
        requireManifestValue(sourceManifest, "seed", currentManifest.get("seed"));
        requireManifestValue(sourceManifest, "warmupQueries", currentManifest.get("warmupQueries"));
        requireManifestValue(sourceManifest, "queryThreads", 1);
        requireManifestValue(sourceManifest, "uploadThreads", 1);
        requireManifestValue(sourceManifest, "workerThreads", 1);
        if (!sourceManifest.path("variants").equals(objectMapper.valueToTree(currentManifest.get("variants")))) {
            throw new ResumeGateException("source_manifest_mismatch:variants");
        }
        requireText(sourceManifest, "mode", "evaluate_existing_targets");
        requireText(sourceManifest, "targetsSha256", targetsSha256);
        boolean legacyPreparedFingerprint = validatePreparedFingerprints(sourceManifest, currentManifest);

        JsonNode sourceTargets = objectMapper.readTree(sourceTargetsPath.toFile());
        requireText(sourceTargets, "sourceSha256", targetsSha256);
        JsonNode targetValues = sourceTargets.path("targets");
        if (!targetValues.isObject() || targetValues.size() != currentTargets.size()) {
            throw new ResumeGateException("source_targets_shape_mismatch");
        }
        currentTargets.forEach((variant, targetId) -> {
            if (!targetId.equals(targetValues.path(variant).asText())) {
                throw new ResumeGateException("source_target_mismatch:" + variant);
            }
        });

        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(objectMapper);
        List<RagBenchmarkRunIO.RunRecord> warmupRecords = runIO.readRecords(sourceWarmupPath);
        List<RagBenchmarkRunIO.RunRecord> measuredRecords = runIO.readRecords(sourceRunPath);
        requireCanonicalJsonLines(sourceWarmupPath, warmupRecords.size());
        requireCanonicalJsonLines(sourceRunPath, measuredRecords.size());
        if (warmupRecords.size() != expectedWarmup.size()) {
            throw new ResumeGateException("source_warmup_count_mismatch");
        }
        if (measuredRecords.isEmpty() || measuredRecords.size() > expectedMeasured.size()) {
            throw new ResumeGateException("source_measured_count_not_partial");
        }

        Set<String> variants = Set.copyOf(currentTargets.keySet());
        new RagBenchmarkWarmupGate().validate(warmupRecords,
                Math.floorDiv(expectedWarmup.size(), variants.size()), variants);
        String sourceRunId = sourceManifest.path("runId").asText();
        if (sourceRunId.isBlank()) throw new ResumeGateException("source_run_id_missing");
        Lineage lineage = readLineage(sourceManifest, sourceRunId, measuredRecords.size());
        Set<String> retrievalIds = new HashSet<>();
        validateSequence(warmupRecords, expectedWarmup,
                java.util.Collections.nCopies(warmupRecords.size(), lineage.warmupRunId()), "warmup",
                documentIds, retrievalIds);
        validateSequence(measuredRecords, expectedMeasured.subList(0, measuredRecords.size()),
                lineage.expectedRunIds(), "measured", documentIds, retrievalIds);

        return new ResumeState(sourceRunId, sourceManifest.path("codeRevision").asText(),
                measuredRecords.size(), sha256(sourceManifestPath), sha256(sourceTargetsPath),
                sha256(sourceWarmupPath), sha256(sourceRunPath), lineage.warmupRunId(),
                lineage.completedSegments(), legacyPreparedFingerprint);
    }

    private void validateSequence(List<RagBenchmarkRunIO.RunRecord> records, List<ExpectedRecord> expected,
                                  List<String> expectedRunIds, String phase, Set<String> documentIds,
                                  Set<String> retrievalIds) {
        for (int index = 0; index < records.size(); index++) {
            RagBenchmarkRunIO.RunRecord record = records.get(index);
            ExpectedRecord wanted = expected.get(index);
            if (!expectedRunIds.get(index).equals(record.runId()) || !wanted.variant().equals(record.variant())
                    || !wanted.queryId().equals(record.queryId())
                    || !wanted.querySha256().equals(record.querySha256())) {
                throw new ResumeGateException("source_" + phase + "_sequence_mismatch_at_" + index);
            }
            boolean duplicateRanking = record.rankedDocumentIds().stream().distinct().count()
                    != record.rankedDocumentIds().size();
            boolean unknownDocument = record.rankedDocumentIds().stream().anyMatch(id -> !documentIds.contains(id));
            boolean invalidNumbers = record.elapsedMs() < 0
                    || record.stageTimingsMs().values().stream().anyMatch(value -> value == null || value < 0)
                    || record.candidateCounts().values().stream().anyMatch(value -> value == null || value < 0);
            if (blank(record.retrievalId()) || !blank(record.errorCode()) || record.degraded()
                    || !record.degradationReasons().isEmpty() || record.rankedDocumentIds().isEmpty()
                    || record.rankedDocumentIds().size() > 10 || duplicateRanking || unknownDocument
                    || invalidNumbers || !retrievalIds.add(record.retrievalId())
                    || positive(record.stageTimingsMs(), "totalMs") < 1) {
                throw new ResumeGateException("source_" + phase + "_unhealthy_at_" + index);
            }
            if (RERANK_VARIANT.equals(record.variant())
                    && (positive(record.candidateCounts(), "rerankCandidateCount") < 1
                    || positive(record.stageTimingsMs(), "rerankMs") < 1)) {
                throw new ResumeGateException("source_" + phase + "_rerank_invalid_at_" + index);
            }
        }
    }

    private boolean validatePreparedFingerprints(JsonNode sourceManifest, Map<String, Object> currentManifest) {
        List<String> fields = List.of("preparedManifestSha256", "queriesSha256", "qrelsSha256",
                "documentMapSha256");
        boolean legacy = fields.stream().allMatch(field -> sourceManifest.path(field).isMissingNode());
        if (!legacy) {
            fields.forEach(field -> requireManifestValue(sourceManifest, field, currentManifest.get(field)));
            requireManifestValue(sourceManifest, "resumeProtocolVersion",
                    currentManifest.get("resumeProtocolVersion"));
        }
        return legacy;
    }

    private Lineage readLineage(JsonNode sourceManifest, String sourceRunId, int measuredCount) {
        JsonNode resume = sourceManifest.path("resume");
        if (!resume.isObject()) {
            Segment segment = new Segment(sourceRunId, 0, measuredCount);
            return new Lineage(sourceRunId, java.util.Collections.nCopies(measuredCount, sourceRunId),
                    List.of(segment));
        }

        String warmupRunId = resume.path("warmupRunId").asText();
        JsonNode segmentNodes = resume.path("sourceSegments");
        if (warmupRunId.isBlank() || !segmentNodes.isArray()) {
            throw new ResumeGateException("source_lineage_missing");
        }
        List<Segment> inherited = new ArrayList<>();
        List<String> expectedRunIds = new ArrayList<>();
        int nextStart = 0;
        for (JsonNode node : segmentNodes) {
            Segment segment = new Segment(node.path("runId").asText(), node.path("startInclusive").asInt(-1),
                    node.path("endExclusive").asInt(-1));
            if (segment.runId().isBlank() || segment.startInclusive() != nextStart
                    || segment.endExclusive() <= segment.startInclusive()
                    || segment.endExclusive() > measuredCount) {
                throw new ResumeGateException("source_lineage_invalid");
            }
            inherited.add(segment);
            for (int index = segment.startInclusive(); index < segment.endExclusive(); index++) {
                expectedRunIds.add(segment.runId());
            }
            nextStart = segment.endExclusive();
        }
        if (nextStart < measuredCount) {
            Segment current = new Segment(sourceRunId, nextStart, measuredCount);
            inherited.add(current);
            for (int index = nextStart; index < measuredCount; index++) expectedRunIds.add(sourceRunId);
        }
        if (expectedRunIds.size() != measuredCount) {
            throw new ResumeGateException("source_lineage_does_not_cover_prefix");
        }
        return new Lineage(warmupRunId, List.copyOf(expectedRunIds), List.copyOf(inherited));
    }

    private boolean regularFile(Path path, long maxBytes) throws IOException {
        return !Files.isSymbolicLink(path) && Files.isRegularFile(path)
                && Files.size(path) > 0 && Files.size(path) <= maxBytes;
    }

    private void requireManifestValue(JsonNode manifest, String field, Object expected) {
        JsonNode value = manifest.path(field);
        boolean matches = expected instanceof Number number
                ? value.isNumber() && value.asLong() == number.longValue()
                : value.isTextual() && String.valueOf(expected).equals(value.asText());
        if (!matches) throw new ResumeGateException("source_manifest_mismatch:" + field);
    }

    private void requireText(JsonNode node, String field, String expected) {
        if (expected == null || !expected.equals(node.path(field).asText())) {
            throw new ResumeGateException("source_artifact_mismatch:" + field);
        }
    }

    private void requireCanonicalJsonLines(Path path, int recordCount) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != recordCount || lines.stream().anyMatch(String::isBlank)) {
            throw new ResumeGateException("source_jsonl_contains_blank_or_ignored_lines");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
            throw new ResumeGateException("source_jsonl_missing_terminal_newline");
        }
    }

    private long positive(Map<String, ? extends Number> values, String key) {
        Number value = values == null ? null : values.get(key);
        return value == null ? 0 : value.longValue();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (var input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    record ExpectedRecord(String variant, String queryId, String querySha256) {}

    private record Lineage(String warmupRunId, List<String> expectedRunIds,
                           List<Segment> completedSegments) {}

    record Segment(String runId, int startInclusive, int endExclusive) {}

    record ResumeState(String sourceRunId, String sourceCodeRevision, int resumedRecordCount,
                       String sourceManifestSha256, String sourceTargetsSha256,
                       String sourceWarmupSha256, String sourceRunSha256, String warmupRunId,
                       List<Segment> sourceSegments, boolean legacyPreparedFingerprint) {
        Map<String, Object> toManifest(String sourceDirectoryName, String currentRunId) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("mode", "validated_prefix_v1");
            values.put("sourceDirectory", sourceDirectoryName);
            values.put("sourceRunId", sourceRunId);
            values.put("sourceCodeRevision", sourceCodeRevision);
            values.put("resumedRecordCount", resumedRecordCount);
            values.put("sourceManifestSha256", sourceManifestSha256);
            values.put("sourceTargetsSha256", sourceTargetsSha256);
            values.put("sourceWarmupSha256", sourceWarmupSha256);
            values.put("sourceRunSha256", sourceRunSha256);
            values.put("warmupRunId", warmupRunId);
            values.put("sourceSegments", sourceSegments);
            values.put("nextRecordIndex", resumedRecordCount);
            values.put("currentSegmentRunId", currentRunId);
            values.put("legacyPreparedFingerprintCompatibility", legacyPreparedFingerprint);
            return Map.copyOf(values);
        }
    }

    static final class ResumeGateException extends IllegalStateException {
        ResumeGateException(String summary) {
            super(ERROR_CODE + ": " + summary);
        }

        ResumeGateException(String summary, Throwable cause) {
            super(ERROR_CODE + ": " + summary, cause);
        }

        String code() {
            return ERROR_CODE;
        }
    }
}
