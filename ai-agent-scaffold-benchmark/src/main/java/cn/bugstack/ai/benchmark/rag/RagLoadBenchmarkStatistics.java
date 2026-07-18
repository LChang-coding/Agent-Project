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
            Map<String, VariantStatistics> variants = new LinkedHashMap<>();
            values.stream().map(RagLoadBenchmarkRunner.LoadRecord::variant).distinct().sorted().forEach(variant ->
                    variants.put(variant, summarize(values.stream()
                            .filter(value -> variant.equals(value.variant())).toList())));
            result.put(concurrency, new ConcurrencyStatistics(concurrency, values.size(), elapsedMs,
                    throughput(values.size(), elapsedMs), Map.copyOf(variants)));
        });
        return Map.copyOf(result);
    }

    private VariantStatistics summarize(List<RagLoadBenchmarkRunner.LoadRecord> records) {
        long errors = records.stream().filter(RagLoadBenchmarkRunner.LoadRecord::failed).count();
        long degraded = records.stream().filter(RagLoadBenchmarkRunner.LoadRecord::degraded).count();
        long empty = records.stream().filter(value -> value.rankedDocumentIds().isEmpty()).count();
        Map<String, Distribution> stages = new LinkedHashMap<>();
        records.stream().flatMap(value -> value.stageTimingsMs().keySet().stream()).distinct().sorted()
                .forEach(stage -> stages.put(stage, distribution(records.stream()
                        .filter(value -> value.stageTimingsMs().containsKey(stage))
                        .map(value -> value.stageTimingsMs().get(stage)).toList())));
        List<Long> clientAndQueue = records.stream().filter(value -> !value.failed())
                .map(value -> Math.max(0L, value.elapsedMs()
                        - value.stageTimingsMs().getOrDefault("totalMs", 0L))).toList();
        if (!clientAndQueue.isEmpty()) stages.put("clientAndQueueMs", distribution(clientAndQueue));
        String dominant = stages.entrySet().stream().filter(entry -> entry.getValue().count() > 0)
                .filter(entry -> !"totalMs".equals(entry.getKey()))
                .max(Comparator.<Map.Entry<String, Distribution>>comparingDouble(entry -> entry.getValue().mean())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse("unavailable");
        return new VariantStatistics(records.size(), errors, ratio(errors, records.size()), degraded,
                ratio(degraded, records.size()), empty, ratio(empty, records.size()),
                distribution(records.stream().map(RagLoadBenchmarkRunner.LoadRecord::elapsedMs).toList()),
                Map.copyOf(stages), dominant);
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
    public record VariantStatistics(long requestCount, long errorCount, double errorRate,
                                    long degradedCount, double degradedRate, long emptyResultCount,
                                    double emptyResultRate, Distribution elapsedMs,
                                    Map<String, Distribution> stageTimingsMs,
                                    String observedDominantLatencyComponent) {}
    public record ConcurrencyStatistics(int concurrency, long requestCount, long elapsedMs,
                                        double throughputRequestsPerSecond,
                                        Map<String, VariantStatistics> variants) {}
}
