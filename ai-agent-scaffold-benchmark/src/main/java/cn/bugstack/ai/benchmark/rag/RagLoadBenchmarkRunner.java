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
import java.util.concurrent.ExecutionException;
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
        try {
            for (int concurrency : configuration.concurrencyLevels()) {
                if (configuration.warmupRequestsPerVariant() > 0) {
                    PhaseResult warmupResult = executePhase(configuration, targetSet.targets(), queries,
                            concurrency, configuration.warmupRequestsPerVariant(), true);
                    warmup.addAll(warmupResult.records());
                }
                PhaseResult measuredResult = executePhase(configuration, targetSet.targets(), queries,
                        concurrency, configuration.measuredRequestsPerVariant(), false);
                measured.addAll(measuredResult.records());
                phaseElapsedMs.put(concurrency, measuredResult.elapsedMs());
            }
            writeJsonLines(configuration.outputDirectory().resolve("warmup.jsonl"), warmup);
            writeJsonLines(configuration.outputDirectory().resolve("load.jsonl"), measured);
            Map<Integer, RagLoadBenchmarkStatistics.ConcurrencyStatistics> statistics =
                    new RagLoadBenchmarkStatistics().aggregate(measured, phaseElapsedMs);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("runId", configuration.runId());
            report.put("percentileMethod", "nearest-rank");
            report.put("loadModel", "closed-loop-fixed-request-count");
            report.put("coordinatedOmissionWarning",
                    "closed-loop结果不代表open-loop到达率过载表现");
            report.put("serverResourceEvidence", "not_collected_by_client");
            report.put("levels", statistics);
            writeAtomic(configuration.outputDirectory().resolve("load-report.json"), report);
            manifest.put("status", "completed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("measuredRequestCount", measured.size());
            manifest.put("warmupRequestCount", warmup.size());
            manifest.put("clientSnapshotAfter", clientSnapshot());
            writeAtomic(manifestPath, manifest);
            return new Result(configuration.runId(), List.copyOf(measured), Map.copyOf(statistics));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            manifest.put("status", "failed");
            manifest.put("finishedAt", Instant.now().toString());
            manifest.put("errorType", exception.getClass().getSimpleName());
            writeAtomic(manifestPath, manifest);
            throw exception;
        }
    }

    private PhaseResult executePhase(Configuration configuration, Map<String, String> targets, List<Query> queries,
                                     int concurrency, int requestsPerVariant, boolean warmup)
            throws InterruptedException {
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
        try {
            for (RequestSpec request : requests) {
                futures.add(executor.submit(() -> {
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
            for (Future<LoadRecord> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("并发压测阶段超时");
                records.add(future.get(remaining, TimeUnit.NANOSECONDS));
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
        long started = System.nanoTime();
        try {
            RagBenchmarkHttpClient.DebugResult result = client.debug(request.targetId(), request.query().text());
            return new LoadRecord(runId, concurrency, request.sequence(), Thread.currentThread().getName(),
                    request.variant(), request.query().queryId(), sha256(request.query().text()),
                    result.retrievalId(), result.rankedDocumentIds(), elapsedMs(started), result.degraded(),
                    result.degradationReasons(), null, result.timingsMs(), result.candidateCounts());
        } catch (RagBenchmarkHttpClient.BenchmarkApiException exception) {
            return error(runId, concurrency, request, started, exception.code());
        } catch (RagBenchmarkHttpClient.BenchmarkProtocolException exception) {
            return error(runId, concurrency, request, started, exception.code());
        } catch (IOException exception) {
            return error(runId, concurrency, request, started, "RAG_BENCHMARK_IO");
        }
    }

    private LoadRecord error(String runId, int concurrency, RequestSpec request, long started, String errorCode) {
        return new LoadRecord(runId, concurrency, request.sequence(), Thread.currentThread().getName(),
                request.variant(), request.query().queryId(), sha256(request.query().text()), null, List.of(),
                elapsedMs(started), false, List.of(), errorCode, Map.of(), Map.of());
    }

    private List<RequestSpec> requests(Map<String, String> targets, List<Query> queries, int requestsPerVariant,
                                       long seed) {
        List<RequestSpec> values = new ArrayList<>(targets.size() * requestsPerVariant);
        List<String> variants = new ArrayList<>(targets.keySet());
        long sequence = 0;
        for (int iteration = 0; iteration < requestsPerVariant; iteration++) {
            for (int offset = 0; offset < variants.size(); offset++) {
                String variant = variants.get((iteration + offset) % variants.size());
                Query query = queries.get((iteration * variants.size() + offset) % queries.size());
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
        values.put("variantCount", targets.targets().size());
        values.put("loadModel", "closed-loop-fixed-request-count");
        values.put("clientSnapshotBefore", clientSnapshot());
        values.put("serverResourceEvidence", "not_collected_by_client");
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

    private void writeJsonLines(Path path, List<LoadRecord> records) throws IOException {
        if (Files.exists(path)) throw new IllegalArgumentException("禁止覆盖已有压测原始记录");
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (LoadRecord record : records) {
                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
            }
        }
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
                                int measuredRequestsPerVariant, Duration phaseTimeout) {
        public Configuration {
            if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,64}") || baseUrl == null
                    || credentialSource == null || credentialSource.isBlank() || codeRevision == null
                    || codeRevision.isBlank() || preparedDirectory == null || targetsFile == null
                    || outputDirectory == null || concurrencyLevels == null || concurrencyLevels.isEmpty()
                    || warmupRequestsPerVariant < 0 || measuredRequestsPerVariant < 1 || phaseTimeout == null
                    || phaseTimeout.isZero() || phaseTimeout.isNegative()) {
                throw new IllegalArgumentException("load runner配置非法");
            }
            List<Integer> normalized = concurrencyLevels.stream().distinct().sorted().toList();
            if (normalized.stream().anyMatch(value -> value == null || value < 1 || value > 256)) {
                throw new IllegalArgumentException("并发级别必须在1到256之间");
            }
            concurrencyLevels = List.copyOf(normalized);
        }
    }

    public record LoadRecord(String runId, int concurrency, long sequence, String worker,
                             String variant, String queryId, String querySha256, String retrievalId,
                             List<String> rankedDocumentIds, long elapsedMs, boolean degraded,
                             List<String> degradationReasons, String errorCode,
                             Map<String, Long> stageTimingsMs, Map<String, Integer> candidateCounts) {
        public LoadRecord {
            rankedDocumentIds = rankedDocumentIds == null ? List.of() : List.copyOf(rankedDocumentIds);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            stageTimingsMs = stageTimingsMs == null ? Map.of() : Map.copyOf(stageTimingsMs);
            candidateCounts = candidateCounts == null ? Map.of() : Map.copyOf(candidateCounts);
        }
        public boolean failed() { return errorCode != null && !errorCode.isBlank(); }
    }

    public record Result(String runId, List<LoadRecord> records,
                         Map<Integer, RagLoadBenchmarkStatistics.ConcurrencyStatistics> statistics) {}
}
