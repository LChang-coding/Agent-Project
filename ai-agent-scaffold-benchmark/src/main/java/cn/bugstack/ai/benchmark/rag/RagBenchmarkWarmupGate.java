package cn.bugstack.ai.benchmark.rag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在正式评测开始前验证预热样本完整、唯一且没有静默降级。 */
final class RagBenchmarkWarmupGate {

    static final String ERROR_CODE = "RAG_BENCHMARK_WARMUP_GATE_FAILED";
    private static final String RERANK_VARIANT = "hybrid_rrf_rerank";

    void validate(List<RagBenchmarkRunIO.RunRecord> records, int expectedQueries, Set<String> variants) {
        if (expectedQueries < 1 || variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("预热门禁参数非法");
        }
        List<RagBenchmarkRunIO.RunRecord> safeRecords = records == null ? List.of() : records;
        Set<String> expectedVariants = Set.copyOf(variants);
        Map<String, Integer> variantCounts = new HashMap<>();
        Set<String> combinations = new HashSet<>();
        int unexpectedVariants = 0;
        int duplicates = 0;
        int errors = 0;
        int degraded = 0;
        int emptyRankings = 0;
        int invalidRerank = 0;

        for (RagBenchmarkRunIO.RunRecord record : safeRecords) {
            if (record == null || !expectedVariants.contains(record.variant())) {
                unexpectedVariants++;
                continue;
            }
            variantCounts.merge(record.variant(), 1, Integer::sum);
            if (!combinations.add(record.variant() + "\u0000" + record.queryId())) duplicates++;
            if (record.errorCode() != null && !record.errorCode().isBlank()) errors++;
            if (record.degraded()) degraded++;
            if (record.rankedDocumentIds().isEmpty()) emptyRankings++;
            if (RERANK_VARIANT.equals(record.variant())
                    && (positive(record.candidateCounts(), "rerankCandidateCount") < 1
                    || positive(record.stageTimingsMs(), "rerankMs") < 1)) {
                invalidRerank++;
            }
        }

        int expectedRecords = Math.multiplyExact(expectedQueries, expectedVariants.size());
        boolean balanced = expectedVariants.stream()
                .allMatch(variant -> variantCounts.getOrDefault(variant, 0) == expectedQueries);
        if (safeRecords.size() != expectedRecords || combinations.size() != expectedRecords || !balanced
                || unexpectedVariants > 0 || duplicates > 0 || errors > 0 || degraded > 0
                || emptyRankings > 0 || invalidRerank > 0) {
            throw new WarmupGateException("expected=" + expectedRecords + ", actual=" + safeRecords.size()
                    + ", unique=" + combinations.size() + ", balanced=" + balanced
                    + ", unexpectedVariants=" + unexpectedVariants + ", duplicates=" + duplicates
                    + ", errors=" + errors + ", degraded=" + degraded + ", empty=" + emptyRankings
                    + ", invalidRerank=" + invalidRerank);
        }
    }

    private long positive(Map<String, ? extends Number> values, String key) {
        Number value = values == null ? null : values.get(key);
        return value == null ? 0 : value.longValue();
    }

    static final class WarmupGateException extends IllegalStateException {
        WarmupGateException(String summary) { super(ERROR_CODE + ": " + summary); }
        String code() { return ERROR_CODE; }
    }
}
