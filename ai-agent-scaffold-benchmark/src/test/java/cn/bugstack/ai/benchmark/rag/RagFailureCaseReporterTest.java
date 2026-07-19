package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFailureCaseReporterTest {

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldGenerateDeterministicEvidenceAndVisibleGoldExcerpt() throws Exception {
        Fixture fixture = fixture(false);
        RagFailureCaseReporter reporter = new RagFailureCaseReporter(mapper);
        RagFailureCaseReporter.Report first = reporter.generate(fixture.configuration());
        RagFailureCaseReporter.Report second = reporter.generate(fixture.configuration());

        assertEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(second));
        assertEquals(3, first.manifest().queryCount());
        assertEquals(12, first.manifest().runRecordCount());
        assertEquals(1, first.manifest().availableCaseCounts().get("dense_miss_hybrid_hit"));
        assertEquals(1, first.manifest().availableCaseCounts().get("rerank_harm"));
        assertEquals(1, first.manifest().availableCaseCounts().get("rerank_rescue"));
        assertEquals(1, first.manifest().availableCaseCounts().get("persistent_miss"));
        RagFailureCaseReporter.CaseEvidence rescue = first.cases().get("rerank_rescue").get(0);
        assertEquals("q2", rescue.queryId());
        assertEquals("Gold two", rescue.goldDocuments().get(0).title());
        assertTrue(rescue.goldDocuments().get(0).excerpt().contains("gold two evidence"));
        assertEquals("not_captured_in_run_jsonl", rescue.variants().get("hybrid_rrf").scoreEvidence());

        Path json = temp.resolve("report.json");
        Path markdown = temp.resolve("report.md");
        reporter.write(first, json, markdown);
        String rendered = Files.readString(markdown);
        assertTrue(rendered.contains("问题：semantic question two"));
        assertTrue(rendered.contains("首个可观测失败步骤：`hybrid_rrf_final_top10_before_rerank`"));
        assertTrue(rendered.contains("逐候选分数=未采集"));
        assertTrue(rendered.contains("关键错误召回文档"));
        assertTrue(rendered.contains("unrelated distractor content"));
    }

    @Test
    void shouldRejectDegradedRunInsteadOfInventingCase() throws Exception {
        Fixture fixture = fixture(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RagFailureCaseReporter(mapper).generate(fixture.configuration()));
        assertTrue(error.getMessage().contains("错误、降级或空结果"));
    }

    @Test
    void shouldRefuseOverwritingEvidence() throws Exception {
        Fixture fixture = fixture(false);
        RagFailureCaseReporter reporter = new RagFailureCaseReporter(mapper);
        RagFailureCaseReporter.Report report = reporter.generate(fixture.configuration());
        Path json = temp.resolve("existing.json");
        Path markdown = temp.resolve("new.md");
        Files.writeString(json, "keep");
        assertThrows(IllegalArgumentException.class, () -> reporter.write(report, json, markdown));
        assertEquals("keep", Files.readString(json));
    }

    private Fixture fixture(boolean degraded) throws Exception {
        Path queries = temp.resolve("queries-" + degraded + ".jsonl");
        Path qrels = temp.resolve("qrels-" + degraded + ".tsv");
        Path documents = temp.resolve("documents-" + degraded + ".md");
        Path documentMap = temp.resolve("map-" + degraded + ".jsonl");
        Path run = temp.resolve("run-" + degraded + ".jsonl");
        Files.writeString(queries, """
                {"queryId":"q1","text":"lexical question one"}
                {"queryId":"q2","text":"semantic question two"}
                {"queryId":"q3","text":"persistent question three"}
                """.strip() + "\n");
        Files.writeString(qrels, "query-id\tcorpus-id\tscore\nq1\td1\t1\nq2\td2\t1\nq3\td3\t1\n");
        Files.writeString(documentMap, """
                {"documentId":"d1","headingMarker":"M1"}
                {"documentId":"d2","headingMarker":"M2"}
                {"documentId":"d3","headingMarker":"M3"}
                {"documentId":"n1","headingMarker":"MN"}
                """.strip() + "\n");
        Files.writeString(documents, """
                # M1 — Gold one

                gold one evidence and exact lexical terms

                # M2 — Gold two

                gold two evidence with semantic relation

                # M3 — Gold three

                gold three evidence never retrieved

                # MN — Distractor

                unrelated distractor content
                """.strip() + "\n");
        List<Run> rows = List.of(
                new Run("dense", "q1", List.of("n1"), degraded),
                new Run("sparse", "q1", List.of("d1"), false),
                new Run("hybrid_rrf", "q1", List.of("d1"), false),
                new Run("hybrid_rrf_rerank", "q1", List.of("n1"), false),
                new Run("dense", "q2", List.of("d2"), false),
                new Run("sparse", "q2", List.of("n1"), false),
                new Run("hybrid_rrf", "q2", List.of("n1"), false),
                new Run("hybrid_rrf_rerank", "q2", List.of("d2"), false),
                new Run("dense", "q3", List.of("n1"), false),
                new Run("sparse", "q3", List.of("n1"), false),
                new Run("hybrid_rrf", "q3", List.of("n1"), false),
                new Run("hybrid_rrf_rerank", "q3", List.of("n1"), false));
        StringBuilder jsonl = new StringBuilder();
        for (Run row : rows) {
            RagBenchmarkRunIO.RunRecord record = new RagBenchmarkRunIO.RunRecord("run", row.variant(), row.queryId(),
                    "hash", "ret", row.documents(), 10, row.degraded(), List.of(), null, Map.of(), Map.of());
            jsonl.append(mapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(run, jsonl);
        return new Fixture(new RagFailureCaseReporter.Configuration(queries, qrels, documents, documentMap, run, 3));
    }

    private record Run(String variant, String queryId, List<String> documents, boolean degraded) {}
    private record Fixture(RagFailureCaseReporter.Configuration configuration) {}
}
