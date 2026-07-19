package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagBenchmarkResumeGateTest {

    private static final String TARGETS_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final List<String> VARIANTS = List.of(
            "dense", "sparse", "hybrid_rrf", "hybrid_rrf_rerank");

    @TempDir Path temporary;
    private final AtomicInteger retrievalSequence = new AtomicInteger();

    @Test
    void shouldAcceptStrictHealthyPrefix() throws Exception {
        Fixture fixture = fixture(null, null);

        RagBenchmarkResumeGate.ResumeState state = new RagBenchmarkResumeGate(fixture.mapper()).validate(
                        fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                        fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1"));

        assertEquals(3, state.resumedRecordCount());
        assertEquals("source-run", state.sourceRunId());
    }

    @Test
    void shouldRejectTamperedSequence() throws Exception {
        Fixture fixture = fixture("tampered-query-hash", null);

        RagBenchmarkResumeGate.ResumeGateException exception = assertThrows(
                RagBenchmarkResumeGate.ResumeGateException.class,
                () -> new RagBenchmarkResumeGate(fixture.mapper()).validate(
                        fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                        fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1")));

        assertEquals(RagBenchmarkResumeGate.ERROR_CODE, exception.code());
    }

    @Test
    void shouldRejectUnhealthyPrefix() throws Exception {
        Fixture fixture = fixture(null, "RAG_BENCHMARK_HTTP_500");

        RagBenchmarkResumeGate.ResumeGateException exception = assertThrows(
                RagBenchmarkResumeGate.ResumeGateException.class,
                () -> new RagBenchmarkResumeGate(fixture.mapper()).validate(
                        fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                        fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1")));

        assertEquals(RagBenchmarkResumeGate.ERROR_CODE, exception.code());
    }

    @Test
    void shouldRejectPreparedInputMismatch() throws Exception {
        Fixture fixture = fixture(null, null);
        fixture.currentManifest().put("sourceRevision", "different-source");

        RagBenchmarkResumeGate.ResumeGateException exception = assertThrows(
                RagBenchmarkResumeGate.ResumeGateException.class,
                () -> new RagBenchmarkResumeGate(fixture.mapper()).validate(
                        fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                        fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1")));

        assertEquals(RagBenchmarkResumeGate.ERROR_CODE, exception.code());
    }

    @Test
    void shouldAcceptCompletePrefixForScoreOnlyRecovery() throws Exception {
        Fixture fixture = fixture(null, null);
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(fixture.mapper());
        for (int index = 3; index < fixture.expectedMeasured().size(); index++) {
            append(runIO, fixture.source().resolve("run.jsonl"),
                    healthy(fixture.expectedMeasured().get(index), null));
        }

        RagBenchmarkResumeGate.ResumeState state = new RagBenchmarkResumeGate(fixture.mapper()).validate(
                fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1"));

        assertEquals(8, state.resumedRecordCount());
    }

    @Test
    void shouldAcceptValidatedChainedLineage() throws Exception {
        Fixture fixture = fixture(null, null);
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(fixture.mapper());
        List<RagBenchmarkRunIO.RunRecord> warmup = runIO.readRecords(
                fixture.source().resolve("warmup.jsonl"));
        List<RagBenchmarkRunIO.RunRecord> measured = runIO.readRecords(
                fixture.source().resolve("run.jsonl"));
        Files.delete(fixture.source().resolve("warmup.jsonl"));
        Files.delete(fixture.source().resolve("run.jsonl"));
        warmup.forEach(record -> append(runIO, fixture.source().resolve("warmup.jsonl"),
                withRunId(record, "original-run")));
        for (int index = 0; index < measured.size(); index++) {
            append(runIO, fixture.source().resolve("run.jsonl"),
                    withRunId(measured.get(index), index == 0 ? "original-run" : "source-run"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceManifest = fixture.mapper().readValue(
                fixture.source().resolve("run-manifest.json").toFile(), Map.class);
        sourceManifest.put("resume", Map.of(
                "warmupRunId", "original-run",
                "sourceSegments", List.of(Map.of("runId", "original-run",
                        "startInclusive", 0, "endExclusive", 1))));
        fixture.mapper().writeValue(fixture.source().resolve("run-manifest.json").toFile(), sourceManifest);

        RagBenchmarkResumeGate.ResumeState state = new RagBenchmarkResumeGate(fixture.mapper()).validate(
                fixture.source(), fixture.currentManifest(), TARGETS_SHA, fixture.targets(),
                fixture.expectedWarmup(), fixture.expectedMeasured(), Set.of("doc-1"));

        assertEquals(2, state.sourceSegments().size());
        assertEquals("original-run", state.warmupRunId());
    }

    private Fixture fixture(String firstQuerySha, String firstErrorCode) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path source = temporary.resolve("source-" + Files.list(temporary).count());
        Files.createDirectories(source);
        Map<String, Object> currentManifest = new LinkedHashMap<>();
        currentManifest.put("schemaVersion", 1);
        currentManifest.put("baseUrl", "http://127.0.0.1:8092/api");
        currentManifest.put("dataset", "scifact");
        currentManifest.put("sourceRevision", "source-revision");
        currentManifest.put("documentCount", 2);
        currentManifest.put("queryCount", 2);
        currentManifest.put("markdownFile", "benchmark-0001.md");
        currentManifest.put("markdownBytes", 100L);
        currentManifest.put("markdownSha256", "markdown-sha");
        currentManifest.put("seed", 20260719L);
        currentManifest.put("warmupQueries", 1);
        currentManifest.put("queryThreads", 1);
        currentManifest.put("uploadThreads", 1);
        currentManifest.put("workerThreads", 1);
        currentManifest.put("variants", RagBenchmarkHttpClient.ProfileDefinition.ablations());

        Map<String, Object> sourceManifest = new LinkedHashMap<>(currentManifest);
        sourceManifest.put("runId", "source-run");
        sourceManifest.put("codeRevision", "source-code");
        sourceManifest.put("status", "failed");
        sourceManifest.put("mode", "evaluate_existing_targets");
        sourceManifest.put("targetsSha256", TARGETS_SHA);
        sourceManifest.put("errorType", "HttpTimeoutException");
        mapper.writeValue(source.resolve("run-manifest.json").toFile(), sourceManifest);

        Map<String, String> targets = new LinkedHashMap<>();
        VARIANTS.forEach(variant -> targets.put(variant, "target-" + variant));
        mapper.writeValue(source.resolve("targets.json").toFile(),
                Map.of("schemaVersion", 1, "sourceSha256", TARGETS_SHA, "targets", targets));

        List<RagBenchmarkResumeGate.ExpectedRecord> warmup = VARIANTS.stream()
                .map(variant -> new RagBenchmarkResumeGate.ExpectedRecord(variant, "q1", "sha-q1"))
                .toList();
        List<RagBenchmarkResumeGate.ExpectedRecord> measured = List.of(
                new RagBenchmarkResumeGate.ExpectedRecord("dense", "q1", "sha-q1"),
                new RagBenchmarkResumeGate.ExpectedRecord("sparse", "q1", "sha-q1"),
                new RagBenchmarkResumeGate.ExpectedRecord("hybrid_rrf", "q1", "sha-q1"),
                new RagBenchmarkResumeGate.ExpectedRecord("hybrid_rrf_rerank", "q1", "sha-q1"),
                new RagBenchmarkResumeGate.ExpectedRecord("sparse", "q2", "sha-q2"),
                new RagBenchmarkResumeGate.ExpectedRecord("hybrid_rrf", "q2", "sha-q2"),
                new RagBenchmarkResumeGate.ExpectedRecord("hybrid_rrf_rerank", "q2", "sha-q2"),
                new RagBenchmarkResumeGate.ExpectedRecord("dense", "q2", "sha-q2"));
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(mapper);
        warmup.forEach(expected -> append(runIO, source.resolve("warmup.jsonl"), healthy(expected, null)));
        for (int index = 0; index < 3; index++) {
            RagBenchmarkResumeGate.ExpectedRecord expected = measured.get(index);
            String querySha = index == 0 && firstQuerySha != null ? firstQuerySha : expected.querySha256();
            append(runIO, source.resolve("run.jsonl"), healthy(
                    new RagBenchmarkResumeGate.ExpectedRecord(expected.variant(), expected.queryId(), querySha),
                    index == 0 ? firstErrorCode : null));
        }
        return new Fixture(mapper, source, currentManifest, targets, warmup, measured);
    }

    private RagBenchmarkRunIO.RunRecord healthy(RagBenchmarkResumeGate.ExpectedRecord expected, String errorCode) {
        return new RagBenchmarkRunIO.RunRecord("source-run", expected.variant(), expected.queryId(),
                expected.querySha256(), "ret-" + retrievalSequence.incrementAndGet(), List.of("doc-1"),
                1, false, List.of(), errorCode,
                Map.of("totalMs", 1L, "rerankMs", 1L), Map.of("rerankCandidateCount", 1));
    }

    private RagBenchmarkRunIO.RunRecord withRunId(RagBenchmarkRunIO.RunRecord record, String runId) {
        return new RagBenchmarkRunIO.RunRecord(runId, record.variant(), record.queryId(), record.querySha256(),
                record.retrievalId(), record.rankedDocumentIds(), record.elapsedMs(), record.degraded(),
                record.degradationReasons(), record.errorCode(), record.stageTimingsMs(), record.candidateCounts());
    }

    private void append(RagBenchmarkRunIO runIO, Path path, RagBenchmarkRunIO.RunRecord record) {
        try {
            runIO.append(path, record);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(ObjectMapper mapper, Path source, Map<String, Object> currentManifest,
                           Map<String, String> targets,
                           List<RagBenchmarkResumeGate.ExpectedRecord> expectedWarmup,
                           List<RagBenchmarkResumeGate.ExpectedRecord> expectedMeasured) {}
}
