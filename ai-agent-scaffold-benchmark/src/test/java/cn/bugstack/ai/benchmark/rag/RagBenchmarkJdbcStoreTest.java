package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagBenchmarkJdbcStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPersistTwoHundredDocumentsEightHundredQueriesAndFourAggregatesIdempotently(
            @TempDir Path temporary) throws Exception {
        Path dataset = Path.of("../docs/rag/evaluation-data/pdf-docx-200").toAbsolutePath().normalize();
        Path runManifest = temporary.resolve("run-manifest.json");
        Path documentResults = temporary.resolve("document-results.jsonl");
        Path runRecords = temporary.resolve("run.jsonl");
        String runId = "jdbc-format-pdf";
        writeArtifacts(dataset, runId, runManifest, documentResults, runRecords);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:rag_benchmark;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            createSchema(connection);
            RagBenchmarkJdbcStore store = new RagBenchmarkJdbcStore(mapper);
            RagBenchmarkJdbcStore.Configuration configuration = new RagBenchmarkJdbcStore.Configuration(
                    dataset.resolve("manifests/dataset-manifest.json"), runManifest, documentResults,
                    runRecords, dataset.resolve("gold/qrels.tsv"), "PDF", "IR_FULL",
                    "document-ir-full-v1", "0".repeat(40), "1".repeat(64),
                    "docs/rag/evaluation-results/jdbc-format-pdf");

            RagBenchmarkJdbcStore.Result first = store.persist(connection, configuration);
            RagBenchmarkJdbcStore.Result second = store.persist(connection, configuration);

            assertEquals(200, first.documentResultCount());
            assertEquals(800, first.queryResultCount());
            assertEquals(4, first.aggregateCount());
            assertEquals(first, second);
            assertEquals(1, count(connection, "rag_benchmark_dataset"));
            assertEquals(1, count(connection, "rag_benchmark_run"));
            assertEquals(200, count(connection, "rag_benchmark_document_result"));
            assertEquals(800, count(connection, "rag_benchmark_query_result"));
            assertEquals(4, count(connection, "rag_benchmark_aggregate"));
        }
    }

    private void writeArtifacts(Path dataset, String runId, Path runManifest,
                                Path documentResults, Path runRecords) throws Exception {
        Map<String, String> queryToDocument = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(dataset.resolve("gold/gold.jsonl"),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode value = mapper.readTree(line);
                queryToDocument.put(value.path("queryId").asText(), value.path("documentId").asText());
            }
        }
        try (BufferedReader reader = Files.newBufferedReader(dataset.resolve("manifests/documents.jsonl"),
                StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(documentResults, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode value = mapper.readTree(line);
                if (!"PDF".equals(value.path("format").asText())) continue;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sourceDocumentId", value.path("sourceDocumentId").asText());
                result.put("formatDocumentId", value.path("formatDocumentId").asText());
                result.put("complexity", value.path("complexity").asText());
                result.put("relativePath", value.path("relativePath").asText());
                result.put("sourceSha256", value.path("sha256").asText());
                result.put("internalDocumentId", "internal-" + value.path("sourceDocumentId").asText());
                result.put("taskId", "task-" + value.path("sourceDocumentId").asText());
                result.put("status", "completed");
                result.put("stage", "indexed");
                result.put("processedChunks", 1);
                result.put("totalChunks", 1);
                result.put("errorCode", null);
                result.put("elapsedMs", 10);
                writer.write(mapper.writeValueAsString(result));
                writer.newLine();
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(runRecords, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> value : queryToDocument.entrySet()) {
                for (String variant : List.of("dense", "sparse", "hybrid_rrf", "hybrid_rrf_rerank")) {
                    RagBenchmarkRunIO.RunRecord record = new RagBenchmarkRunIO.RunRecord(
                            runId, variant, value.getKey(), "2".repeat(64),
                            "ret-" + variant + "-" + value.getKey(), List.of(value.getValue()), 5,
                            false, List.of(), null, Map.of("totalMs", 5L, "rerankMs", 1L),
                            Map.of("rerankCandidateCount", 1));
                    writer.write(mapper.writeValueAsString(record));
                    writer.newLine();
                }
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(runManifest.toFile(), Map.of(
                "schemaVersion", 1, "runId", runId, "status", "completed",
                "startedAt", Instant.parse("2026-07-29T00:00:00Z").toString(),
                "finishedAt", Instant.parse("2026-07-29T01:00:00Z").toString(),
                "expectedDocumentCount", 200, "completedDocumentCount", 200,
                "expectedQueryResultCount", 800, "completedQueryResultCount", 800));
    }

    private void createSchema(Connection connection) throws Exception {
        String[] statements = {
                """
                CREATE TABLE rag_benchmark_dataset(
                  dataset_id VARCHAR(120) PRIMARY KEY,dataset_name VARCHAR(255),manifest_sha256 CHAR(64) UNIQUE,
                  tree_sha256 CHAR(64),source_url VARCHAR(1000),source_revision VARCHAR(255),
                  license_code VARCHAR(255),paired_document_count INT,query_count INT,qrel_count INT,
                  manifest_snapshot CLOB,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """,
                """
                CREATE TABLE rag_benchmark_run(
                  run_id VARCHAR(120) PRIMARY KEY,dataset_id VARCHAR(120),format VARCHAR(16),
                  preprocessing_strategy VARCHAR(64),preprocessing_revision VARCHAR(128),git_commit CHAR(40),
                  config_sha256 CHAR(64),run_manifest_sha256 CHAR(64),artifact_root VARCHAR(1000),
                  status VARCHAR(32),expected_document_count INT,completed_document_count INT,
                  expected_query_result_count INT,completed_query_result_count INT,error_count INT,
                  degraded_count INT,empty_result_count INT,started_at TIMESTAMP,finished_at TIMESTAMP,
                  run_manifest CLOB,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """,
                """
                CREATE TABLE rag_benchmark_document_result(
                  run_id VARCHAR(120),source_document_id VARCHAR(120),format_document_id VARCHAR(160),
                  complexity VARCHAR(16),document_sha256 CHAR(64),task_id VARCHAR(120),status VARCHAR(32),
                  parser_name VARCHAR(128),parser_revision VARCHAR(255),quality_disposition VARCHAR(64),
                  quality_score DECIMAL(12,8),page_count INT,character_count INT,chunk_count INT,
                  error_code VARCHAR(128),stage_metrics CLOB,artifact_refs CLOB,
                  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY(run_id,format_document_id))
                """,
                """
                CREATE TABLE rag_benchmark_query_result(
                  run_id VARCHAR(120),retrieval_variant VARCHAR(64),query_id VARCHAR(120),
                  query_sha256 CHAR(64),retrieval_id VARCHAR(120),gold_document_ids CLOB,
                  ranked_document_ids CLOB,recall_at_1 DECIMAL(12,8),recall_at_5 DECIMAL(12,8),
                  recall_at_10 DECIMAL(12,8),mrr_at_10 DECIMAL(12,8),ndcg_at_10 DECIMAL(12,8),
                  map_at_10 DECIMAL(12,8),precision_at_10 DECIMAL(12,8),elapsed_ms BIGINT,
                  degraded BOOLEAN,degradation_reasons CLOB,error_code VARCHAR(128),
                  stage_timings_ms CLOB,candidate_counts CLOB,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY(run_id,retrieval_variant,query_id))
                """,
                """
                CREATE TABLE rag_benchmark_aggregate(
                  run_id VARCHAR(120),retrieval_variant VARCHAR(64),slice_type VARCHAR(32),
                  slice_value VARCHAR(128),sample_count INT,quality_metrics CLOB,latency_metrics CLOB,
                  candidate_metrics CLOB,metrics_sha256 CHAR(64),update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY(run_id,retrieval_variant,slice_type,slice_value))
                """
        };
        try (var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private int count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
