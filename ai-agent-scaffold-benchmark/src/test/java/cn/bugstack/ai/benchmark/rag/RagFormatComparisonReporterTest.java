package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFormatComparisonReporterTest {

    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCompareTwoHundredPairedQueriesAndVerifyResourceEvidence() throws Exception {
        Path qrels = temp.resolve("qrels.tsv");
        Path queries = temp.resolve("queries.jsonl");
        Path documents = temp.resolve("documents.jsonl");
        StringBuilder qrelsText = new StringBuilder("query-id\tcorpus-id\tscore\n");
        StringBuilder queryText = new StringBuilder();
        StringBuilder documentText = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            qrelsText.append("q").append(index).append("\td").append(index).append("\t1\n");
            queryText.append(mapper.writeValueAsString(Map.of("queryId", "q" + index,
                    "text", "question " + index))).append('\n');
            for (String format : List.of("PDF", "DOCX")) {
                String extension = format.toLowerCase();
                documentText.append(mapper.writeValueAsString(Map.of(
                        "formatDocumentId", format + ":d" + index, "sourceDocumentId", "d" + index,
                        "queryId", "q" + index, "format", format, "complexity", "SIMPLE",
                        "relativePath", "prepared/" + extension + "/d" + index + "." + extension,
                        "bytes", 1, "sha256", "a".repeat(64), "evidenceMarker", "M" + index,
                        "canonicalTextChars", 10))).append('\n');
            }
        }
        Files.writeString(qrels, qrelsText);
        Files.writeString(queries, queryText);
        Files.writeString(documents, documentText);
        Path pdf = run("PDF", false);
        Path docx = run("DOCX", true);
        Path resources = resources();
        Path output = temp.resolve("comparison");

        RagFormatComparisonReporter.Result result = new RagFormatComparisonReporter(mapper).generate(
                new RagFormatComparisonReporter.Configuration(pdf, docx, qrels, queries, documents,
                        resources, output));

        assertEquals(200, result.report().manifest().queryCount());
        assertEquals(199, result.report().pairs().get("dense").bothHit());
        assertEquals(1, result.report().pairs().get("dense").pdfOnlyHit());
        assertTrue(result.report().resources().inspectUnchanged());
        assertEquals(1, result.report().resources().inspectedContainerCount());
        assertTrue(Files.readString(result.markdown()).contains("PDF/DOCX 同源RAG配对评测报告"));
    }

    private Path run(String format, boolean missFirst) throws Exception {
        Path directory = temp.resolve(format.toLowerCase() + "-run");
        Files.createDirectories(directory);
        mapper.writeValue(directory.resolve("run-manifest.json").toFile(), Map.of(
                "status", "completed", "format", format, "preprocessingStrategy", "IR_FULL",
                "completedDocumentCount", 200, "completedQueryResultCount", 800,
                "datasetTreeSha256", "t".repeat(64), "datasetManifestSha256", "m".repeat(64),
                "configSha256", "c".repeat(64)));
        StringBuilder records = new StringBuilder();
        for (String variant : RagFailureCaseReporter.VARIANTS) {
            for (int index = 0; index < 200; index++) {
                String document = missFirst && index == 0 ? "d1" : "d" + index;
                RagBenchmarkRunIO.RunRecord record = new RagBenchmarkRunIO.RunRecord(
                        format.toLowerCase() + "-run", variant, "q" + index, "hash",
                        "ret-" + variant + "-" + index, List.of(document), 10 + index,
                        false, List.of(), null, Map.of("rerankMs", 1L),
                        Map.of("denseCandidateCount", 1));
                records.append(mapper.writeValueAsString(record)).append('\n');
            }
        }
        Files.writeString(directory.resolve("run.jsonl"), records);
        StringBuilder documentResults = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            documentResults.append(mapper.writeValueAsString(Map.of(
                    "sourceDocumentId", "d" + index, "formatDocumentId", format + ":d" + index,
                    "complexity", "SIMPLE", "status", "completed", "stage", "completed",
                    "elapsedMs", 100 + index, "totalChunks", 2, "processedChunks", 2,
                    "relativePath", "prepared/" + format.toLowerCase() + "/d" + index,
                    "sourceSha256", "a".repeat(64)))).append('\n');
        }
        Files.writeString(directory.resolve("document-results.jsonl"), documentResults);
        return directory;
    }

    private Path resources() throws Exception {
        Path directory = temp.resolve("resources");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("local-process.jsonl"),
                "{\"capturedAt\":\"2026-01-01T00:00:00Z\",\"appProcess\":\"1 1.5 1024 00:01\",\"threadCount\":10}\n");
        Files.writeString(directory.resolve("remote-containers.jsonl"),
                "{\"capturedAt\":\"2026-01-01T00:00:00Z\",\"containers\":["
                        + "{\"Name\":\"rag-test\",\"CPUPerc\":\"2.5%\",\"MemPerc\":\"3.5%\",\"PIDs\":\"4\"}]}\n");
        Files.writeString(directory.resolve("remote-inspect-before.txt"),
                "2026-01-01T00:00:00Z\n/rag-test|0|false|running|healthy|image:1\n");
        Files.writeString(directory.resolve("remote-inspect-after.txt"),
                "2026-01-01T01:00:00Z\n/rag-test|0|false|running|healthy|image:1\n");
        Files.writeString(directory.resolve("remote-sampler.err.log"), "");
        return directory;
    }
}
