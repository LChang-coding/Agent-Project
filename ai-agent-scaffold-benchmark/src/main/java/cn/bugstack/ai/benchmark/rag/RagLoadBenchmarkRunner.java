package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** 复用已完成生产 run 的 target，执行有界并发检索压测。 */
public final class RagLoadBenchmarkRunner {

    private final ObjectMapper objectMapper;
    private final RagBenchmarkHttpClient client;

    public RagLoadBenchmarkRunner(ObjectMapper objectMapper, RagBenchmarkHttpClient client) {
        if (objectMapper == null || client == null) throw new IllegalArgumentException("load runner依赖不能为空");
        this.objectMapper = objectMapper;
        this.client = client;
    }

    public Result run(Configuration configuration) throws IOException, InterruptedException {
        prepareEmptyDirectory(configuration.outputDirectory());
        List<Query> queries = readQueries(configuration.preparedDirectory().resolve("queries.jsonl"));
        TargetSet targetSet = readTargets(configuration.targetsFile());
        Instant startedAt = Instant.now();
        Map<String, Object> manifest = initialManifest(configuration, queries, targetSet, startedAt);
        Path manifestPath = configuration.outputDirectory().resolve("load-manifest.json");
        writeAtomic(manifestPath, manifest);
        List<LoadRecord> measured = new ArrayList<>();
        List<LoadRecord> warmup = new ArrayList<>();
        Map<Integer, Long> phaseElapsedMs = new LinkedHashMap<>();
        Path warmupPath = configuration.outputDirectory().resolve("warmup.jsonl");
        Path measuredPath = configuration.outputDirectory().resolve("load.jsonl");
        try (BufferedWriter warmupWriter = newRecordWriter(warmupPath);
             BufferedWriter measuredWriter = newRecordWriter(measuredPath)) {
            for (int concurrency : configuration.concurrencyLevels()) {
                if (configuration.warmupRequestsPerVariant() > 0) {
                    PhaseResult warmupResult = executePhase(configuration, targetSet.targets(), queries,
                            concurrency, configuration.warmupRequestsPerVariant(), true, warmupWriter);
                    warmup.addAll(warmupResult.records());
                }
                PhaseResult measuredResult = executePhase(configuration, targetSet.targets(), queries,
                        concurrency, configuration.measuredRequestsPerVariant(), false, measuredWriter);
                measured.addAll(measuredResult.records());
                phaseElapsedMs.put(concurrency, measuredResult.elapsedMs());
            }
            Map<Integer, RagLoadBenchmarkStatistics.ConcurrencyStatistics> statistics =
                    new RagLoadBenchmarkStatistics().aggregate(measured, phaseElapsedMs);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("runId", configuration.runId());
            report.put("percentileMethod", "nearest-rank");
            report.put("loadModel", "closed-loop-fixed-request-count");
            report.put("coordinatedOmissionWarning",
                    "closed-loop结果不代表open-loop到达率过载表现");
            report.put("serverResourceEvidence", configuration.resourceEvidenceReference());
            report.put("levels", statistics);
            writeAtomic(configuration.outputDirectory().resolve("load-report.json"), report);
            manifest.put("status", "completed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("measuredRequestCount", measured.size());
            manifest.put("warmupRequestCount", warmup.size());
            manifest.put("clientSnapshotAfter", clientSnapshot());
            manifest.put("warmupSha256", sha256(warmupPath));
            manifest.put("loadSha256", sha256(measuredPath));
            writeAtomic(manifestPath, manifest);
            return new Result(configuration.runId(), List.copyOf(measured), Map.copyOf(statistics));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            manifest.put("status", "failed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("errorType", exception.getClass().getSimpleName());
            if (exception instanceof LoadGateException gate) {
                manifest.put("errorCode", "RAG_BENCHMARK_LOAD_GATE_FAILED");
                manifest.put("failedSample", gate.summary());
            }
            writeAtomic(manifestPath, manifest);
            throw exception;
        }
    }

    private PhaseResult executePhase(Configuration configuration, Map<String, String> targets, List<Query> queries,
                                     int concurrency, int requestsPerVariant, boolean warmup,
                                     BufferedWriter writer)
            throws IOException, InterruptedException {
        List<RequestSpec> requests = requests(targets, queries, requestsPerVariant,
                configuration.seed() ^ ((long) concurrency << 32) ^ (warmup ? -1L : 0L));
        AtomicInteger threadSequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "rag-load-" + concurrency + "-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(Math.min(concurrency, requests.size()));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoadRecord>> futures = new ArrayList<>(requests.size());
        CompletionService<LoadRecord> completion = new ExecutorCompletionService<>(executor);
        try {
            for (RequestSpec request : requests) {
                futures.add(completion.submit(() -> {
                    ready.countDown();
                    start.await();
                    return execute(configuration.runId(), concurrency, request);
                }));
            }
            if (!ready.await(configuration.phaseTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("并发工作线程未在限时内就绪");
            }
            long started = System.nanoTime();
            start.countDown();
            long deadline = started + configuration.phaseTimeout().toNanos();
            List<LoadRecord> records = new ArrayList<>(futures.size());
            for (int completed = 0; completed < futures.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("并发压测阶段超时");
                Future<LoadRecord> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) throw new TimeoutException("并发压测阶段超时");
                LoadRecord record = future.get();
                appendRecord(writer, record);
                validateRecord(record, warmup ? "warmup" : "measured");
                records.add(record);
            }
            long elapsedMs = Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
            records.sort(Comparator.comparingLong(LoadRecord::sequence));
            return new PhaseResult(List.copyOf(records), elapsedMs);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            throw new IllegalStateException("压测工作线程异常", cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("压测线程池未在限时内退出");
            }
        }
    }

    private LoadRecord execute(String runId, int concurrency, RequestSpec request) throws InterruptedException {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        try {
            RagBenchmarkHttpClient.DebugResult result = client.debug(request.targetId(), request.query().text());
            return new LoadRecord(runId, concurrency, request.sequence(), Thread.currentThread().getName(),
                    request.variant(), request.query().queryId(), sha256(request.query().text()),
                    result.retrievalId(), result.rankedDocumentIds(), elapsedMs(started), result.degraded(),
                    result.degradationReasons(), null, result.timingsMs(), result.candidateCounts(),
                    startedAt.toString(), Instant.now().toString(), result.httpStatus(), result.responseBytes());
        } catch (RagBenchmarkHttpClient.BenchmarkApiException exception) {
            return error(runId, concurrency, request, startedAt, started, exception.code(),
                    exception.httpStatus(), exception.responseBytes());
        } catch (RagBenchmarkHttpClient.BenchmarkProtocolException exception) {
            return error(runId, concurrency, request, startedAt, started, exception.code(),
                    exception.httpStatus(), exception.responseBytes());
        } catch (IOException exception) {
            return error(runId, concurrency, request, startedAt, started, "RAG_BENCHMARK_IO", null, null);
        }
    }

    private LoadRecord error(String runId, int concurrency, RequestSpec request, Instant startedAt, long started,
                             String errorCode, Integer httpStatus, Integer responseBytes) {
        return new LoadRecord(runId, concurrency, request.sequence(), Thread.currentThread().getName(),
                request.variant(), request.query().queryId(), sha256(request.query().text()), null, List.of(),
                elapsedMs(started), false, List.of(), errorCode, Map.of(), Map.of(), startedAt.toString(),
                Instant.now().toString(), httpStatus, responseBytes);
    }

    private BufferedWriter newRecordWriter(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void appendRecord(BufferedWriter writer, LoadRecord record) throws IOException {
        writer.write(objectMapper.writeValueAsString(record));
        writer.newLine();
        writer.flush();
    }

    private void validateRecord(LoadRecord record, String phase) {
        String reason = null;
        if (record.failed()) reason = "error";
        else if (record.degraded() || !record.degradationReasons().isEmpty()) reason = "degraded";
        else if (record.retrievalId() == null || record.retrievalId().isBlank()) reason = "retrieval_id";
        else if (record.rankedDocumentIds().isEmpty()) reason = "empty_ranking";
        else if (new LinkedHashSet<>(record.rankedDocumentIds()).size() != record.rankedDocumentIds().size()) {
            reason = "duplicate_ranking";
        } else if (record.elapsedMs() <= 0 || record.stageTimingsMs().values().stream().anyMatch(value -> value < 0)
                || record.candidateCounts().values().stream().anyMatch(value -> value < 0)) {
            reason = "invalid_metrics";
        } else if (record.httpStatus() == null || record.httpStatus() < 200 || record.httpStatus() >= 300
                || record.responseBytes() == null || record.responseBytes() <= 0) {
            reason = "invalid_transport_evidence";
        } else if ("hybrid_rrf_rerank".equals(record.variant())
                && (record.candidateCounts().getOrDefault("rerankCandidateCount", 0) <= 0
                || record.stageTimingsMs().getOrDefault("rerankMs", 0L) <= 0)) {
            reason = "invalid_rerank";
        } else {
            try {
                Instant startedAt = Instant.parse(record.startedAt());
                Instant finishedAt = Instant.parse(record.finishedAt());
                if (finishedAt.isBefore(startedAt)) reason = "invalid_timestamps";
            } catch (RuntimeException exception) {
                reason = "invalid_timestamps";
            }
        }
        if (reason != null) throw new LoadGateException(record, phase, reason);
    }

    private List<RequestSpec> requests(Map<String, String> targets, List<Query> queries, int requestsPerVariant,
                                       long seed) {
        List<RequestSpec> values = new ArrayList<>(targets.size() * requestsPerVariant);
        List<String> variants = new ArrayList<>(targets.keySet());
        long sequence = 0;
        for (int iteration = 0; iteration < requestsPerVariant; iteration++) {
            Query query = queries.get(iteration % queries.size());
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((iteration + offset) % variants.size());
                values.add(new RequestSpec(sequence++, variant, targets.get(variant), query));
            }
        }
        Collections.shuffle(values, new Random(seed));
        List<RequestSpec> sequenced = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            RequestSpec value = values.get(index);
            sequenced.add(new RequestSpec(index, value.variant(), value.targetId(), value.query()));
        }
        return List.copyOf(sequenced);
    }

    private TargetSet readTargets(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IllegalArgumentException("targets文件不存在");
        JsonNode root = objectMapper.readTree(path.toFile());
        if (root.path("schemaVersion").asInt() != 1 || root.path("sourceRunId").asText().isBlank()) {
            throw new IllegalArgumentException("targets文件版本或来源run非法");
        }
        JsonNode targetNode = root.path("targets");
        Map<String, String> targets = new LinkedHashMap<>();
        for (RagBenchmarkHttpClient.ProfileDefinition definition
                : RagBenchmarkHttpClient.ProfileDefinition.ablations()) {
            String target = targetNode.path(definition.variant()).asText();
            if (!target.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("targets映射不完整");
            targets.put(definition.variant(), target);
        }
        Set<String> actual = new LinkedHashSet<>();
        targetNode.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(targets.keySet())) throw new IllegalArgumentException("targets包含未知variant");
        return new TargetSet(root.path("sourceRunId").asText(),
                Collections.unmodifiableMap(new LinkedHashMap<>(targets)), sha256(path));
    }

    private List<Query> readQueries(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("prepared queries文件不存在");
        List<Query> values = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                String queryId = node.path("queryId").asText();
                String text = node.path("text").asText();
                if (queryId.isBlank() || text.isBlank()) throw new IllegalArgumentException("prepared query记录非法");
                values.add(new Query(queryId, text));
            }
        }
        if (values.isEmpty()) throw new IllegalArgumentException("prepared queries为空");
        values.sort(Comparator.comparing(Query::queryId));
        return List.copyOf(values);
    }

    private Map<String, Object> initialManifest(Configuration configuration, List<Query> queries,
                                                TargetSet targets, Instant startedAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1);
        values.put("runId", configuration.runId());
        values.put("status", "running");
        values.put("startedAt", startedAt.toString());
        values.put("baseUrl", configuration.baseUrl().toString());
        values.put("credentialSource", configuration.credentialSource());
        values.put("codeRevision", configuration.codeRevision());
        values.put("sourceRunId", targets.sourceRunId());
        values.put("targetsSha256", targets.sha256());
        values.put("queriesSha256", sha256(configuration.preparedDirectory().resolve("queries.jsonl")));
        values.put("queryCount", queries.size());
        values.put("seed", configuration.seed());
        values.put("concurrencyLevels", configuration.concurrencyLevels());
        values.put("warmupRequestsPerVariant", configuration.warmupRequestsPerVariant());
        values.put("measuredRequestsPerVariant", configuration.measuredRequestsPerVariant());
        values.put("phaseTimeoutMs", configuration.phaseTimeout().toMillis());
        values.put("requestTimeoutMs", configuration.requestTimeout().toMillis());
        values.put("connectTimeoutMs", configuration.connectTimeout().toMillis());
        values.put("cliJarSha256", configuration.cliJarSha256());
        values.put("appJarSha256", configuration.appJarSha256());
        values.put("variantCount", targets.targets().size());
        values.put("queryPairing", "same-deterministic-query-per-variant-v1");
        values.put("rawPersistence", "completion-order-flush-per-record-v1");
        values.put("strictPhaseGate", true);
        values.put("loadModel", "closed-loop-fixed-request-count");
        values.put("clientSnapshotBefore", clientSnapshot());
        values.put("serverResourceEvidence", configuration.resourceEvidenceReference());
        return values;
    }

    private Map<String, Object> clientSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("capturedAt", Instant.now().toString());
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        values.put("availableProcessors", runtime.availableProcessors());
        values.put("jvmMaxMemoryBytes", runtime.maxMemory());
        values.put("jvmTotalMemoryBytes", runtime.totalMemory());
        values.put("jvmFreeMemoryBytes", runtime.freeMemory());
        values.put("systemLoadAverage", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        return values;
    }

    private void prepareEmptyDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IllegalArgumentException("load输出路径不是目录");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                if (stream.iterator().hasNext()) throw new IllegalArgumentException("load输出目录必须为空");
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

    private long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算文件SHA-256", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private record Query(String queryId, String text) {}
    private record RequestSpec(long sequence, String variant, String targetId, Query query) {}
    private record TargetSet(String sourceRunId, Map<String, String> targets, String sha256) {}
    private record PhaseResult(List<LoadRecord> records, long elapsedMs) {}

    public record Configuration(String runId, URI baseUrl, String credentialSource, String codeRevision,
                                Path preparedDirectory, Path targetsFile, Path outputDirectory, long seed,
                                List<Integer> concurrencyLevels, int warmupRequestsPerVariant,
                                int measuredRequestsPerVariant, Duration phaseTimeout, Duration requestTimeout,
                                Duration connectTimeout, String cliJarSha256, String appJarSha256,
                                String resourceEvidenceReference) {
        public Configuration {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,64}") || baseUrl == null
                    || credentialSource == null || credentialSource.isBlank() || codeRevision == null
                    || codeRevision.isBlank() || preparedDirectory == null || targetsFile == null
                    || outputDirectory == null || concurrencyLevels == null || concurrencyLevels.isEmpty()
                    || warmupRequestsPerVariant < 0 || measuredRequestsPerVariant < 1 || phaseTimeout == null
                    || phaseTimeout.isZero() || phaseTimeout.isNegative() || requestTimeout == null
                    || requestTimeout.isZero() || requestTimeout.isNegative() || connectTimeout == null
                    || connectTimeout.isZero() || connectTimeout.isNegative()
                    || cliJarSha256 == null || !cliJarSha256.matches("[0-9a-f]{64}")
                    || appJarSha256 == null || !appJarSha256.matches("[0-9a-f]{64}")
                    || resourceEvidenceReference == null || resourceEvidenceReference.isBlank()
                    || resourceEvidenceReference.length() > 512) {
                throw new IllegalArgumentException("load runner配置非法");
            }
            if (concurrencyLevels.stream().anyMatch(value -> value == null || value < 1 || value > 256)
                    || new LinkedHashSet<>(concurrencyLevels).size() != concurrencyLevels.size()) {
                throw new IllegalArgumentException("并发级别必须在1到256之间");
            }
            concurrencyLevels = List.copyOf(concurrencyLevels);
        }

        public Configuration(String runId, URI baseUrl, String credentialSource, String codeRevision,
                             Path preparedDirectory, Path targetsFile, Path outputDirectory, long seed,
                             List<Integer> concurrencyLevels, int warmupRequestsPerVariant,
                             int measuredRequestsPerVariant, Duration phaseTimeout) {
            this(runId, baseUrl, credentialSource, codeRevision, preparedDirectory, targetsFile,
                    outputDirectory, seed, concurrencyLevels, warmupRequestsPerVariant,
                    measuredRequestsPerVariant, phaseTimeout, Duration.ofSeconds(120), Duration.ofSeconds(10),
                    "0".repeat(64), "0".repeat(64), "not_collected_by_test_client");
        }
    }

    public record LoadRecord(String runId, int concurrency, long sequence, String worker,
                             String variant, String queryId, String querySha256, String retrievalId,
                             List<String> rankedDocumentIds, long elapsedMs, boolean degraded,
                             List<String> degradationReasons, String errorCode,
                             Map<String, Long> stageTimingsMs, Map<String, Integer> candidateCounts,
                             String startedAt, String finishedAt, Integer httpStatus, Integer responseBytes) {
        public LoadRecord {
            rankedDocumentIds = rankedDocumentIds == null ? List.of() : List.copyOf(rankedDocumentIds);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            stageTimingsMs = stageTimingsMs == null ? Map.of() : Map.copyOf(stageTimingsMs);
            candidateCounts = candidateCounts == null ? Map.of() : Map.copyOf(candidateCounts);
        }
        public LoadRecord(String runId, int concurrency, long sequence, String worker,
                          String variant, String queryId, String querySha256, String retrievalId,
                          List<String> rankedDocumentIds, long elapsedMs, boolean degraded,
                          List<String> degradationReasons, String errorCode,
                          Map<String, Long> stageTimingsMs, Map<String, Integer> candidateCounts) {
            this(runId, concurrency, sequence, worker, variant, queryId, querySha256, retrievalId,
                    rankedDocumentIds, elapsedMs, degraded, degradationReasons, errorCode, stageTimingsMs,
                    candidateCounts, "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.001Z", 200, 1);
        }
        public boolean failed() { return errorCode != null && !errorCode.isBlank(); }
    }

    private static final class LoadGateException extends IllegalStateException {
        private final Map<String, Object> summary;

        private LoadGateException(LoadRecord record, String phase, String reason) {
            super("RAG_BENCHMARK_LOAD_GATE_FAILED");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("phase", phase);
            value.put("concurrency", record.concurrency());
            value.put("sequence", record.sequence());
            value.put("variant", record.variant());
            value.put("queryId", record.queryId());
            value.put("reason", reason);
            value.put("sampleErrorCode", record.errorCode());
            value.put("httpStatus", record.httpStatus());
            value.put("rankedCount", record.rankedDocumentIds().size());
            summary = Collections.unmodifiableMap(value);
        }

        private Map<String, Object> summary() { return summary; }
    }

    public record Result(String runId, List<LoadRecord> records,
                         Map<Integer, RagLoadBenchmarkStatistics.ConcurrencyStatistics> statistics) {}
}
