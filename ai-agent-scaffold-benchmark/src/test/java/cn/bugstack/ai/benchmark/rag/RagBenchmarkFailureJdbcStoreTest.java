package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagBenchmarkFailureJdbcStoreTest {

    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPersistVerifiedFailureEvidenceIdempotently() throws Exception {
        Path failure = failureReport();
        Path internal = internalAnalysis(failure, true);
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:failure-store;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            schema(connection);
            RagBenchmarkFailureJdbcStore store = new RagBenchmarkFailureJdbcStore(mapper);
            RagBenchmarkFailureJdbcStore.Configuration configuration =
                    new RagBenchmarkFailureJdbcStore.Configuration("run-1", failure, internal,
                            "docs/rag/evaluation-results/run-1");

            assertEquals(1, store.persist(connection, configuration).failureCount());
            assertEquals(1, store.persist(connection, configuration).failureCount());
            try (var result = connection.createStatement().executeQuery(
                    "SELECT COUNT(*),MIN(query_id),MIN(evidence_sha256) "
                            + "FROM rag_benchmark_failure_case WHERE run_id='run-1'")) {
                result.next();
                assertEquals(1, result.getInt(1));
                assertEquals("q1", result.getString(2));
                assertEquals(sha256(internal), result.getString(3));
            }
        }
    }

    @Test
    void shouldRejectUnhealthyInternalEvidenceBeforeDatabaseWrite() throws Exception {
        Path failure = failureReport();
        Path internal = internalAnalysis(failure, false);
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:failure-store-reject;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            schema(connection);
            RagBenchmarkFailureJdbcStore store = new RagBenchmarkFailureJdbcStore(mapper);
            assertThrows(IllegalArgumentException.class, () -> store.persist(connection,
                    new RagBenchmarkFailureJdbcStore.Configuration("run-1", failure, internal, "artifacts/run-1")));
            try (var result = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM rag_benchmark_failure_case")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private Path failureReport() throws Exception {
        Path path = temp.resolve("failure.json");
        Map<String, Object> variant = Map.of("final", true);
        Map<String, Object> failureCase = Map.of(
                "queryId", "q1",
                "goldDocuments", List.of(Map.of("documentId", "doc-gold")),
                "firstObservableFailure", "dense_final_top10",
                "directFacts", List.of("Dense gold首名次=Top10未命中"),
                "inference", "推断：Dense候选被近邻竞争挤出。",
                "alternativeExplanation", "其他可能解释：标注不完整。",
                "variants", Map.of("dense", variant));
        mapper.writeValue(path.toFile(), Map.of(
                "manifest", Map.of(),
                "cases", Map.of("persistent_miss", List.of(failureCase))));
        return path;
    }

    private Path internalAnalysis(Path failure, boolean healthy) throws Exception {
        Path path = temp.resolve("internal-" + healthy + ".json");
        Map<String, Object> dense = Map.of(
                "finalRankingMatchesBaseline", true,
                "retrievalId", "ret-1",
                "firstObservedCoverageLoss", Map.of("stage", "dense_raw", "code", "DENSE_RAW_TOPK_MISS"),
                "firstObservedTotalLoss", Map.of("stage", "dense_raw", "code", "DENSE_RAW_TOPK_MISS"),
                "competingDocuments", List.of(Map.of("documentId", "doc-wrong", "rank", 1)));
        mapper.writeValue(path.toFile(), Map.of(
                "manifest", Map.of(
                        "integrityHealthy", healthy,
                        "exactFinalRankingMatches", 4,
                        "expectedFinalRankingComparisons", 4,
                        "inputSha256", Map.of("failureReport", sha256(failure))),
                "queries", List.of(Map.of("queryId", "q1", "variants", Map.of("dense", dense)))));
        return path;
    }

    private void schema(java.sql.Connection connection) throws Exception {
        connection.createStatement().execute("""
                CREATE TABLE rag_benchmark_run (
                  run_id VARCHAR(120) PRIMARY KEY,
                  status VARCHAR(32) NOT NULL
                )
                """);
        connection.createStatement().execute("INSERT INTO rag_benchmark_run VALUES ('run-1','completed')");
        connection.createStatement().execute("""
                CREATE TABLE rag_benchmark_failure_case (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  failure_id VARCHAR(160) NOT NULL UNIQUE,
                  run_id VARCHAR(120) NOT NULL,
                  query_id VARCHAR(120),
                  source_document_id VARCHAR(120),
                  retrieval_variant VARCHAR(64),
                  first_failure_stage VARCHAR(64) NOT NULL,
                  failure_category VARCHAR(128) NOT NULL,
                  direct_facts VARCHAR(100000) NOT NULL,
                  causal_analysis VARCHAR(100000) NOT NULL,
                  alternative_explanation VARCHAR(100000),
                  evidence_relative_path VARCHAR(1000) NOT NULL,
                  evidence_sha256 CHAR(64) NOT NULL,
                  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3)
                )
                """);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
