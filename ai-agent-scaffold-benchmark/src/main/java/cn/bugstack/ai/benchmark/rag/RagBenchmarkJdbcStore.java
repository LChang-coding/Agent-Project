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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 将已完成的原始评测产物幂等写入 MySQL，并在提交前复核行数。 */
public final class RagBenchmarkJdbcStore {

    private final ObjectMapper mapper;

    public RagBenchmarkJdbcStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Result persist(Connection connection, Configuration configuration) throws Exception {
        validate(configuration);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            RagFormatDatasetBuilder.Manifest dataset = mapper.readValue(
                    configuration.datasetManifest().toFile(), RagFormatDatasetBuilder.Manifest.class);
            JsonNode runManifest = mapper.readTree(configuration.runManifest().toFile());
            List<RagBenchmarkRunIO.RunRecord> records = new RagBenchmarkRunIO(mapper)
                    .readRecords(configuration.runRecords());
            String runId = uniqueRunId(records);
            Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(mapper).loadQrels(
                    configuration.qrels(), BeirDatasetLoader.Limits.defaults());
            validateRecords(records, qrels, runId);
            List<JsonNode> documentResults = readJsonLines(configuration.documentResults());
            Map<String, JsonNode> documentManifests = documentManifests(configuration.datasetManifest());
            validateDocumentResults(documentResults, documentManifests, runId, runManifest);

            upsertDataset(connection, dataset, configuration.datasetManifest());
            upsertRun(connection, configuration, dataset, runManifest, records, runId);
            int documentRows = upsertDocumentResults(connection, runId, configuration, documentResults,
                    documentManifests);
            int queryRows = upsertQueryResults(connection, runId, qrels, records);
            int aggregateRows = upsertAggregates(connection, runId, qrels, records);
            verifyCount(connection, "rag_benchmark_document_result", runId, documentResults.size());
            verifyCount(connection, "rag_benchmark_query_result", runId, records.size());
            verifyCount(connection, "rag_benchmark_aggregate", runId, aggregateRows);
            connection.commit();
            return new Result(runId, dataset.datasetName(), documentRows, queryRows, aggregateRows);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private int upsertDocumentResults(Connection connection, String runId, Configuration configuration,
                                      List<JsonNode> results, Map<String, JsonNode> manifests) throws Exception {
        String sql = """
                INSERT INTO rag_benchmark_document_result
                (run_id,source_document_id,format_document_id,complexity,document_sha256,task_id,status,
                 parser_name,parser_revision,quality_disposition,quality_score,page_count,character_count,
                 chunk_count,error_code,stage_metrics,artifact_refs)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE task_id=VALUES(task_id),status=VALUES(status),
                 parser_name=VALUES(parser_name),parser_revision=VALUES(parser_revision),
                 quality_disposition=VALUES(quality_disposition),quality_score=VALUES(quality_score),
                 page_count=VALUES(page_count),character_count=VALUES(character_count),
                 chunk_count=VALUES(chunk_count),error_code=VALUES(error_code),
                 stage_metrics=VALUES(stage_metrics),artifact_refs=VALUES(artifact_refs),
                 update_time=CURRENT_TIMESTAMP(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (JsonNode result : results) {
                JsonNode source = manifests.get(required(result, "formatDocumentId"));
                Map<String, Object> stageMetrics = new LinkedHashMap<>();
                stageMetrics.put("stage", result.path("stage").asText());
                stageMetrics.put("processedChunks", result.path("processedChunks").asInt());
                stageMetrics.put("totalChunks", result.path("totalChunks").asInt());
                stageMetrics.put("elapsedMs", result.path("elapsedMs").asLong());
                Map<String, Object> artifactRefs = Map.of(
                        "documentResultPath", configuration.artifactRoot() + "/document-results.jsonl",
                        "sourceRelativePath", required(result, "relativePath"),
                        "sourceSha256", required(result, "sourceSha256"));
                int index = 1;
                statement.setString(index++, runId);
                statement.setString(index++, required(result, "sourceDocumentId"));
                statement.setString(index++, required(result, "formatDocumentId"));
                statement.setString(index++, required(result, "complexity"));
                statement.setString(index++, required(result, "sourceSha256"));
                statement.setString(index++, optional(result, "taskId"));
                statement.setString(index++, required(result, "status"));
                statement.setString(index++, optional(result, "parserName"));
                statement.setString(index++, optional(result, "parserRevision"));
                statement.setString(index++, optional(result, "qualityDisposition"));
                if (result.path("qualityScore").isNumber()) {
                    statement.setDouble(index++, result.path("qualityScore").asDouble());
                } else {
                    statement.setObject(index++, null);
                }
                statement.setInt(index++, result.path("pageCount").asInt());
                statement.setInt(index++, source.path("canonicalTextChars").asInt());
                statement.setInt(index++, result.path("totalChunks").asInt());
                statement.setString(index++, optional(result, "errorCode"));
                statement.setString(index++, mapper.writeValueAsString(stageMetrics));
                statement.setString(index, mapper.writeValueAsString(artifactRefs));
                statement.addBatch();
            }
            return statement.executeBatch().length;
        }
    }

    private void upsertDataset(Connection connection, RagFormatDatasetBuilder.Manifest dataset,
                               Path manifestPath) throws Exception {
        String manifestHash = sha256(manifestPath);
        String existingHash = scalar(connection,
                "SELECT manifest_sha256 FROM rag_benchmark_dataset WHERE dataset_id=?",
                dataset.datasetName());
        if (existingHash != null && !existingHash.equals(manifestHash)) {
            throw new IllegalStateException("同一dataset_id已存在不同manifest，拒绝覆盖");
        }
        String sql = """
                INSERT INTO rag_benchmark_dataset
                (dataset_id,dataset_name,manifest_sha256,tree_sha256,source_url,source_revision,license_code,
                 paired_document_count,query_count,qrel_count,manifest_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE update_time=CURRENT_TIMESTAMP(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, dataset.datasetName());
            statement.setString(index++, dataset.datasetName());
            statement.setString(index++, manifestHash);
            statement.setString(index++, dataset.treeSha256());
            statement.setString(index++, dataset.sourceUrl());
            statement.setString(index++, dataset.sourceRevision());
            statement.setString(index++, dataset.license());
            statement.setInt(index++, dataset.pairedDocumentCount());
            statement.setInt(index++, dataset.queryCount());
            statement.setInt(index++, dataset.qrelCount());
            statement.setString(index, mapper.writeValueAsString(dataset));
            statement.executeUpdate();
        }
    }

    private void upsertRun(Connection connection, Configuration configuration,
                           RagFormatDatasetBuilder.Manifest dataset, JsonNode manifest,
                           List<RagBenchmarkRunIO.RunRecord> records, String runId) throws Exception {
        long errors = records.stream().filter(value -> text(value.errorCode())).count();
        long degraded = records.stream().filter(RagBenchmarkRunIO.RunRecord::degraded).count();
        long empty = records.stream().filter(value -> value.rankedDocumentIds().isEmpty()).count();
        String status = required(manifest, "status");
        Instant started = Instant.parse(required(manifest, "startedAt"));
        Instant finished = optionalInstant(manifest, "finishedAt");
        String sql = """
                INSERT INTO rag_benchmark_run
                (run_id,dataset_id,format,preprocessing_strategy,preprocessing_revision,git_commit,
                 config_sha256,run_manifest_sha256,artifact_root,status,expected_document_count,
                 completed_document_count,expected_query_result_count,completed_query_result_count,
                 error_count,degraded_count,empty_result_count,started_at,finished_at,run_manifest)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  status=VALUES(status),completed_document_count=VALUES(completed_document_count),
                  completed_query_result_count=VALUES(completed_query_result_count),
                  error_count=VALUES(error_count),degraded_count=VALUES(degraded_count),
                  empty_result_count=VALUES(empty_result_count),finished_at=VALUES(finished_at),
                  run_manifest=VALUES(run_manifest),update_time=CURRENT_TIMESTAMP(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, runId);
            statement.setString(index++, dataset.datasetName());
            statement.setString(index++, configuration.format());
            statement.setString(index++, configuration.preprocessingStrategy());
            statement.setString(index++, configuration.preprocessingRevision());
            statement.setString(index++, configuration.gitCommit());
            statement.setString(index++, configuration.configSha256());
            statement.setString(index++, sha256(configuration.runManifest()));
            statement.setString(index++, configuration.artifactRoot());
            statement.setString(index++, status);
            statement.setInt(index++, dataset.pairedDocumentCount());
            statement.setInt(index++, manifest.path("completedDocumentCount").asInt(
                    dataset.pairedDocumentCount()));
            statement.setInt(index++, records.size());
            statement.setInt(index++, records.size());
            statement.setLong(index++, errors);
            statement.setLong(index++, degraded);
            statement.setLong(index++, empty);
            statement.setTimestamp(index++, Timestamp.from(started));
            if (finished == null) statement.setTimestamp(index++, null);
            else statement.setTimestamp(index++, Timestamp.from(finished));
            statement.setString(index, mapper.writeValueAsString(manifest));
            statement.executeUpdate();
        }
    }

    private int upsertQueryResults(Connection connection, String runId,
                                   Map<String, Map<String, Integer>> qrels,
                                   List<RagBenchmarkRunIO.RunRecord> records) throws Exception {
        String sql = """
                INSERT INTO rag_benchmark_query_result
                (run_id,retrieval_variant,query_id,query_sha256,retrieval_id,gold_document_ids,
                 ranked_document_ids,recall_at_1,recall_at_5,recall_at_10,mrr_at_10,ndcg_at_10,
                 map_at_10,precision_at_10,elapsed_ms,degraded,degradation_reasons,error_code,
                 stage_timings_ms,candidate_counts)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE retrieval_id=VALUES(retrieval_id),
                 ranked_document_ids=VALUES(ranked_document_ids),recall_at_1=VALUES(recall_at_1),
                 recall_at_5=VALUES(recall_at_5),recall_at_10=VALUES(recall_at_10),
                 mrr_at_10=VALUES(mrr_at_10),ndcg_at_10=VALUES(ndcg_at_10),
                 map_at_10=VALUES(map_at_10),precision_at_10=VALUES(precision_at_10),
                 elapsed_ms=VALUES(elapsed_ms),degraded=VALUES(degraded),
                 degradation_reasons=VALUES(degradation_reasons),error_code=VALUES(error_code),
                 stage_timings_ms=VALUES(stage_timings_ms),candidate_counts=VALUES(candidate_counts),
                 update_time=CURRENT_TIMESTAMP(3)
                """;
        RagRetrievalScorer scorer = new RagRetrievalScorer();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (RagBenchmarkRunIO.RunRecord record : records) {
                Map<String, Integer> relevance = qrels.get(record.queryId());
                RagRetrievalScorer.QueryMetrics metrics = scorer.scoreQuery(
                        record.queryId(), relevance, record.rankedDocumentIds());
                List<String> gold = relevance.entrySet().stream().filter(entry -> entry.getValue() > 0)
                        .map(Map.Entry::getKey).sorted().toList();
                int index = 1;
                statement.setString(index++, runId);
                statement.setString(index++, record.variant());
                statement.setString(index++, record.queryId());
                statement.setString(index++, record.querySha256());
                statement.setString(index++, record.retrievalId());
                statement.setString(index++, mapper.writeValueAsString(gold));
                statement.setString(index++, mapper.writeValueAsString(record.rankedDocumentIds()));
                statement.setDouble(index++, metrics.recallAt1());
                statement.setDouble(index++, metrics.recallAt5());
                statement.setDouble(index++, metrics.recallAt10());
                statement.setDouble(index++, metrics.mrrAt10());
                statement.setDouble(index++, metrics.ndcgAt10());
                statement.setDouble(index++, metrics.mapAt10());
                statement.setDouble(index++, metrics.precisionAt10());
                statement.setLong(index++, record.elapsedMs());
                statement.setBoolean(index++, record.degraded());
                statement.setString(index++, mapper.writeValueAsString(record.degradationReasons()));
                statement.setString(index++, record.errorCode());
                statement.setString(index++, mapper.writeValueAsString(record.stageTimingsMs()));
                statement.setString(index, mapper.writeValueAsString(record.candidateCounts()));
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            return results.length;
        }
    }

    private int upsertAggregates(Connection connection, String runId,
                                 Map<String, Map<String, Integer>> qrels,
                                 List<RagBenchmarkRunIO.RunRecord> records) throws Exception {
        Map<String, List<RagBenchmarkRunIO.RunRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(RagBenchmarkRunIO.RunRecord::variant,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, RagBenchmarkRunStatistics.VariantStatistics> performance =
                new RagBenchmarkRunStatistics().aggregate(records);
        String sql = """
                INSERT INTO rag_benchmark_aggregate
                (run_id,retrieval_variant,slice_type,slice_value,sample_count,quality_metrics,
                 latency_metrics,candidate_metrics,metrics_sha256)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE sample_count=VALUES(sample_count),
                 quality_metrics=VALUES(quality_metrics),latency_metrics=VALUES(latency_metrics),
                 candidate_metrics=VALUES(candidate_metrics),metrics_sha256=VALUES(metrics_sha256),
                 update_time=CURRENT_TIMESTAMP(3)
                """;
        int rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, List<RagBenchmarkRunIO.RunRecord>> entry : grouped.entrySet()) {
                Map<String, List<String>> ranking = new LinkedHashMap<>();
                entry.getValue().forEach(record -> ranking.put(record.queryId(), record.rankedDocumentIds()));
                RagRetrievalScorer.AggregateMetrics quality = new RagRetrievalScorer().scoreAll(qrels, ranking);
                RagBenchmarkRunStatistics.VariantStatistics stats = performance.get(entry.getKey());
                String qualityJson = mapper.writeValueAsString(quality.summary());
                String latencyJson = mapper.writeValueAsString(Map.of(
                        "elapsedMs", stats.elapsedMs(), "stageTimingsMs", stats.stageTimingsMs(),
                        "errorCount", stats.errorCount(), "degradedCount", stats.degradedCount(),
                        "emptyResultCount", stats.emptyResultCount()));
                String candidateJson = mapper.writeValueAsString(stats.candidateCounts());
                String metricsHash = sha256(qualityJson + "\0" + latencyJson + "\0" + candidateJson);
                int index = 1;
                statement.setString(index++, runId);
                statement.setString(index++, entry.getKey());
                statement.setString(index++, "ALL");
                statement.setString(index++, "ALL");
                statement.setInt(index++, entry.getValue().size());
                statement.setString(index++, qualityJson);
                statement.setString(index++, latencyJson);
                statement.setString(index++, candidateJson);
                statement.setString(index, metricsHash);
                statement.addBatch();
                rows++;
            }
            statement.executeBatch();
        }
        return rows;
    }

    private void validateRecords(List<RagBenchmarkRunIO.RunRecord> records,
                                 Map<String, Map<String, Integer>> qrels, String runId) {
        Set<String> unique = new java.util.HashSet<>();
        for (RagBenchmarkRunIO.RunRecord record : records) {
            if (!runId.equals(record.runId()) || !qrels.containsKey(record.queryId())
                    || !unique.add(record.variant() + "\0" + record.queryId())) {
                throw new IllegalArgumentException("run记录身份、qrels引用或唯一性非法");
            }
        }
    }

    private void validateDocumentResults(List<JsonNode> results, Map<String, JsonNode> manifests,
                                         String runId, JsonNode runManifest) {
        Set<String> unique = new java.util.HashSet<>();
        int expected = runManifest.path("expectedDocumentCount").asInt();
        if (expected < 1 || results.size() != expected) {
            throw new IllegalArgumentException("逐文档结果数量与run manifest不一致");
        }
        for (JsonNode result : results) {
            String formatDocumentId = required(result, "formatDocumentId");
            JsonNode source = manifests.get(formatDocumentId);
            if (source == null || !unique.add(formatDocumentId)
                    || !required(result, "sourceDocumentId").equals(required(source, "sourceDocumentId"))
                    || !required(result, "sourceSha256").equals(required(source, "sha256"))) {
                throw new IllegalArgumentException("逐文档结果与数据集manifest身份不一致: " + runId);
            }
        }
    }

    private Map<String, JsonNode> documentManifests(Path datasetManifest) throws IOException {
        Path path = datasetManifest.getParent().resolve("documents.jsonl");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : readJsonLines(path)) {
            String id = required(value, "formatDocumentId");
            if (result.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("格式文档manifest包含重复身份");
            }
        }
        return Map.copyOf(result);
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("JSONL文件不存在: " + path);
        }
        List<JsonNode> values = new java.util.ArrayList<>();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) values.add(mapper.readTree(line));
            }
        }
        return List.copyOf(values);
    }

    private String uniqueRunId(List<RagBenchmarkRunIO.RunRecord> records) {
        Set<String> ids = records.stream().map(RagBenchmarkRunIO.RunRecord::runId)
                .filter(this::text).collect(Collectors.toSet());
        if (ids.size() != 1) throw new IllegalArgumentException("run记录必须恰好包含一个runId");
        return ids.iterator().next();
    }

    private void verifyCount(Connection connection, String table, String runId, int expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE run_id=?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != expected) {
                    throw new IllegalStateException(table + "落库行数不一致");
                }
            }
        }
    }

    private String scalar(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private Instant optionalInstant(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : Instant.parse(value);
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("run manifest缺少字段: " + field);
        return value;
    }

    private String optional(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
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

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private void validate(Configuration value) {
        if (value == null || value.datasetManifest() == null || value.runManifest() == null
                || value.documentResults() == null || value.runRecords() == null || value.qrels() == null) {
            throw new IllegalArgumentException("评测落库文件不能为空");
        }
        if (!List.of("PDF", "DOCX").contains(value.format())
                || !value.gitCommit().matches("[0-9a-f]{40}")
                || !value.configSha256().matches("[0-9a-f]{64}")
                || !value.preprocessingStrategy().matches("[A-Z_]{3,64}")
                || !text(value.preprocessingRevision()) || !text(value.artifactRoot())) {
            throw new IllegalArgumentException("评测落库身份字段非法");
        }
    }

    public record Configuration(Path datasetManifest, Path runManifest, Path documentResults,
                                Path runRecords, Path qrels,
                                String format, String preprocessingStrategy, String preprocessingRevision,
                                String gitCommit, String configSha256, String artifactRoot) {}
    public record Result(String runId, String datasetId, int documentResultCount,
                         int queryResultCount, int aggregateCount) {}
}
