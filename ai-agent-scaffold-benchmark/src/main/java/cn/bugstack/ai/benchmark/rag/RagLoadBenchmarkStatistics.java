package cn.bugstack.ai.benchmark.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按并发级别和消融组聚合压测延迟、错误和观测到的主导耗时。 */
public final class RagLoadBenchmarkStatistics {

    public Map<Integer, ConcurrencyStatistics> aggregate(List<RagLoadBenchmarkRunner.LoadRecord> records,
                                                         Map<Integer, Long> phaseElapsedMs) {
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("load记录不能为空");
        Map<Integer, List<RagLoadBenchmarkRunner.LoadRecord>> byConcurrency = new LinkedHashMap<>();
        records.stream().sorted(Comparator.comparingInt(RagLoadBenchmarkRunner.LoadRecord::concurrency)
                        .thenComparingLong(RagLoadBenchmarkRunner.LoadRecord::sequence))
                .forEach(record -> byConcurrency.computeIfAbsent(record.concurrency(), ignored -> new ArrayList<>())
                        .add(record));
        Map<Integer, ConcurrencyStatistics> result = new LinkedHashMap<>();
        byConcurrency.forEach((concurrency, values) -> {
            long elapsedMs = requiredElapsed(phaseElapsedMs, concurrency);
            long successCount = values.stream().filter(value -> !value.failed()).count();
            Map<String, VariantStatistics> variants = new LinkedHashMap<>();
            values.stream().map(RagLoadBenchmarkRunner.LoadRecord::variant).distinct().sorted().forEach(variant ->
                    variants.put(variant, summarize(values.stream()
                            .filter(value -> variant.equals(value.variant())).toList())));
            double attemptThroughput = throughput(values.size(), elapsedMs);
            result.put(concurrency, new ConcurrencyStatistics(concurrency, values.size(), values.size(),
                    successCount, elapsedMs, attemptThroughput, attemptThroughput,
                    throughput(successCount, elapsedMs), Map.copyOf(variants)));
        });
        return Map.copyOf(result);
    }

    private VariantStatistics summarize(List<RagLoadBenchmarkRunner.LoadRecord> records) {
        List<RagLoadBenchmarkRunner.LoadRecord> successes = records.stream()
                .filter(value -> !value.failed()).toList();
        long errors = records.size() - successes.size();
        long degraded = successes.stream().filter(RagLoadBenchmarkRunner.LoadRecord::degraded).count();
        long successfulEmpty = successes.stream().filter(value -> value.rankedDocumentIds().isEmpty()).count();
        Map<String, Long> errorCodeCounts = new LinkedHashMap<>();
        records.stream().filter(RagLoadBenchmarkRunner.LoadRecord::failed)
                .map(RagLoadBenchmarkRunner.LoadRecord::errorCode).sorted()
                .forEach(errorCode -> errorCodeCounts.merge(errorCode, 1L, Long::sum));
        Map<String, Distribution> stages = new LinkedHashMap<>();
        successes.stream().flatMap(value -> value.stageTimingsMs().keySet().stream()).distinct().sorted()
                .forEach(stage -> stages.put(stage, distribution(successes.stream()
                        .filter(value -> value.stageTimingsMs().containsKey(stage))
                        .map(value -> value.stageTimingsMs().get(stage)).toList())));
        List<Long> outsideReportedService = successes.stream()
                .map(value -> Math.max(0L, value.elapsedMs()
                        - reportedServiceMs(value.stageTimingsMs()))).toList();
        if (!outsideReportedService.isEmpty()) {
            stages.put("outsideReportedServiceMs", distribution(outsideReportedService));
        }
        String dominant = stages.entrySet().stream().filter(entry -> entry.getValue().count() > 0)
                .filter(entry -> !List.of("totalMs", "serviceMs").contains(entry.getKey()))
                .max(Comparator.<Map.Entry<String, Distribution>>comparingDouble(entry -> entry.getValue().mean())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse("unavailable");
        Distribution allAttemptElapsed = distribution(records.stream()
                .map(RagLoadBenchmarkRunner.LoadRecord::elapsedMs).toList());
        Distribution successOnlyElapsed = distribution(successes.stream()
                .map(RagLoadBenchmarkRunner.LoadRecord::elapsedMs).toList());
        return new VariantStatistics(records.size(), records.size(), successes.size(), errors,
                Map.copyOf(errorCodeCounts), ratio(errors, records.size()), degraded,
                ratio(degraded, successes.size()), successfulEmpty, ratio(successfulEmpty, successes.size()),
                successfulEmpty, ratio(successfulEmpty, successes.size()), allAttemptElapsed,
                allAttemptElapsed, successOnlyElapsed, Map.copyOf(stages), dominant);
    }

    private long reportedServiceMs(Map<String, Long> timings) {
        long serviceMs = timings.getOrDefault("serviceMs", 0L);
        return serviceMs > 0 ? serviceMs : timings.getOrDefault("totalMs", 0L);
    }

    private long requiredElapsed(Map<Integer, Long> values, int concurrency) {
        Long elapsed = values == null ? null : values.get(concurrency);
        if (elapsed == null || elapsed < 1) throw new IllegalArgumentException("缺少并发阶段耗时");
        return elapsed;
    }

    private double throughput(long requests, long elapsedMs) {
        return requests * 1000.0 / elapsedMs;
    }

    private Distribution distribution(List<Long> values) {
        if (values.isEmpty()) return new Distribution(0, 0, 0, 0, 0, 0);
        List<Long> sorted = values.stream().sorted().toList();
        return new Distribution(sorted.size(), sorted.stream().mapToLong(Long::longValue).average().orElse(0),
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted.get(sorted.size() - 1));
    }

    private long percentile(List<Long> values, double percentile) {
        return values.get(Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    public record Distribution(long count, double mean, long p50, long p95, long p99, long max) {}

    /**
     * variant统计；requestCount是attemptCount的兼容别名，emptyResult与successfulEmpty同义，
     * elapsedMs是allAttemptElapsedMs的兼容别名。degraded/empty比率以成功请求为分母。
     */
    public record VariantStatistics(long requestCount, long attemptCount, long successCount,
                                    long errorCount, Map<String, Long> errorCodeCounts, double errorRate,
                                    long degradedCount, double degradedRate, long emptyResultCount,
                                    double emptyResultRate, long successfulEmptyCount,
                                    double successfulEmptyRate, Distribution elapsedMs,
                                    Distribution allAttemptElapsedMs, Distribution successOnlyElapsedMs,
                                    Map<String, Distribution> stageTimingsMs,
                                    String observedDominantLatencyComponent) {}

    /** requestCount/throughputRequestsPerSecond分别是attempt口径计数和吞吐的兼容别名。 */
    public record ConcurrencyStatistics(int concurrency, long requestCount, long attemptCount,
                                        long successCount, long elapsedMs,
                                        double throughputRequestsPerSecond,
                                        double attemptThroughputRequestsPerSecond,
                                        double successThroughputRequestsPerSecond,
                                        Map<String, VariantStatistics> variants) {}
}
