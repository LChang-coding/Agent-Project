package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 对同源 PDF/DOCX 逐文件执行生产摄取与四组检索消融，并把内部文档身份还原为数据集金标身份。
 */
public final class RagFormatBenchmarkRunner {

    private final ObjectMapper mapper;
    private final RagBenchmarkHttpClient client;

    public RagFormatBenchmarkRunner(ObjectMapper mapper, RagBenchmarkHttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    public Result run(Configuration configuration) throws Exception {
        Dataset dataset = loadDataset(configuration);
        prepareEmptyDirectory(configuration.outputDirectory());
        Map<String, Object> manifest = initialManifest(configuration, dataset);
        Path manifestPath = configuration.outputDirectory().resolve("run-manifest.json");
        writeAtomic(manifestPath, manifest);
        Instant startedAt = Instant.now();
        try {
            RagBenchmarkHttpClient.KnowledgeBase knowledgeBase = client.createKnowledgeBase(
                    bounded("fmt_" + configuration.runId(), 96),
                    "Controlled " + configuration.format() + " benchmark " + configuration.runId());
            manifest.put("knowledgeBaseId", knowledgeBase.knowledgeBaseId());
            writeAtomic(manifestPath, manifest);

            IngestResult ingestion = ingest(configuration, dataset, knowledgeBase.knowledgeBaseId(), manifest,
                    manifestPath);
            Map<String, String> targets = createTargets(configuration, knowledgeBase.knowledgeBaseId());
            writeAtomic(configuration.outputDirectory().resolve("targets.json"), Map.of(
                    "schemaVersion", 1, "sourceRunId", configuration.runId(), "targets", targets));

            List<Query> queries = new ArrayList<>(dataset.queries());
            Collections.shuffle(queries, new Random(configuration.seed()));
            runWarmup(configuration, targets, queries, ingestion.internalToSource());
            executeMeasured(configuration, targets, queries, ingestion.internalToSource());

            RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(mapper);
            List<RagBenchmarkRunIO.RunRecord> records = runIO.readRecords(
                    configuration.outputDirectory().resolve("run.jsonl"));
            validateMeasured(records, dataset, targets.keySet(), configuration.runId());
            Map<String, Map<String, List<String>>> runs = runIO.read(
                    configuration.outputDirectory().resolve("run.jsonl"));
            Map<String, RagRetrievalScorer.AggregateMetrics> quality = new LinkedHashMap<>();
            RagRetrievalScorer scorer = new RagRetrievalScorer();
            targets.keySet().forEach(variant -> quality.put(variant,
                    scorer.scoreAll(dataset.qrels(), runs.getOrDefault(variant, Map.of()))));
            Map<String, RagBenchmarkRunStatistics.VariantStatistics> performance =
                    new RagBenchmarkRunStatistics().aggregate(records);
            runIO.writeReport(configuration.outputDirectory().resolve("metrics.json"), quality, Map.of(
                    "runId", configuration.runId(),
                    "format", configuration.format(),
                    "preprocessingStrategy", configuration.preprocessingStrategy(),
                    "preprocessingRevision", configuration.preprocessingRevision(),
                    "answerMetrics", "not_evaluated_no_gold_answers",
                    "percentileMethod", "nearest-rank",
                    "runStatistics", performance));

            manifest.put("status", "completed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("completedDocumentCount", ingestion.completedCount());
            manifest.put("completedQueryResultCount", records.size());
            manifest.put("errorCount", 0);
            manifest.put("degradedCount", 0);
            manifest.put("emptyResultCount", 0);
            manifest.put("durationMs", Duration.between(startedAt, Instant.now()).toMillis());
            manifest.put("runRecordsSha256", sha256(configuration.outputDirectory().resolve("run.jsonl")));
            manifest.put("documentResultsSha256",
                    sha256(configuration.outputDirectory().resolve("document-results.jsonl")));
            writeAtomic(manifestPath, manifest);
            return new Result(configuration.runId(), knowledgeBase.knowledgeBaseId(), ingestion.completedCount(),
                    records.size(), quality, performance);
        } catch (Exception error) {
            manifest.put("status", "failed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("errorType", error.getClass().getSimpleName());
            manifest.put("errorCode", errorCode(error));
            writeAtomic(manifestPath, manifest);
            throw error;
        }
    }

    private IngestResult ingest(Configuration configuration, Dataset dataset, String knowledgeBaseId,
                                Map<String, Object> runManifest, Path runManifestPath) throws Exception {
        Map<String, String> internalToSource = new LinkedHashMap<>();
        int completed = 0;
        Path results = configuration.outputDirectory().resolve("document-results.jsonl");
        Path mapPath = configuration.outputDirectory().resolve("document-map.jsonl");
        for (FormatDocument source : dataset.documents()) {
            long started = System.nanoTime();
            RagBenchmarkHttpClient.Upload upload = null;
            RagBenchmarkHttpClient.IngestTask task = null;
            try {
                upload = client.uploadDocument(knowledgeBaseId, source.path());
                task = waitForTask(upload.taskId(), configuration);
                RagBenchmarkHttpClient.Document document = client.getDocument(knowledgeBaseId, upload.documentId());
                if (!document.ready()) {
                    throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                            "RAG_BENCHMARK_DOCUMENT_NOT_READY", "摄取成功但文档未激活");
                }
                if (internalToSource.putIfAbsent(document.documentId(), source.sourceDocumentId()) != null) {
                    throw new IllegalStateException("不同源文档映射到同一内部documentId");
                }
                appendJson(mapPath, Map.of(
                        "sourceDocumentId", source.sourceDocumentId(),
                        "formatDocumentId", source.formatDocumentId(),
                        "internalDocumentId", document.documentId(),
                        "taskId", upload.taskId(),
                        "activeVersionId", document.activeVersionId(),
                        "activeGeneration", document.activeGeneration()));
                appendJson(results, new DocumentResult(source.sourceDocumentId(), source.formatDocumentId(),
                        source.complexity(), source.relativePath(), source.sha256(), document.documentId(),
                        upload.taskId(), task.status(), task.stage(), task.processedChunks(), task.totalChunks(),
                        null, elapsedMs(started)));
                completed++;
                runManifest.put("completedDocumentCount", completed);
                writeAtomic(runManifestPath, runManifest);
            } catch (Exception error) {
                appendJson(results, new DocumentResult(source.sourceDocumentId(), source.formatDocumentId(),
                        source.complexity(), source.relativePath(), source.sha256(),
                        upload == null ? null : upload.documentId(), upload == null ? null : upload.taskId(),
                        task == null ? "failed" : task.status(), task == null ? "upload_or_poll" : task.stage(),
                        task == null ? 0 : task.processedChunks(), task == null ? 0 : task.totalChunks(),
                        errorCode(error), elapsedMs(started)));
                throw error;
            }
        }
        if (completed != dataset.documents().size() || internalToSource.size() != dataset.documents().size()) {
            throw new IllegalStateException("格式文档摄取闭环数量不一致");
        }
        return new IngestResult(Map.copyOf(internalToSource), completed);
    }

    private RagBenchmarkHttpClient.IngestTask waitForTask(String taskId, Configuration configuration)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + configuration.ingestTimeout().toNanos();
        RagBenchmarkHttpClient.IngestTask latest = null;
        while (System.nanoTime() < deadline) {
            latest = client.getTask(taskId);
            if (latest.completed()) return latest;
            if (latest.terminalFailure()) {
                throw new RagBenchmarkHttpClient.BenchmarkProtocolException("RAG_BENCHMARK_INGEST_FAILED",
                        "格式文档摄取失败: " + latest.status() + "/" + latest.stage() + "/"
                                + String.valueOf(latest.errorCode()));
            }
            Thread.sleep(configuration.pollInterval().toMillis());
        }
        throw new RagBenchmarkHttpClient.BenchmarkProtocolException("RAG_BENCHMARK_INGEST_TIMEOUT",
                "格式文档摄取超时，最后状态=" + (latest == null ? "unknown" : latest.status()));
    }

    private Map<String, String> createTargets(Configuration configuration, String knowledgeBaseId)
            throws IOException, InterruptedException {
        Map<String, String> targets = new LinkedHashMap<>();
        List<RagBenchmarkHttpClient.Profile> profiles = new ArrayList<>();
        List<RagBenchmarkHttpClient.Binding> bindings = new ArrayList<>();
        for (RagBenchmarkHttpClient.ProfileDefinition definition
                : RagBenchmarkHttpClient.ProfileDefinition.ablations()) {
            RagBenchmarkHttpClient.Profile profile = client.createProfile(
                    bounded("fmt_" + configuration.runId() + "_" + definition.variant(), 96), definition);
            String targetId = bounded("fmt_" + configuration.runId() + "_" + definition.variant(), 120);
            RagBenchmarkHttpClient.Binding binding = client.createBinding(targetId, knowledgeBaseId,
                    profile.profileId());
            targets.put(definition.variant(), targetId);
            profiles.add(profile);
            bindings.add(binding);
        }
        writeAtomic(configuration.outputDirectory().resolve("profiles.json"), profiles);
        writeAtomic(configuration.outputDirectory().resolve("bindings.json"), bindings);
        return Collections.unmodifiableMap(targets);
    }

    private void runWarmup(Configuration configuration, Map<String, String> targets, List<Query> queries,
                           Map<String, String> internalToSource) throws Exception {
        int count = Math.min(configuration.warmupQueries(), queries.size());
        Path path = configuration.outputDirectory().resolve("warmup.jsonl");
        RagBenchmarkRunIO io = new RagBenchmarkRunIO(mapper);
        for (int index = 0; index < count; index++) {
            Query query = queries.get(index);
            for (Map.Entry<String, String> target : targets.entrySet()) {
                io.append(path, execute(configuration.runId(), target.getKey(), target.getValue(), query,
                        internalToSource));
            }
        }
        if (count > 0) new RagBenchmarkWarmupGate().validate(io.readRecords(path), count, targets.keySet());
    }

    private void executeMeasured(Configuration configuration, Map<String, String> targets, List<Query> queries,
                                 Map<String, String> internalToSource) throws Exception {
        Path path = configuration.outputDirectory().resolve("run.jsonl");
        RagBenchmarkRunIO io = new RagBenchmarkRunIO(mapper);
        List<String> variants = new ArrayList<>(targets.keySet());
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            Query query = queries.get(queryIndex);
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((queryIndex + offset) % variants.size());
                RagBenchmarkRunIO.RunRecord record = execute(configuration.runId(), variant,
                        targets.get(variant), query, internalToSource);
                new RagBenchmarkMeasuredGate().validate(record);
                io.append(path, record);
            }
        }
    }

    private RagBenchmarkRunIO.RunRecord execute(String runId, String variant, String targetId, Query query,
                                                 Map<String, String> internalToSource)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            RagBenchmarkHttpClient.DebugResult result = client.debug(targetId, query.text());
            List<String> sourceIds = new ArrayList<>();
            for (String returnedId : result.rankedDocumentIds()) {
                String sourceId = internalToSource.get(returnedId);
                if (sourceId == null && internalToSource.containsValue(returnedId)) sourceId = returnedId;
                if (sourceId == null) {
                    throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                            "RAG_BENCHMARK_UNKNOWN_DOCUMENT", "检索返回未摄取的documentId");
                }
                if (!sourceIds.contains(sourceId)) sourceIds.add(sourceId);
            }
            return new RagBenchmarkRunIO.RunRecord(runId, variant, query.queryId(), sha256(query.text()),
                    result.retrievalId(), sourceIds, elapsedMs(started), result.degraded(),
                    result.degradationReasons(), null, result.timingsMs(), result.candidateCounts());
        } catch (RagBenchmarkHttpClient.BenchmarkApiException error) {
            return errorRecord(runId, variant, query, started, error.code());
        } catch (RagBenchmarkHttpClient.BenchmarkProtocolException error) {
            return errorRecord(runId, variant, query, started, error.code());
        }
    }

    private RagBenchmarkRunIO.RunRecord errorRecord(String runId, String variant, Query query,
                                                     long started, String errorCode) {
        return new RagBenchmarkRunIO.RunRecord(runId, variant, query.queryId(), sha256(query.text()),
                null, List.of(), elapsedMs(started), false, List.of(), errorCode, Map.of(), Map.of());
    }

    private void validateMeasured(List<RagBenchmarkRunIO.RunRecord> records, Dataset dataset,
                                  Set<String> variants, String runId) {
        int expected = dataset.queries().size() * variants.size();
        if (records.size() != expected) throw new IllegalStateException("格式评测记录数不等于200×4");
        Set<String> unique = new LinkedHashSet<>();
        for (RagBenchmarkRunIO.RunRecord record : records) {
            if (!runId.equals(record.runId()) || !variants.contains(record.variant())
                    || !dataset.queryIds().contains(record.queryId())
                    || !unique.add(record.variant() + "\0" + record.queryId())
                    || record.errorCode() != null || record.degraded() || record.rankedDocumentIds().isEmpty()
                    || record.rankedDocumentIds().stream().anyMatch(id -> !dataset.sourceDocumentIds().contains(id))) {
                throw new IllegalStateException("格式评测记录门禁失败");
            }
        }
    }

    private Dataset loadDataset(Configuration configuration) throws IOException {
        RagFormatDatasetValidator.Report report = new RagFormatDatasetValidator(mapper)
                .validate(configuration.datasetDirectory());
        if (!report.valid()) throw new IllegalArgumentException("格式评测数据集校验失败: " + report.failures());
        Path manifestPath = configuration.datasetDirectory().resolve("manifests/dataset-manifest.json");
        RagFormatDatasetBuilder.Manifest manifest = mapper.readValue(
                manifestPath.toFile(), RagFormatDatasetBuilder.Manifest.class);
        List<FormatDocument> documents = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                configuration.datasetDirectory().resolve(manifest.documentManifestPath()),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode value = mapper.readTree(line);
                if (!configuration.format().equals(value.path("format").asText())) continue;
                Path path = configuration.datasetDirectory().resolve(value.path("relativePath").asText()).normalize();
                if (!path.startsWith(configuration.datasetDirectory().normalize())) {
                    throw new IllegalArgumentException("文档路径逃逸数据集目录");
                }
                documents.add(new FormatDocument(value.path("formatDocumentId").asText(),
                        value.path("sourceDocumentId").asText(), value.path("complexity").asText(),
                        value.path("relativePath").asText(), value.path("sha256").asText(), path));
            }
        }
        documents.sort(Comparator.comparing(FormatDocument::sourceDocumentId));
        if (documents.size() != 200) throw new IllegalArgumentException("每种格式必须恰好200份文档");
        List<Query> queries = readQueries(configuration.datasetDirectory().resolve(manifest.queriesPath()));
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(mapper).loadQrels(
                configuration.datasetDirectory().resolve(manifest.qrelsPath()), BeirDatasetLoader.Limits.defaults());
        Set<String> queryIds = queries.stream().map(Query::queryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> documentIds = documents.stream().map(FormatDocument::sourceDocumentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (queries.size() != 200 || qrels.size() != 200 || !qrels.keySet().equals(queryIds)
                || qrels.values().stream().flatMap(value -> value.keySet().stream())
                .anyMatch(id -> !documentIds.contains(id))) {
            throw new IllegalArgumentException("格式评测问题、qrels和文档身份不闭合");
        }
        return new Dataset(manifestPath, manifest, List.copyOf(documents), queries, qrels, queryIds, documentIds);
    }

    private List<Query> readQueries(Path path) throws IOException {
        List<Query> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode value = mapper.readTree(line);
                String id = value.path("_id").asText();
                String text = value.path("text").asText();
                if (id.isBlank() || text.isBlank()) throw new IllegalArgumentException("格式评测问题记录非法");
                result.add(new Query(id, text));
            }
        }
        result.sort(Comparator.comparing(Query::queryId));
        return List.copyOf(result);
    }

    private Map<String, Object> initialManifest(Configuration configuration, Dataset dataset) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1);
        values.put("runId", configuration.runId());
        values.put("status", "running");
        values.put("startedAt", Instant.now().toString());
        values.put("datasetName", dataset.manifest().datasetName());
        values.put("datasetManifestSha256", sha256(dataset.manifestPath()));
        values.put("datasetTreeSha256", dataset.manifest().treeSha256());
        values.put("format", configuration.format());
        values.put("preprocessingStrategy", configuration.preprocessingStrategy());
        values.put("preprocessingRevision", configuration.preprocessingRevision());
        values.put("codeRevision", configuration.codeRevision());
        values.put("configSha256", configuration.configSha256());
        values.put("seed", configuration.seed());
        values.put("expectedDocumentCount", dataset.documents().size());
        values.put("completedDocumentCount", 0);
        values.put("expectedQueryResultCount", dataset.queries().size() * 4);
        values.put("completedQueryResultCount", 0);
        values.put("credentialSource", configuration.credentialSource());
        return values;
    }

    private void prepareEmptyDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) throw new IllegalArgumentException("禁止覆盖既有格式评测输出目录");
        Files.createDirectories(directory);
    }

    private void appendJson(Path path, Object value) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            writer.write(mapper.writeValueAsString(value));
            writer.newLine();
        }
    }

    private void writeAtomic(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private String errorCode(Exception error) {
        if (error instanceof RagBenchmarkHttpClient.BenchmarkApiException api) return api.code();
        if (error instanceof RagBenchmarkHttpClient.BenchmarkProtocolException protocol) return protocol.code();
        if (error instanceof HttpTimeoutException) return RagBenchmarkRunner.REQUEST_TIMEOUT_ERROR_CODE;
        if (error instanceof IOException) return RagBenchmarkRunner.IO_ERROR_CODE;
        return "RAG_BENCHMARK_FORMAT_RUN_FAILED";
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
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
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }

    private record Query(String queryId, String text) {}
    private record FormatDocument(String formatDocumentId, String sourceDocumentId, String complexity,
                                  String relativePath, String sha256, Path path) {}
    private record Dataset(Path manifestPath, RagFormatDatasetBuilder.Manifest manifest,
                           List<FormatDocument> documents, List<Query> queries,
                           Map<String, Map<String, Integer>> qrels, Set<String> queryIds,
                           Set<String> sourceDocumentIds) {}
    private record IngestResult(Map<String, String> internalToSource, int completedCount) {}

    public record DocumentResult(String sourceDocumentId, String formatDocumentId, String complexity,
                                 String relativePath, String sourceSha256, String internalDocumentId,
                                 String taskId, String status, String stage, int processedChunks,
                                 int totalChunks, String errorCode, long elapsedMs) {}

    public record Configuration(String runId, java.net.URI baseUrl, String credentialSource,
                                String codeRevision, Path datasetDirectory, String format,
                                String preprocessingStrategy, String preprocessingRevision,
                                String configSha256, Path outputDirectory, long seed, int warmupQueries,
                                Duration pollInterval, Duration ingestTimeout) {
        public Configuration {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,64}") || baseUrl == null
                    || credentialSource == null || credentialSource.isBlank()
                    || codeRevision == null || !codeRevision.matches("[0-9a-f]{40}")
                    || datasetDirectory == null || !Set.of("PDF", "DOCX").contains(format)
                    || preprocessingStrategy == null || preprocessingStrategy.isBlank()
                    || preprocessingRevision == null || preprocessingRevision.isBlank()
                    || configSha256 == null || !configSha256.matches("[0-9a-f]{64}")
                    || outputDirectory == null || warmupQueries < 0
                    || pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
                    || ingestTimeout == null || ingestTimeout.isZero() || ingestTimeout.isNegative()) {
                throw new IllegalArgumentException("格式评测运行配置非法");
            }
        }
    }

    public record Result(String runId, String knowledgeBaseId, int completedDocumentCount,
                         int completedQueryResultCount,
                         Map<String, RagRetrievalScorer.AggregateMetrics> quality,
                         Map<String, RagBenchmarkRunStatistics.VariantStatistics> performance) {}
}
