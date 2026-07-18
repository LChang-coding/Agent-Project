package cn.bugstack.ai.benchmark.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从逐查询原始记录计算可复核的延迟、错误、降级与候选量统计。 */
public final class RagBenchmarkRunStatistics {

    public Map<String, VariantStatistics> aggregate(List<RagBenchmarkRunIO.RunRecord> records) {
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("run记录不能为空");
        Map<String, List<RagBenchmarkRunIO.RunRecord>> grouped = new LinkedHashMap<>();
        records.forEach(record -> grouped.computeIfAbsent(record.variant(), ignored -> new ArrayList<>()).add(record));
        Map<String, VariantStatistics> result = new LinkedHashMap<>();
        grouped.forEach((variant, values) -> result.put(variant, summarize(values)));
        return Map.copyOf(result);
    }

    private VariantStatistics summarize(List<RagBenchmarkRunIO.RunRecord> records) {
        long errors = records.stream().filter(value -> value.errorCode() != null && !value.errorCode().isBlank()).count();
        long degraded = records.stream().filter(RagBenchmarkRunIO.RunRecord::degraded).count();
        long empty = records.stream().filter(value -> value.rankedDocumentIds().isEmpty()).count();
        Map<String, Distribution> stages = new LinkedHashMap<>();
        records.stream().flatMap(value -> value.stageTimingsMs().keySet().stream()).distinct().sorted()
                .forEach(stage -> stages.put(stage, distribution(records.stream()
                        .filter(value -> value.stageTimingsMs().containsKey(stage))
                        .map(value -> value.stageTimingsMs().get(stage)).toList())));
        Map<String, Distribution> candidates = new LinkedHashMap<>();
        records.stream().flatMap(value -> value.candidateCounts().keySet().stream()).distinct().sorted()
                .forEach(name -> candidates.put(name, distribution(records.stream()
                        .filter(value -> value.candidateCounts().containsKey(name))
                        .map(value -> value.candidateCounts().get(name).longValue()).toList())));
        return new VariantStatistics(records.size(), errors, ratio(errors, records.size()), degraded,
                ratio(degraded, records.size()), empty, ratio(empty, records.size()),
                distribution(records.stream().map(RagBenchmarkRunIO.RunRecord::elapsedMs).toList()),
                Map.copyOf(stages), Map.copyOf(candidates));
    }

    private Distribution distribution(List<Long> values) {
        if (values.isEmpty()) return new Distribution(0, 0, 0, 0, 0, 0);
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return new Distribution(sorted.size(), mean, percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), sorted.get(sorted.size() - 1));
    }

    /** nearest-rank 分位数，与报告中的算法标识一致。 */
    private long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    public record Distribution(long count, double mean, long p50, long p95, long p99, long max) {}
    public record VariantStatistics(long requestCount, long errorCount, double errorRate,
                                    long degradedCount, double degradedRate, long emptyResultCount,
                                    double emptyResultRate, Distribution elapsedMs,
                                    Map<String, Distribution> stageTimingsMs,
                                    Map<String, Distribution> candidateCounts) {}
}
