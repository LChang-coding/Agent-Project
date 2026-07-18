package cn.bugstack.ai.benchmark.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 无模型依赖的确定性检索指标实现。 */
public final class RagRetrievalScorer {

    public AggregateMetrics scoreAll(Map<String, Map<String, Integer>> qrels,
                                     Map<String, List<String>> runs) {
        if (qrels == null || qrels.isEmpty()) throw new IllegalArgumentException("qrels不能为空");
        Map<String, List<String>> safeRuns = runs == null ? Map.of() : runs;
        for (String queryId : safeRuns.keySet()) {
            if (!qrels.containsKey(queryId)) throw new IllegalArgumentException("run包含未知查询: " + queryId);
        }
        List<QueryMetrics> values = new ArrayList<>();
        int missing = 0;
        for (Map.Entry<String, Map<String, Integer>> entry : qrels.entrySet()) {
            List<String> ranking = safeRuns.get(entry.getKey());
            if (ranking == null) {
                ranking = List.of();
                missing++;
            }
            values.add(scoreQuery(entry.getKey(), entry.getValue(), ranking));
        }
        return new AggregateMetrics(values.size(), missing,
                average(values, QueryMetrics::recallAt1), average(values, QueryMetrics::recallAt5),
                average(values, QueryMetrics::recallAt10), average(values, QueryMetrics::mrrAt10),
                average(values, QueryMetrics::ndcgAt10), average(values, QueryMetrics::mapAt10),
                average(values, QueryMetrics::precisionAt10), average(values, QueryMetrics::successAt1),
                average(values, QueryMetrics::successAt5), average(values, QueryMetrics::successAt10),
                List.copyOf(values));
    }

    public QueryMetrics scoreQuery(String queryId, Map<String, Integer> qrels, List<String> ranking) {
        if (queryId == null || queryId.isBlank() || qrels == null || qrels.isEmpty()) {
            throw new IllegalArgumentException("查询ID和qrels不能为空");
        }
        Set<String> positives = new LinkedHashSet<>();
        qrels.forEach((documentId, score) -> {
            if (score == null || score < 0) throw new IllegalArgumentException("相关性分数非法");
            if (score > 0) positives.add(documentId);
        });
        if (positives.isEmpty()) throw new IllegalArgumentException("评测查询必须至少有一个正例");
        List<String> safeRanking = ranking == null ? List.of() : List.copyOf(ranking);
        if (new LinkedHashSet<>(safeRanking).size() != safeRanking.size()) {
            throw new IllegalArgumentException("run中同一查询不能包含重复文档");
        }
        return new QueryMetrics(queryId, recallAt(positives, safeRanking, 1),
                recallAt(positives, safeRanking, 5), recallAt(positives, safeRanking, 10),
                reciprocalRank(positives, safeRanking, 10), ndcgAt(qrels, safeRanking, 10),
                averagePrecisionAt(positives, safeRanking, 10), precisionAt(positives, safeRanking, 10),
                successAt(positives, safeRanking, 1), successAt(positives, safeRanking, 5),
                successAt(positives, safeRanking, 10));
    }

    private double recallAt(Set<String> positives, List<String> ranking, int cutoff) {
        return hitsAt(positives, ranking, cutoff) / (double) positives.size();
    }

    private double reciprocalRank(Set<String> positives, List<String> ranking, int cutoff) {
        for (int index = 0; index < Math.min(cutoff, ranking.size()); index++) {
            if (positives.contains(ranking.get(index))) return 1D / (index + 1D);
        }
        return 0D;
    }

    private double ndcgAt(Map<String, Integer> qrels, List<String> ranking, int cutoff) {
        double dcg = 0D;
        for (int index = 0; index < Math.min(cutoff, ranking.size()); index++) {
            dcg += gain(qrels.getOrDefault(ranking.get(index), 0)) / log2(index + 2D);
        }
        List<Integer> ideal = qrels.values().stream().filter(value -> value > 0)
                .sorted(java.util.Comparator.reverseOrder()).limit(cutoff).toList();
        double idcg = 0D;
        for (int index = 0; index < ideal.size(); index++) idcg += gain(ideal.get(index)) / log2(index + 2D);
        return idcg == 0D ? 0D : dcg / idcg;
    }

    /** MAP@10 以该查询全部正例数为分母，未在前10召回的正例会被惩罚。 */
    private double averagePrecisionAt(Set<String> positives, List<String> ranking, int cutoff) {
        int hits = 0;
        double precisionSum = 0D;
        for (int index = 0; index < Math.min(cutoff, ranking.size()); index++) {
            if (positives.contains(ranking.get(index))) {
                hits++;
                precisionSum += hits / (double) (index + 1);
            }
        }
        return precisionSum / positives.size();
    }

    private double successAt(Set<String> positives, List<String> ranking, int cutoff) {
        return hitsAt(positives, ranking, cutoff) > 0 ? 1D : 0D;
    }

    private double precisionAt(Set<String> positives, List<String> ranking, int cutoff) {
        return hitsAt(positives, ranking, cutoff) / (double) cutoff;
    }

    private int hitsAt(Set<String> positives, List<String> ranking, int cutoff) {
        int hits = 0;
        for (int index = 0; index < Math.min(cutoff, ranking.size()); index++) {
            if (positives.contains(ranking.get(index))) hits++;
        }
        return hits;
    }

    private double gain(int relevance) { return Math.pow(2D, relevance) - 1D; }
    private double log2(double value) { return Math.log(value) / Math.log(2D); }
    private double average(List<QueryMetrics> values, Metric metric) {
        return values.stream().mapToDouble(metric::value).average().orElse(0D);
    }

    @FunctionalInterface
    private interface Metric { double value(QueryMetrics metrics); }

    public record QueryMetrics(String queryId, double recallAt1, double recallAt5, double recallAt10,
                               double mrrAt10, double ndcgAt10, double mapAt10, double precisionAt10,
                               double successAt1, double successAt5, double successAt10) {}

    public record AggregateMetrics(int queryCount, int missingRunCount,
                                   double recallAt1, double recallAt5, double recallAt10,
                                   double mrrAt10, double ndcgAt10, double mapAt10, double precisionAt10,
                                   double successAt1, double successAt5, double successAt10,
                                   List<QueryMetrics> queries) {
        public Map<String, Double> summary() {
            Map<String, Double> result = new LinkedHashMap<>();
            result.put("Recall@1", recallAt1);
            result.put("Recall@5", recallAt5);
            result.put("Recall@10", recallAt10);
            result.put("MRR@10", mrrAt10);
            result.put("nDCG@10", ndcgAt10);
            result.put("MAP@10", mapAt10);
            result.put("Precision@10", precisionAt10);
            result.put("Success@1", successAt1);
            result.put("Success@5", successAt5);
            result.put("Success@10", successAt10);
            return Map.copyOf(result);
        }
    }
}
