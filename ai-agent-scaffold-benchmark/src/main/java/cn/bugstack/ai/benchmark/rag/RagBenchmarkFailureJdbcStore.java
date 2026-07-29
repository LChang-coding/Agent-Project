package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将失败分类与内部阶段因果证据幂等写入正式评测失败表。 */
public final class RagBenchmarkFailureJdbcStore {

    private final ObjectMapper mapper;

    public RagBenchmarkFailureJdbcStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Result persist(Connection connection, Configuration configuration) throws Exception {
        configuration.validate();
        JsonNode failure = mapper.readTree(configuration.failureReport().toFile());
        JsonNode internal = mapper.readTree(configuration.internalAnalysis().toFile());
        validateEvidence(failure, internal, configuration);
        List<FailureRow> rows = rows(failure, internal, configuration);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            requireCompletedRun(connection, configuration.runId());
            upsert(connection, rows);
            verifyCount(connection, configuration.runId(), rows.size());
            connection.commit();
            return new Result(configuration.runId(), rows.size(), sha256(configuration.failureReport()),
                    sha256(configuration.internalAnalysis()));
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void validateEvidence(JsonNode failure, JsonNode internal, Configuration configuration)
            throws IOException {
        JsonNode manifest = internal.path("manifest");
        if (!manifest.path("integrityHealthy").asBoolean(false)
                || manifest.path("exactFinalRankingMatches").asInt()
                != manifest.path("expectedFinalRankingComparisons").asInt()
                || !sha256(configuration.failureReport()).equals(
                manifest.path("inputSha256").path("failureReport").asText())) {
            throw new IllegalArgumentException("内部失败证据完整性或失败报告hash不匹配");
        }
        if (!failure.path("cases").isObject() || !internal.path("queries").isArray()) {
            throw new IllegalArgumentException("失败证据结构非法");
        }
    }

    private List<FailureRow> rows(JsonNode failure, JsonNode internal, Configuration configuration)
            throws Exception {
        Map<String, JsonNode> internalQueries = new LinkedHashMap<>();
        for (JsonNode query : internal.path("queries")) {
            String queryId = query.path("queryId").asText();
            if (queryId.isBlank() || internalQueries.putIfAbsent(queryId, query) != null) {
                throw new IllegalArgumentException("内部失败分析queryId非法或重复");
            }
        }
        List<FailureRow> rows = new ArrayList<>();
        var categories = failure.path("cases").fields();
        while (categories.hasNext()) {
            Map.Entry<String, JsonNode> category = categories.next();
            for (JsonNode value : category.getValue()) {
                String queryId = required(value, "queryId");
                JsonNode internalQuery = internalQueries.get(queryId);
                if (internalQuery == null) throw new IllegalArgumentException("失败案例缺少内部阶段证据: " + queryId);
                String variant = failureVariant(category.getKey());
                JsonNode analysis = internalQuery.path("variants").path(variant);
                if (analysis.isMissingNode() || !analysis.path("finalRankingMatchesBaseline").asBoolean(false)) {
                    throw new IllegalArgumentException("失败案例内部变体证据不完整: " + queryId + "/" + variant);
                }
                String sourceDocumentId = value.path("goldDocuments").path(0).path("documentId").asText(null);
                String causal = required(value, "inference") + "\n内部首个可观测损失="
                        + mapper.writeValueAsString(analysis.path("firstObservedTotalLoss"));
                Map<String, Object> directFacts = new LinkedHashMap<>();
                directFacts.put("facts", mapper.convertValue(value.path("directFacts"), List.class));
                directFacts.put("variant", variant);
                directFacts.put("retrievalId", analysis.path("retrievalId").asText());
                directFacts.put("firstObservedCoverageLoss", analysis.path("firstObservedCoverageLoss"));
                directFacts.put("competingDocuments", analysis.path("competingDocuments"));
                String failureId = sha256(configuration.runId() + "\0" + category.getKey() + "\0" + queryId);
                rows.add(new FailureRow(failureId, configuration.runId(), queryId, sourceDocumentId,
                        variant, required(value, "firstObservableFailure"), category.getKey(),
                        mapper.writeValueAsString(directFacts), causal,
                        value.path("alternativeExplanation").asText(null),
                        configuration.artifactRoot() + "/internal-failure-analysis.json#queryId=" + queryId,
                        sha256(configuration.internalAnalysis())));
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("失败报告没有可落库案例");
        return List.copyOf(rows);
    }

    private void upsert(Connection connection, List<FailureRow> rows) throws Exception {
        String sql = """
                INSERT INTO rag_benchmark_failure_case
                (failure_id,run_id,query_id,source_document_id,retrieval_variant,first_failure_stage,
                 failure_category,direct_facts,causal_analysis,alternative_explanation,
                 evidence_relative_path,evidence_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE direct_facts=VALUES(direct_facts),
                 causal_analysis=VALUES(causal_analysis),alternative_explanation=VALUES(alternative_explanation),
                 evidence_relative_path=VALUES(evidence_relative_path),evidence_sha256=VALUES(evidence_sha256),
                 update_time=CURRENT_TIMESTAMP(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (FailureRow row : rows) {
                int index = 1;
                statement.setString(index++, row.failureId());
                statement.setString(index++, row.runId());
                statement.setString(index++, row.queryId());
                statement.setString(index++, row.sourceDocumentId());
                statement.setString(index++, row.retrievalVariant());
                statement.setString(index++, row.firstFailureStage());
                statement.setString(index++, row.failureCategory());
                statement.setString(index++, row.directFacts());
                statement.setString(index++, row.causalAnalysis());
                statement.setString(index++, row.alternativeExplanation());
                statement.setString(index++, row.evidenceRelativePath());
                statement.setString(index, row.evidenceSha256());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void requireCompletedRun(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM rag_benchmark_run WHERE run_id=?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"completed".equalsIgnoreCase(result.getString(1))) {
                    throw new IllegalStateException("失败案例只能关联已完成的评测run");
                }
            }
        }
    }

    private void verifyCount(Connection connection, String runId, int expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM rag_benchmark_failure_case WHERE run_id=?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != expected) throw new IllegalStateException("失败案例落库行数不一致");
            }
        }
    }

    private String failureVariant(String category) {
        return switch (category) {
            case "dense_miss_hybrid_hit", "sparse_only_success", "persistent_miss" -> "dense";
            case "sparse_miss_hybrid_hit", "dense_only_success" -> "sparse";
            case "rerank_rescue", "rerank_reorder_gain" -> "hybrid_rrf";
            default -> "hybrid_rrf_rerank";
        };
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("失败案例字段缺失: " + field);
        return value;
    }

    private String sha256(Path path) throws IOException {
        return HexFormat.of().formatHex(digest(Files.readAllBytes(path)));
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Configuration(String runId, Path failureReport, Path internalAnalysis, String artifactRoot) {
        void validate() {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,120}")
                    || failureReport == null || !Files.isRegularFile(failureReport)
                    || internalAnalysis == null || !Files.isRegularFile(internalAnalysis)
                    || artifactRoot == null || artifactRoot.isBlank() || artifactRoot.length() > 800) {
                throw new IllegalArgumentException("失败案例落库配置非法");
            }
        }
    }

    public record Result(String runId, int failureCount, String failureReportSha256,
                         String internalAnalysisSha256) {}
    private record FailureRow(String failureId, String runId, String queryId, String sourceDocumentId,
                              String retrievalVariant, String firstFailureStage, String failureCategory,
                              String directFacts, String causalAnalysis, String alternativeExplanation,
                              String evidenceRelativePath, String evidenceSha256) {}
}
