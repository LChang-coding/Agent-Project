package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagInternalDiagnosticReporterTest {

    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldLocateFusionLossAndKeepVisibleCompetingDocument() throws Exception {
        Fixture fixture = singleGoldFixture();
        RagInternalDiagnosticReporter reporter = new RagInternalDiagnosticReporter(mapper);
        RagInternalDiagnosticReporter.Report report = reporter.generate(fixture.configuration());
        Path json = temp.resolve("report.json");
        Path markdown = temp.resolve("report.md");
        reporter.write(report, json, markdown);

        assertTrue(report.manifest().integrityHealthy());
        assertEquals(4, report.manifest().exactFinalRankingMatches());
        RagInternalDiagnosticReporter.VariantAnalysis dense = report.queries().get(0).variants().get("dense");
        assertEquals("FUSION_THRESHOLD_OR_TOPK_LOSS", dense.firstObservedTotalLoss().code());
        assertEquals("wrong", dense.competingDocuments().get(0).documentId());
        assertTrue(Files.readString(markdown).contains("内部阶段失败证据报告"));
        assertTrue(Files.readString(markdown).contains("Wrong body evidence"));
    }

    @Test
    void shouldRejectMissingStageInsteadOfInventingLoss() throws Exception {
        Fixture fixture = singleGoldFixture();
        List<RagDiagnosticCaseRunner.DiagnosticRecord> records = readRecords(fixture.diagnostics());
        RagDiagnosticCaseRunner.DiagnosticRecord dense = records.get(0);
        records.set(0, copy(dense, dense.diagnostics().stream()
                .filter(value -> !"pre_assembly".equals(value.stage())).toList()));
        writeRecords(fixture, records);
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldRejectMultipleBindingsInOneRecord() throws Exception {
        Fixture fixture = singleGoldFixture();
        List<RagDiagnosticCaseRunner.DiagnosticRecord> records = readRecords(fixture.diagnostics());
        RagDiagnosticCaseRunner.DiagnosticRecord dense = records.get(0);
        List<RagBenchmarkHttpClient.DiagnosticCandidate> changed = new ArrayList<>(dense.diagnostics());
        RagBenchmarkHttpClient.DiagnosticCandidate original = changed.get(0);
        changed.set(0, new RagBenchmarkHttpClient.DiagnosticCandidate("other-binding", original.profileId(),
                original.stage(), original.rank(), original.knowledgeBaseId(), original.documentId(),
                original.versionId(), original.generation(), original.chunkId(), original.headingPath(),
                original.benchmarkDocumentId(), original.denseScore(), original.sparseScore(),
                original.fusionScore(), original.rerankScore(), original.outcome()));
        records.set(0, copy(dense, changed));
        writeRecords(fixture, records);
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldRejectManifestHashMismatch() throws Exception {
        Fixture fixture = singleGoldFixture();
        Files.writeString(fixture.diagnostics(), Files.readString(fixture.diagnostics()) + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldRejectFailureReportGoldThatDoesNotMatchQrels() throws Exception {
        Fixture fixture = singleGoldFixture();
        Files.writeString(fixture.qrels(), "query-id\tcorpus-id\tscore\nq1\twrong\t1\n");
        rewriteFailureHashesAndManifest(fixture);
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldRejectDuplicateDocumentMapMarker() throws Exception {
        Fixture fixture = singleGoldFixture();
        String first = Files.readAllLines(fixture.documentMap()).get(0);
        Files.writeString(fixture.documentMap(), Files.readString(fixture.documentMap()) + first + "\n");
        rewriteFailureHashesAndManifest(fixture);
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldRejectCrossVariantCandidateDrift() throws Exception {
        Fixture fixture = singleGoldFixture();
        List<RagDiagnosticCaseRunner.DiagnosticRecord> records = readRecords(fixture.diagnostics());
        RagDiagnosticCaseRunner.DiagnosticRecord hybrid = records.get(2);
        List<RagBenchmarkHttpClient.DiagnosticCandidate> changed = new ArrayList<>();
        for (RagBenchmarkHttpClient.DiagnosticCandidate value : hybrid.diagnostics()) {
            changed.add("dense_raw".equals(value.stage()) && value.rank() == 1
                    ? new RagBenchmarkHttpClient.DiagnosticCandidate(value.bindingId(), value.profileId(), value.stage(),
                    value.rank(), value.knowledgeBaseId(), value.documentId(), value.versionId(), value.generation(),
                    value.chunkId(), value.headingPath(), value.benchmarkDocumentId(), value.denseScore() + 0.01,
                    value.sparseScore(), value.fusionScore(), value.rerankScore(), value.outcome()) : value);
        }
        records.set(2, copy(hybrid, changed));
        writeRecords(fixture, records);
        assertThrows(IllegalArgumentException.class,
                () -> new RagInternalDiagnosticReporter(mapper).generate(fixture.configuration()));
    }

    @Test
    void shouldClassifyMultiGoldOppositeRankMovesAsMixed() throws Exception {
        List<String> gold = List.of("gold-a", "gold-b");
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        rankings.put("dense", List.of("gold-a", "wrong", "gold-b"));
        rankings.put("sparse", List.of("gold-a", "wrong", "gold-b"));
        rankings.put("hybrid_rrf", List.of("gold-a", "wrong", "gold-b"));
        rankings.put("hybrid_rrf_rerank", List.of("gold-b", "gold-a", "wrong"));
        List<RagDiagnosticCaseRunner.DiagnosticRecord> records = List.of(
                record("dense", rankings.get("dense"), nonRerankStages("dense_raw", gold)),
                record("sparse", rankings.get("sparse"), nonRerankStages("sparse_raw", gold)),
                record("hybrid_rrf", rankings.get("hybrid_rrf"), hybridStages(gold, false)),
                record("hybrid_rrf_rerank", rankings.get("hybrid_rrf_rerank"), hybridStages(gold, true)));
        Fixture fixture = writeFixture(gold, rankings, records);

        RagInternalDiagnosticReporter.RerankEffect effect = new RagInternalDiagnosticReporter(mapper)
                .generate(fixture.configuration()).queries().get(0).variants().get("hybrid_rrf_rerank").rerankEffect();
        assertEquals("RERANK_MIXED", effect.classification());
        assertEquals(List.of("HARM", "GAIN"), effect.perGold().stream()
                .map(RagInternalDiagnosticReporter.GoldRankDelta::direction).toList());
        assertEquals(1D, effect.recallBefore());
        assertEquals(1D, effect.recallAfter());
    }

    private Fixture singleGoldFixture() throws Exception {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        rankings.put("dense", List.of("wrong"));
        rankings.put("sparse", List.of("gold"));
        rankings.put("hybrid_rrf", List.of("gold"));
        rankings.put("hybrid_rrf_rerank", List.of("gold"));
        String gold = RagBenchmarkArtifactWriter.marker("gold");
        String wrong = RagBenchmarkArtifactWriter.marker("wrong");
        return writeFixture(List.of("gold"), rankings, List.of(
                record("dense", rankings.get("dense"), dense(gold, wrong)),
                record("sparse", rankings.get("sparse"), sparse(gold)),
                record("hybrid_rrf", rankings.get("hybrid_rrf"), hybrid(gold, wrong, false)),
                record("hybrid_rrf_rerank", rankings.get("hybrid_rrf_rerank"), hybrid(gold, wrong, true))));
    }

    private Fixture writeFixture(List<String> goldIds, Map<String, List<String>> rankings,
                                 List<RagDiagnosticCaseRunner.DiagnosticRecord> records) throws Exception {
        Path failure = temp.resolve("failure.json");
        Path diagnostics = temp.resolve("diagnostic.jsonl");
        Path diagnosticManifest = temp.resolve("diagnostic-manifest.json");
        Path qrels = temp.resolve("qrels.tsv");
        Path documents = temp.resolve("benchmark.md");
        Path documentMap = temp.resolve("document-map.jsonl");
        List<String> all = new ArrayList<>(goldIds);
        if (!all.contains("wrong")) all.add("wrong");
        StringBuilder markdown = new StringBuilder();
        StringBuilder mappings = new StringBuilder();
        for (String id : all) {
            String marker = RagBenchmarkArtifactWriter.marker(id);
            String title = "wrong".equals(id) ? "Wrong title" : "Gold " + id;
            String body = "wrong".equals(id) ? "Wrong body evidence" : "Gold body evidence " + id;
            markdown.append("# ").append(marker).append(" — ").append(title).append("\n\n").append(body).append(".\n\n");
            mappings.append(mapper.writeValueAsString(Map.of("documentId", id, "headingMarker", marker))).append('\n');
        }
        Files.writeString(documents, markdown);
        Files.writeString(documentMap, mappings);
        StringBuilder qrel = new StringBuilder("query-id\tcorpus-id\tscore\n");
        goldIds.forEach(id -> qrel.append("q1\t").append(id).append("\t1\n"));
        Files.writeString(qrels, qrel);
        Map<String, Object> variants = new LinkedHashMap<>();
        rankings.forEach((name, ranking) -> variants.put(name,
                Map.of("ranking", ranking.stream().map(id -> Map.of("documentId", id)).toList())));
        List<Map<String, String>> goldDocuments = goldIds.stream().map(id -> Map.of(
                "documentId", id, "title", "Gold " + id, "excerpt", "Gold body evidence " + id,
                "headingMarker", RagBenchmarkArtifactWriter.marker(id))).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("queryId", "q1"); value.put("question", "question");
        value.put("goldDocuments", goldDocuments); value.put("variants", variants);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("inputSha256", Map.of("qrels", sha256(qrels), "documents", sha256(documents),
                "documentMap", sha256(documentMap)));
        mapper.writeValue(failure.toFile(), Map.of("manifest", manifest,
                "cases", Map.of("dense_miss_hybrid_hit", List.of(value))));
        Fixture fixture = new Fixture(failure, diagnostics, diagnosticManifest, qrels, documents, documentMap);
        writeRecords(fixture, records);
        return fixture;
    }

    private void writeRecords(Fixture fixture, List<RagDiagnosticCaseRunner.DiagnosticRecord> records) throws Exception {
        StringBuilder lines = new StringBuilder();
        for (RagDiagnosticCaseRunner.DiagnosticRecord record : records)
            lines.append(mapper.writeValueAsString(record)).append('\n');
        Files.writeString(fixture.diagnostics(), lines);
        rewriteDiagnosticManifest(fixture, records.size());
    }

    @SuppressWarnings("unchecked")
    private void rewriteFailureHashesAndManifest(Fixture fixture) throws Exception {
        Map<String, Object> root = mapper.readValue(fixture.failure().toFile(), LinkedHashMap.class);
        ((Map<String, Object>) root.get("manifest")).put("inputSha256", Map.of(
                "qrels", sha256(fixture.qrels()), "documents", sha256(fixture.documents()),
                "documentMap", sha256(fixture.documentMap())));
        mapper.writeValue(fixture.failure().toFile(), root);
        rewriteDiagnosticManifest(fixture, readRecords(fixture.diagnostics()).size());
    }

    private void rewriteDiagnosticManifest(Fixture fixture, int count) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1); manifest.put("status", "completed");
        manifest.put("runId", "run"); manifest.put("codeRevision", "revision");
        manifest.put("caseReportSha256", sha256(fixture.failure()));
        manifest.put("targetsSha256", "targets-sha"); manifest.put("queryCount", 1);
        manifest.put("expectedRecordCount", count); manifest.put("completedRecordCount", count);
        manifest.put("diagnosticJsonlSha256", sha256(fixture.diagnostics()));
        mapper.writeValue(fixture.diagnosticManifest().toFile(), manifest);
    }

    private List<RagDiagnosticCaseRunner.DiagnosticRecord> readRecords(Path path) throws Exception {
        List<RagDiagnosticCaseRunner.DiagnosticRecord> result = new ArrayList<>();
        for (String line : Files.readAllLines(path)) if (!line.isBlank())
            result.add(mapper.readValue(line, RagDiagnosticCaseRunner.DiagnosticRecord.class));
        return result;
    }

    private RagDiagnosticCaseRunner.DiagnosticRecord copy(RagDiagnosticCaseRunner.DiagnosticRecord value,
                                                           List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates) {
        return new RagDiagnosticCaseRunner.DiagnosticRecord(value.runId(), value.queryId(), value.question(),
                value.categories(), value.variant(), value.retrievalId(), value.rankedDocumentIds(), value.elapsedMs(),
                value.stageTimingsMs(), value.candidateCounts(), candidates, value.httpStatus(), value.responseBytes());
    }

    private RagDiagnosticCaseRunner.DiagnosticRecord record(String variant, List<String> ranking,
                                                              List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("denseCandidateCount", count(candidates, "dense_raw"));
        counts.put("sparseCandidateCount", count(candidates, "sparse_raw"));
        counts.put("rerankCandidateCount", count(candidates, "rerank_input"));
        counts.put("fusionCandidateCount", count(candidates, "fusion"));
        return new RagDiagnosticCaseRunner.DiagnosticRecord("run", "q1", "question",
                List.of("dense_miss_hybrid_hit"), variant, "ret-" + variant, ranking, 1,
                Map.of("totalMs", 1L), counts, candidates, 200, 1024);
    }

    private int count(List<RagBenchmarkHttpClient.DiagnosticCandidate> values, String stage) {
        return (int) values.stream().filter(value -> stage.equals(value.stage())).count();
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> dense(String gold, String wrong) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = new ArrayList<>();
        values.add(candidate("dense_raw", 1, "wrong", wrong, 0.9, null, null, null, "returned_by_vector_store"));
        values.add(candidate("dense_raw", 2, "gold", gold, 0.7, null, null, null, "returned_by_vector_store"));
        addFinal(values, "wrong", wrong, false, 0.9, null);
        return values;
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> sparse(String gold) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = new ArrayList<>();
        values.add(candidate("sparse_raw", 1, "gold", gold, null, 1.0, null, null, "returned_by_vector_store"));
        addFinal(values, "gold", gold, false, null, 1.0);
        return values;
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> hybrid(String gold, String wrong, boolean rerank) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = new ArrayList<>();
        values.add(candidate("dense_raw", 1, "wrong", wrong, 0.9, null, null, null, "returned_by_vector_store"));
        values.add(candidate("dense_raw", 2, "gold", gold, 0.7, null, null, null, "returned_by_vector_store"));
        values.add(candidate("sparse_raw", 1, "gold", gold, null, 1.0, null, null, "returned_by_vector_store"));
        addFinal(values, "gold", gold, rerank, 0.7, 1.0);
        return values;
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> nonRerankStages(String rawStage, List<String> gold) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = new ArrayList<>();
        List<String> ordered = List.of(gold.get(0), "wrong", gold.get(1));
        for (int i = 0; i < ordered.size(); i++) {
            String id = ordered.get(i); String marker = RagBenchmarkArtifactWriter.marker(id);
            values.add(candidate(rawStage, i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    null, null, "returned_by_vector_store"));
            values.add(candidate("fusion", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "kept_after_fusion_threshold_topk"));
            values.add(candidate("candidate_filter", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "kept"));
            values.add(candidate("pre_assembly", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "kept_without_rerank"));
            values.add(candidate("context_budget", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "accepted_citation"));
        }
        return values;
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> hybridStages(List<String> gold, boolean rerank) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = nonRerankStages("dense_raw", gold);
        values.removeIf(value -> List.of("fusion", "candidate_filter", "pre_assembly", "context_budget").contains(value.stage()));
        List<String> input = List.of(gold.get(0), "wrong", gold.get(1));
        for (int i = 0; i < input.size(); i++) values.add(candidate("sparse_raw", i + 1, input.get(i),
                RagBenchmarkArtifactWriter.marker(input.get(i)), 0.9 - i * 0.1, 1.0 - i * 0.1,
                null, null, "returned_by_vector_store"));
        for (int i = 0; i < input.size(); i++) {
            String id = input.get(i); String marker = RagBenchmarkArtifactWriter.marker(id);
            values.add(candidate("fusion", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "kept_after_fusion_threshold_topk"));
            values.add(candidate("candidate_filter", i + 1, id, marker, 0.9 - i * 0.1, 1.0 - i * 0.1,
                    0.8 - i * 0.1, null, "kept"));
        }
        List<String> output = rerank ? List.of(gold.get(1), gold.get(0), "wrong") : input;
        for (int i = 0; i < input.size(); i++) {
            String id = input.get(i); String marker = RagBenchmarkArtifactWriter.marker(id);
            values.add(candidate(rerank ? "rerank_input" : "pre_assembly", i + 1, id, marker,
                    0.9 - i * 0.1, 1.0 - i * 0.1, 0.8 - i * 0.1, null,
                    rerank ? "sent_to_reranker" : "kept_without_rerank"));
        }
        for (int i = 0; i < output.size(); i++) {
            String id = output.get(i); String marker = RagBenchmarkArtifactWriter.marker(id);
            if (rerank) values.add(candidate("rerank_output", i + 1, id, marker, 0.8, 0.8, 0.8,
                    1.0 - i * 0.1, "kept_after_rerank"));
            values.add(candidate("context_budget", i + 1, id, marker, 0.8, 0.8, 0.8,
                    rerank ? 1.0 - i * 0.1 : null, "accepted_citation"));
        }
        return values;
    }

    private void addFinal(List<RagBenchmarkHttpClient.DiagnosticCandidate> values, String id, String marker,
                          boolean rerank, Double dense, Double sparse) {
        values.add(candidate("fusion", 1, id, marker, dense, sparse, 0.8, null, "kept_after_fusion_threshold_topk"));
        values.add(candidate("candidate_filter", 1, id, marker, dense, sparse, 0.8, null, "kept"));
        values.add(candidate(rerank ? "rerank_input" : "pre_assembly", 1, id, marker, dense, sparse, 0.8,
                null, rerank ? "sent_to_reranker" : "kept_without_rerank"));
        if (rerank) values.add(candidate("rerank_output", 1, id, marker, dense, sparse, 0.8,
                0.9, "kept_after_rerank"));
        values.add(candidate("context_budget", 1, id, marker, dense, sparse, 0.8,
                rerank ? 0.9 : null, "accepted_citation"));
    }

    private RagBenchmarkHttpClient.DiagnosticCandidate candidate(String stage, int rank, String documentId,
                                                                  String heading, Double dense, Double sparse,
                                                                  Double fusion, Double rerank, String outcome) {
        return new RagBenchmarkHttpClient.DiagnosticCandidate("binding", "profile", stage, rank, "kb",
                "source-doc", "version", 1, "chunk-" + stage + "-" + rank + "-" + documentId,
                heading + " — title", documentId, dense, sparse, fusion, rerank, outcome);
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Fixture(Path failure, Path diagnostics, Path diagnosticManifest, Path qrels,
                           Path documents, Path documentMap) {
        private RagInternalDiagnosticReporter.Configuration configuration() {
            return new RagInternalDiagnosticReporter.Configuration(failure, diagnostics, diagnosticManifest,
                    qrels, documents, documentMap);
        }
    }
}
