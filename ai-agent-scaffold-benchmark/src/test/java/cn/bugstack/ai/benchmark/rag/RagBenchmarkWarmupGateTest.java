package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagBenchmarkWarmupGateTest {

    private static final Set<String> VARIANTS = Set.of("dense", "sparse", "hybrid_rrf", "hybrid_rrf_rerank");
    private final RagBenchmarkWarmupGate gate = new RagBenchmarkWarmupGate();

    @Test
    void shouldAcceptCompleteUniqueHealthyWarmup() {
        assertDoesNotThrow(() -> gate.validate(validRecords(10), 10, VARIANTS));
    }

    @Test
    void shouldRejectErrorDegradationAndEmptyRanking() {
        assertGateFailure(replace(validRecords(1), 0, record("dense", "q1", List.of(), true,
                "RAG_BENCHMARK_HTTP_500", 0, 0)));
    }

    @Test
    void shouldRejectDuplicateCombination() {
        List<RagBenchmarkRunIO.RunRecord> records = new ArrayList<>(validRecords(1));
        records.set(1, records.get(0));
        assertGateFailure(records);
    }

    @Test
    void shouldRejectMissingVariant() {
        List<RagBenchmarkRunIO.RunRecord> records = new ArrayList<>(validRecords(1));
        records.remove(records.size() - 1);
        assertGateFailure(records);
    }

    @Test
    void shouldRejectRerankFallbackWithNonEmptyRanking() {
        List<RagBenchmarkRunIO.RunRecord> records = new ArrayList<>(validRecords(1));
        records.set(3, record("hybrid_rrf_rerank", "q1", List.of("doc-1"), false, null, 0, 0));
        assertGateFailure(records);
    }

    private void assertGateFailure(List<RagBenchmarkRunIO.RunRecord> records) {
        RagBenchmarkWarmupGate.WarmupGateException exception = assertThrows(
                RagBenchmarkWarmupGate.WarmupGateException.class, () -> gate.validate(records, 1, VARIANTS));
        assertEquals(RagBenchmarkWarmupGate.ERROR_CODE, exception.code());
    }

    private List<RagBenchmarkRunIO.RunRecord> validRecords(int queryCount) {
        List<RagBenchmarkRunIO.RunRecord> records = new ArrayList<>();
        for (int query = 1; query <= queryCount; query++) {
            String queryId = "q" + query;
            for (String variant : List.of("dense", "sparse", "hybrid_rrf", "hybrid_rrf_rerank")) {
                records.add(record(variant, queryId, List.of("doc-1"), false, null,
                        variant.equals("hybrid_rrf_rerank") ? 10 : 0,
                        variant.equals("hybrid_rrf_rerank") ? 5 : 0));
            }
        }
        return records;
    }

    private List<RagBenchmarkRunIO.RunRecord> replace(List<RagBenchmarkRunIO.RunRecord> records, int index,
                                                       RagBenchmarkRunIO.RunRecord replacement) {
        List<RagBenchmarkRunIO.RunRecord> copy = new ArrayList<>(records);
        copy.set(index, replacement);
        return copy;
    }

    private RagBenchmarkRunIO.RunRecord record(String variant, String queryId, List<String> ranking,
                                                boolean degraded, String errorCode,
                                                int rerankCandidates, long rerankMs) {
        return new RagBenchmarkRunIO.RunRecord("run", variant, queryId, "sha", "retrieval", ranking, 1,
                degraded, degraded ? List.of("fallback") : List.of(), errorCode,
                Map.of("rerankMs", rerankMs), Map.of("rerankCandidateCount", rerankCandidates));
    }
}
