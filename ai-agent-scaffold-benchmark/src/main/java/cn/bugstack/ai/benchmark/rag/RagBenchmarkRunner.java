package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** 通过生产 HTTP API 执行单知识库、四消融组的黑盒检索评测。 */
public final class RagBenchmarkRunner {

    static final String REQUEST_TIMEOUT_ERROR_CODE = "RAG_BENCHMARK_REQUEST_TIMEOUT";
    static final String IO_ERROR_CODE = "RAG_BENCHMARK_IO";

    private final ObjectMapper objectMapper;
    private final RagBenchmarkHttpClient client;

    public RagBenchmarkRunner(ObjectMapper objectMapper, RagBenchmarkHttpClient client) {
        this.objectMapper = objectMapper;
        this.client = client;
    }

    public Result run(Configuration configuration) throws IOException, InterruptedException {
        PreparedDataset prepared = validatePreparedDataset(configuration.preparedDirectory());
        prepareEmptyDirectory(configuration.runDirectory());
        Instant startedAt = Instant.now();
        Map<String, Object> manifest = initialManifest(configuration, prepared, startedAt);
        writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
        try {
            RagBenchmarkHttpClient.KnowledgeBase knowledgeBase = client.createKnowledgeBase(
                    bounded("bench_" + configuration.runId(), 96), "RAG benchmark " + configuration.runId());
            RagBenchmarkHttpClient.Upload upload = client.uploadMarkdown(knowledgeBase.knowledgeBaseId(),
                    prepared.markdownFile());
            writeAtomic(configuration.runDirectory().resolve("upload.json"), upload);
            RagBenchmarkHttpClient.IngestTask task = waitForTask(upload.taskId(), configuration);
            writeAtomic(configuration.runDirectory().resolve("ingest-task.json"), task);
            RagBenchmarkHttpClient.Document document = client.getDocument(knowledgeBase.knowledgeBaseId(),
                    upload.documentId());
            if (!document.ready()) throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                    "RAG_BENCHMARK_DOCUMENT_NOT_READY", "摄取任务完成但文档未就绪");
            writeAtomic(configuration.runDirectory().resolve("document.json"), document);

            Map<String, String> targets = new LinkedHashMap<>();
            List<RagBenchmarkHttpClient.Profile> profiles = new ArrayList<>();
            List<RagBenchmarkHttpClient.Binding> bindings = new ArrayList<>();
            for (RagBenchmarkHttpClient.ProfileDefinition definition
                    : RagBenchmarkHttpClient.ProfileDefinition.ablations()) {
                RagBenchmarkHttpClient.Profile profile = client.createProfile(
                        bounded("bench_" + configuration.runId() + "_" + definition.variant(), 96), definition);
                String targetId = bounded("bench_" + configuration.runId() + "_" + definition.variant(), 120);
                RagBenchmarkHttpClient.Binding binding = client.createBinding(targetId,
                        knowledgeBase.knowledgeBaseId(), profile.profileId());
                targets.put(definition.variant(), targetId);
                profiles.add(profile);
                bindings.add(binding);
            }
            writeAtomic(configuration.runDirectory().resolve("profiles.json"), profiles);
            writeAtomic(configuration.runDirectory().resolve("bindings.json"), bindings);
            writeAtomic(configuration.runDirectory().resolve("targets.json"), Map.of(
                    "schemaVersion", 1,
                    "sourceRunId", configuration.runId(),
                    "targets", targets));

            List<Query> queries = readQueries(prepared.queriesFile());
            List<Query> shuffled = new ArrayList<>(queries);
            Collections.shuffle(shuffled, new Random(configuration.seed()));
            runWarmup(configuration.runId(), configuration.runDirectory(), configuration.warmupQueries(),
                    targets, shuffled);
            validateWarmup(configuration.runDirectory(), configuration.warmupQueries(), shuffled.size(),
                    targets.keySet());
            executeMeasured(configuration.runId(), configuration.runDirectory(), targets, shuffled, 0);

            Map<String, Map<String, List<String>>> runs = new RagBenchmarkRunIO(objectMapper)
                    .read(configuration.runDirectory().resolve("run.jsonl"));
            Map<String, RagBenchmarkRunStatistics.VariantStatistics> statistics =
                    new RagBenchmarkRunStatistics().aggregate(new RagBenchmarkRunIO(objectMapper)
                            .readRecords(configuration.runDirectory().resolve("run.jsonl")));
            Map<String, RagRetrievalScorer.AggregateMetrics> metrics = new LinkedHashMap<>();
            RagRetrievalScorer scorer = new RagRetrievalScorer();
            targets.keySet().forEach(variant -> metrics.put(variant,
                    scorer.scoreAll(prepared.qrels(), runs.getOrDefault(variant, Map.of()))));
            new RagBenchmarkRunIO(objectMapper).writeReport(configuration.runDirectory().resolve("metrics.json"),
                    metrics, Map.of("runId", configuration.runId(), "answerMetrics",
                            "not_evaluated_no_gold_answers", "percentileMethod", "nearest-rank",
                            "runStatistics", statistics));
            manifest.put("status", "completed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("knowledgeBaseId", knowledgeBase.knowledgeBaseId());
            manifest.put("taskId", task.taskId());
            manifest.put("queryCount", queries.size());
            writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
            return new Result(configuration.runId(), knowledgeBase.knowledgeBaseId(), task.taskId(), metrics,
                    statistics);
        } catch (RuntimeException | IOException | InterruptedException exception) {
            manifest.put("status", "failed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("errorType", exception.getClass().getSimpleName());
            putManifestErrorCode(manifest, exception);
            writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
            throw exception;
        }
    }

    /** 复用已经完成摄取和绑定的 targets，只重新执行查询与评分。 */
    public Result evaluate(EvaluationConfiguration configuration) throws IOException, InterruptedException {
        PreparedDataset prepared = validatePreparedDataset(configuration.preparedDirectory());
        Map<String, String> targets = readTargets(configuration.targetsFile());
        prepareEmptyDirectory(configuration.runDirectory());
        Configuration manifestConfiguration = new Configuration(configuration.runId(), configuration.baseUrl(),
                configuration.credentialSource(), configuration.codeRevision(), configuration.preparedDirectory(),
                configuration.runDirectory(), configuration.seed(), configuration.warmupQueries(),
                Duration.ofMillis(1), Duration.ofSeconds(1), configuration.requestTimeoutSeconds());
        Map<String, Object> manifest = initialManifest(manifestConfiguration, prepared, Instant.now());
        manifest.put("mode", "evaluate_existing_targets");
        manifest.put("targetsSha256", sha256(configuration.targetsFile()));
        manifest.put("sourceTargetsFile", configuration.targetsFile().getFileName().toString());
        writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
        writeAtomic(configuration.runDirectory().resolve("targets.json"), Map.of(
                "schemaVersion", 1, "sourceSha256", sha256(configuration.targetsFile()), "targets", targets));
        try {
            List<Query> queries = readQueries(prepared.queriesFile());
            List<Query> shuffled = new ArrayList<>(queries);
            Collections.shuffle(shuffled, new Random(configuration.seed()));
            int resumedRecordCount = prepareEvaluationRun(configuration, prepared, targets, shuffled, manifest);
            executeMeasured(configuration.runId(), configuration.runDirectory(), targets, shuffled,
                    resumedRecordCount);
            RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(objectMapper);
            Map<String, Map<String, List<String>>> runs = runIO.read(
                    configuration.runDirectory().resolve("run.jsonl"));
            Map<String, RagBenchmarkRunStatistics.VariantStatistics> statistics =
                    new RagBenchmarkRunStatistics().aggregate(runIO.readRecords(
                            configuration.runDirectory().resolve("run.jsonl")));
            Map<String, RagRetrievalScorer.AggregateMetrics> metrics = new LinkedHashMap<>();
            RagRetrievalScorer scorer = new RagRetrievalScorer();
            targets.keySet().forEach(variant -> metrics.put(variant,
                    scorer.scoreAll(prepared.qrels(), runs.getOrDefault(variant, Map.of()))));
            Map<String, Object> metricsManifest = new LinkedHashMap<>();
            metricsManifest.put("runId", configuration.runId());
            metricsManifest.put("answerMetrics", "not_evaluated_no_gold_answers");
            metricsManifest.put("percentileMethod", "nearest-rank");
            metricsManifest.put("runStatistics", statistics);
            metricsManifest.put("targetsSha256", sha256(configuration.targetsFile()));
            metricsManifest.put("requestTimeoutSeconds", configuration.requestTimeoutSeconds());
            if (manifest.containsKey("resume")) metricsManifest.put("resume", manifest.get("resume"));
            runIO.writeReport(configuration.runDirectory().resolve("metrics.json"), metrics, metricsManifest);
            manifest.put("status", "completed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("queryCount", queries.size());
            writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
            return new Result(configuration.runId(), "existing", "existing", metrics, statistics);
        } catch (RuntimeException | IOException | InterruptedException exception) {
            manifest.put("status", "failed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("errorType", exception.getClass().getSimpleName());
            putManifestErrorCode(manifest, exception);
            writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
            throw exception;
        }
    }

    private void putManifestErrorCode(Map<String, Object> manifest, Exception exception) {
        if (exception instanceof RagBenchmarkHttpClient.BenchmarkApiException api) {
            manifest.put("errorCode", api.code());
        } else if (exception instanceof RagBenchmarkHttpClient.BenchmarkProtocolException protocol) {
            manifest.put("errorCode", protocol.code());
        } else if (exception instanceof RagBenchmarkWarmupGate.WarmupGateException gate) {
            manifest.put("errorCode", gate.code());
        } else if (exception instanceof RagBenchmarkResumeGate.ResumeGateException gate) {
            manifest.put("errorCode", gate.code());
        } else if (exception instanceof HttpTimeoutException) {
            manifest.put("errorCode", REQUEST_TIMEOUT_ERROR_CODE);
        } else if (exception instanceof IOException) {
            manifest.put("errorCode", IO_ERROR_CODE);
        }
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
                        "benchmark摄取任务终止: " + latest.status() + "/" + latest.stage() + "/"
                                + (latest.errorCode() == null ? "" : latest.errorCode()));
            }
            Thread.sleep(configuration.pollInterval().toMillis());
        }
        throw new RagBenchmarkHttpClient.BenchmarkProtocolException("RAG_BENCHMARK_INGEST_TIMEOUT",
                "benchmark摄取任务超时，最后状态=" + (latest == null ? "unknown" : latest.status()));
    }

    private int prepareEvaluationRun(EvaluationConfiguration configuration, PreparedDataset prepared,
                                     Map<String, String> targets, List<Query> shuffled,
                                     Map<String, Object> manifest)
            throws IOException, InterruptedException {
        if (configuration.resumeDirectory() == null) {
            runWarmup(configuration.runId(), configuration.runDirectory(), configuration.warmupQueries(),
                    targets, shuffled);
            validateWarmup(configuration.runDirectory(), configuration.warmupQueries(), shuffled.size(),
                    targets.keySet());
            return 0;
        }

        List<RagBenchmarkResumeGate.ExpectedRecord> expectedWarmup = expectedWarmup(
                configuration.warmupQueries(), targets, shuffled);
        List<RagBenchmarkResumeGate.ExpectedRecord> expectedMeasured = expectedMeasured(targets, shuffled);
        RagBenchmarkResumeGate.ResumeState resume = new RagBenchmarkResumeGate(objectMapper).validate(
                configuration.resumeDirectory(), manifest, sha256(configuration.targetsFile()), targets,
                expectedWarmup, expectedMeasured, prepared.documentIds());
        manifest.put("resume", resume.toManifest(configuration.resumeDirectory().getFileName().toString(),
                configuration.runId()));
        writeAtomic(configuration.runDirectory().resolve("run-manifest.json"), manifest);
        Files.copy(configuration.resumeDirectory().resolve("warmup.jsonl"),
                configuration.runDirectory().resolve("warmup.jsonl"));
        Files.copy(configuration.resumeDirectory().resolve("run.jsonl"),
                configuration.runDirectory().resolve("run.jsonl"));
        if (!resume.sourceWarmupSha256().equals(sha256(configuration.runDirectory().resolve("warmup.jsonl")))
                || !resume.sourceRunSha256().equals(sha256(
                configuration.runDirectory().resolve("run.jsonl")))) {
            throw new RagBenchmarkResumeGate.ResumeGateException("source_changed_during_copy");
        }
        return resume.resumedRecordCount();
    }

    private List<RagBenchmarkResumeGate.ExpectedRecord> expectedWarmup(int warmupQueries,
                                                                       Map<String, String> targets,
                                                                       List<Query> queries) {
        List<RagBenchmarkResumeGate.ExpectedRecord> expected = new ArrayList<>();
        int count = Math.min(warmupQueries, queries.size());
        for (int queryIndex = 0; queryIndex < count; queryIndex++) {
            Query query = queries.get(queryIndex);
            for (String variant : targets.keySet()) {
                expected.add(expectedRecord(variant, query));
            }
        }
        return List.copyOf(expected);
    }

    private List<RagBenchmarkResumeGate.ExpectedRecord> expectedMeasured(Map<String, String> targets,
                                                                         List<Query> queries) {
        List<RagBenchmarkResumeGate.ExpectedRecord> expected = new ArrayList<>();
        List<String> variants = new ArrayList<>(targets.keySet());
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            Query query = queries.get(queryIndex);
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((queryIndex + offset) % variants.size());
                expected.add(expectedRecord(variant, query));
            }
        }
        return List.copyOf(expected);
    }

    private RagBenchmarkResumeGate.ExpectedRecord expectedRecord(String variant, Query query) {
        return new RagBenchmarkResumeGate.ExpectedRecord(variant, query.queryId(), sha256(query.text()));
    }

    private void runWarmup(String runId, Path runDirectory, int warmupQueries,
                           Map<String, String> targets, List<Query> queries)
            throws IOException, InterruptedException {
        int count = Math.min(warmupQueries, queries.size());
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(objectMapper);
        Path warmup = runDirectory.resolve("warmup.jsonl");
        for (int index = 0; index < count; index++) {
            Query query = queries.get(index);
            for (Map.Entry<String, String> target : targets.entrySet()) {
                runIO.append(warmup, execute(runId, target.getKey(), target.getValue(), query));
            }
        }
    }

    private void validateWarmup(Path runDirectory, int warmupQueries, int queryCount, java.util.Set<String> variants)
            throws IOException {
        int expectedQueries = Math.min(warmupQueries, queryCount);
        if (expectedQueries == 0) return;
        List<RagBenchmarkRunIO.RunRecord> records = new RagBenchmarkRunIO(objectMapper)
                .readRecords(runDirectory.resolve("warmup.jsonl"));
        new RagBenchmarkWarmupGate().validate(records, expectedQueries, variants);
    }

    private void executeMeasured(String runId, Path runDirectory,
                                 Map<String, String> targets, List<Query> queries, int completedRecords)
            throws IOException, InterruptedException {
        RagBenchmarkRunIO runIO = new RagBenchmarkRunIO(objectMapper);
        Path runFile = runDirectory.resolve("run.jsonl");
        List<String> variants = new ArrayList<>(targets.keySet());
        int recordIndex = 0;
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            Query query = queries.get(queryIndex);
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((queryIndex + offset) % variants.size());
                if (recordIndex++ < completedRecords) continue;
                runIO.append(runFile, execute(runId, variant, targets.get(variant), query));
            }
        }
    }

    private Map<String, String> readTargets(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("benchmark targets文件不存在");
        }
        JsonNode values = objectMapper.readTree(path.toFile()).path("targets");
        Map<String, String> targets = new LinkedHashMap<>();
        RagBenchmarkHttpClient.ProfileDefinition.ablations().forEach(definition -> {
            String targetId = values.path(definition.variant()).asText();
            if (targetId.isBlank() || !targetId.matches("[A-Za-z0-9_.-]{1,120}")) {
                throw new IllegalArgumentException("benchmark targets缺少合法变体: " + definition.variant());
            }
            targets.put(definition.variant(), targetId);
        });
        if (!values.isObject() || values.size() != targets.size()
                || targets.values().stream().distinct().count() != targets.size()) {
            throw new IllegalArgumentException("benchmark targets必须恰好包含4个唯一目标");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(targets));
    }

    private RagBenchmarkRunIO.RunRecord execute(String runId, String variant, String targetId, Query query)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            RagBenchmarkHttpClient.DebugResult result = client.debug(targetId, query.text());
            return new RagBenchmarkRunIO.RunRecord(runId, variant, query.queryId(), sha256(query.text()),
                    result.retrievalId(), result.rankedDocumentIds(), elapsedMs(started), result.degraded(),
                    result.degradationReasons(), null, result.timingsMs(), result.candidateCounts());
        } catch (RagBenchmarkHttpClient.BenchmarkApiException exception) {
            return error(runId, variant, query, started, exception.code());
        } catch (RagBenchmarkHttpClient.BenchmarkProtocolException exception) {
            return error(runId, variant, query, started, exception.code());
        }
    }

    private RagBenchmarkRunIO.RunRecord error(String runId, String variant, Query query, long started, String code) {
        return new RagBenchmarkRunIO.RunRecord(runId, variant, query.queryId(), sha256(query.text()), null,
                List.of(), elapsedMs(started), false, List.of(), code, Map.of(), Map.of());
    }

    private PreparedDataset validatePreparedDataset(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("prepared benchmark目录不存在");
        }
        Path manifestPath = directory.resolve("manifest.json");
        Path queriesPath = directory.resolve("queries.jsonl");
        Path qrelsPath = directory.resolve("qrels.tsv");
        Path documentMapPath = directory.resolve("document-map.jsonl");
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(queriesPath)
                || !Files.isRegularFile(qrelsPath) || !Files.isRegularFile(documentMapPath)) {
            throw new IllegalArgumentException("prepared benchmark文件不完整");
        }
        List<Path> markdownFiles;
        try (var paths = Files.list(directory.resolve("documents"))) {
            markdownFiles = paths.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted().toList();
        }
        if (markdownFiles.size() != 1) {
            throw new IllegalArgumentException("当前generation语义下黑盒评测只允许一个Markdown分片，实际="
                    + markdownFiles.size());
        }
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(objectMapper)
                .loadQrels(qrelsPath, BeirDatasetLoader.Limits.defaults());
        return new PreparedDataset(manifestPath, markdownFiles.get(0), queriesPath, qrelsPath, documentMapPath,
                readDocumentIds(documentMapPath), qrels,
                manifest.path("datasetName").asText(), manifest.path("documentCount").asInt(),
                manifest.path("queryCount").asInt(), manifest.path("sourceRevision").asText());
    }

    private Set<String> readDocumentIds(Path path) throws IOException {
        java.util.LinkedHashSet<String> documentIds = new java.util.LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) throw new IllegalArgumentException("prepared document-map包含空行");
                String documentId = objectMapper.readTree(line).path("documentId").asText();
                if (documentId.isBlank() || !documentIds.add(documentId)) {
                    throw new IllegalArgumentException("prepared document-map包含非法或重复documentId");
                }
            }
        }
        return Set.copyOf(documentIds);
    }

    private List<Query> readQueries(Path path) throws IOException {
        List<Query> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                String id = node.path("queryId").asText();
                String text = node.path("text").asText();
                if (id.isBlank() || text.isBlank()) throw new IllegalArgumentException("prepared query记录非法");
                result.add(new Query(id, text));
            }
        }
        result.sort(Comparator.comparing(Query::queryId));
        return List.copyOf(result);
    }

    private Map<String, Object> initialManifest(Configuration configuration, PreparedDataset prepared,
                                                Instant startedAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1);
        values.put("runId", configuration.runId());
        values.put("status", "running");
        values.put("startedAt", startedAt.toString());
        values.put("baseUrl", configuration.baseUrl().toString());
        values.put("credentialSource", configuration.credentialSource());
        values.put("codeRevision", configuration.codeRevision());
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        values.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        values.put("dataset", prepared.datasetName());
        values.put("sourceRevision", prepared.sourceRevision());
        values.put("documentCount", prepared.documentCount());
        values.put("queryCount", prepared.queryCount());
        values.put("markdownFile", prepared.markdownFile().getFileName().toString());
        values.put("markdownBytes", size(prepared.markdownFile()));
        values.put("markdownSha256", sha256(prepared.markdownFile()));
        values.put("preparedManifestSha256", sha256(prepared.manifestFile()));
        values.put("queriesSha256", sha256(prepared.queriesFile()));
        values.put("qrelsSha256", sha256(prepared.qrelsFile()));
        values.put("documentMapSha256", sha256(prepared.documentMapFile()));
        values.put("seed", configuration.seed());
        values.put("warmupQueries", configuration.warmupQueries());
        values.put("requestTimeoutSeconds", configuration.requestTimeoutSeconds());
        values.put("resumeProtocolVersion", 1);
        values.put("queryThreads", 1);
        values.put("uploadThreads", 1);
        values.put("workerThreads", 1);
        values.put("cleanup", "keep");
        values.put("variants", RagBenchmarkHttpClient.ProfileDefinition.ablations());
        return values;
    }

    private void prepareEmptyDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IllegalArgumentException("run输出路径不是目录");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                if (stream.iterator().hasNext()) throw new IllegalArgumentException("run输出目录必须为空");
            }
        } else Files.createDirectories(directory);
    }

    private void writeAtomic(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
    private long size(Path path) {
        try { return Files.size(path); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    private long elapsedMs(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Query(String queryId, String text) {}
    private record PreparedDataset(Path manifestFile, Path markdownFile, Path queriesFile, Path qrelsFile,
                                   Path documentMapFile, Set<String> documentIds,
                                   Map<String, Map<String, Integer>> qrels, String datasetName,
                                   int documentCount, int queryCount, String sourceRevision) {}

    public record Configuration(String runId, java.net.URI baseUrl, String credentialSource, String codeRevision,
                                Path preparedDirectory, Path runDirectory, long seed, int warmupQueries,
                                Duration pollInterval, Duration ingestTimeout, int requestTimeoutSeconds) {
        public Configuration(String runId, java.net.URI baseUrl, String credentialSource, String codeRevision,
                             Path preparedDirectory, Path runDirectory, long seed, int warmupQueries,
                             Duration pollInterval, Duration ingestTimeout) {
            this(runId, baseUrl, credentialSource, codeRevision, preparedDirectory, runDirectory, seed,
                    warmupQueries, pollInterval, ingestTimeout, 120);
        }

        public Configuration {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,64}") || baseUrl == null
                    || credentialSource == null || credentialSource.isBlank() || preparedDirectory == null
                    || codeRevision == null || codeRevision.isBlank()
                    || runDirectory == null || warmupQueries < 0 || pollInterval == null
                    || pollInterval.isZero() || pollInterval.isNegative() || ingestTimeout == null
                    || ingestTimeout.isZero() || ingestTimeout.isNegative() || requestTimeoutSeconds < 1) {
                throw new IllegalArgumentException("benchmark runner配置非法");
            }
        }
    }
    public record EvaluationConfiguration(String runId, java.net.URI baseUrl, String credentialSource,
                                          String codeRevision, Path preparedDirectory, Path targetsFile,
                                          Path runDirectory, long seed, int warmupQueries, Path resumeDirectory,
                                          int requestTimeoutSeconds) {
        public EvaluationConfiguration(String runId, java.net.URI baseUrl, String credentialSource,
                                       String codeRevision, Path preparedDirectory, Path targetsFile,
                                       Path runDirectory, long seed, int warmupQueries) {
            this(runId, baseUrl, credentialSource, codeRevision, preparedDirectory, targetsFile,
                    runDirectory, seed, warmupQueries, null, 120);
        }

        public EvaluationConfiguration {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,64}") || baseUrl == null
                    || credentialSource == null || credentialSource.isBlank() || codeRevision == null
                    || codeRevision.isBlank() || preparedDirectory == null || targetsFile == null
                    || runDirectory == null || warmupQueries < 0
                    || resumeDirectory != null && runDirectory.equals(resumeDirectory)
                    || requestTimeoutSeconds < 1) {
                throw new IllegalArgumentException("benchmark复评分配置非法");
            }
        }
    }
    public record Result(String runId, String knowledgeBaseId, String taskId,
                         Map<String, RagRetrievalScorer.AggregateMetrics> metrics,
                         Map<String, RagBenchmarkRunStatistics.VariantStatistics> statistics) {}
}
