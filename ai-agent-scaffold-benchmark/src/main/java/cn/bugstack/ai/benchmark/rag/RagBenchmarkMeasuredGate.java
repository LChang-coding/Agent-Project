package cn.bugstack.ai.benchmark.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在正式样本写盘前拒绝业务错误、静默降级和无效Rerank，确保run始终是健康前缀。 */
final class RagBenchmarkMeasuredGate {

    static final String ERROR_CODE = "RAG_BENCHMARK_MEASURED_GATE_FAILED";
    private static final String RERANK_VARIANT = "hybrid_rrf_rerank";

    void validate(RagBenchmarkRunIO.RunRecord record) {
        if (record == null) throw new MeasuredGateException(null, "record_missing");
        boolean duplicateRanking = record.rankedDocumentIds().stream().distinct().count()
                != record.rankedDocumentIds().size();
        boolean invalidNumbers = record.elapsedMs() < 0
                || record.stageTimingsMs().values().stream().anyMatch(value -> value == null || value < 0)
                || record.candidateCounts().values().stream().anyMatch(value -> value == null || value < 0);
        boolean invalidRerank = RERANK_VARIANT.equals(record.variant())
                && (positive(record.candidateCounts(), "rerankCandidateCount") < 1
                || positive(record.stageTimingsMs(), "rerankMs") < 1);
        if (blank(record.retrievalId()) || !blank(record.errorCode()) || record.degraded()
                || !record.degradationReasons().isEmpty() || record.rankedDocumentIds().isEmpty()
                || duplicateRanking || invalidNumbers || positive(record.stageTimingsMs(), "totalMs") < 1
                || invalidRerank) {
            throw new MeasuredGateException(record, "sample_unhealthy");
        }
    }

    private long positive(Map<String, ? extends Number> values, String key) {
        Number value = values == null ? null : values.get(key);
        return value == null ? 0 : value.longValue();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static final class MeasuredGateException extends IllegalStateException {
        private final Map<String, Object> sample;

        MeasuredGateException(RagBenchmarkRunIO.RunRecord record, String reason) {
            super(ERROR_CODE + ": " + reason);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("reason", reason);
            if (record != null) {
                values.put("variant", record.variant());
                values.put("queryId", record.queryId());
                values.put("sampleErrorCode", record.errorCode() == null ? "" : record.errorCode());
                values.put("degraded", record.degraded());
                values.put("rankedCount", record.rankedDocumentIds().size());
            }
            sample = Map.copyOf(values);
        }

        String code() {
            return ERROR_CODE;
        }

        Map<String, Object> sample() {
            return sample;
        }
    }
}
