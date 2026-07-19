package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagBenchmarkMeasuredGateTest {

    private final RagBenchmarkMeasuredGate gate = new RagBenchmarkMeasuredGate();

    @Test
    void shouldAcceptHealthyMeasuredRecord() {
        assertDoesNotThrow(() -> gate.validate(record("hybrid_rrf_rerank", null, false,
                List.of("doc-1"), 1, 1)));
    }

    @Test
    void shouldRejectBusinessErrorBeforeAppend() {
        RagBenchmarkMeasuredGate.MeasuredGateException exception = assertThrows(
                RagBenchmarkMeasuredGate.MeasuredGateException.class,
                () -> gate.validate(record("dense", "RAG_QDRANT_UNAVAILABLE", false,
                        List.of(), 0, 0)));

        assertEquals(RagBenchmarkMeasuredGate.ERROR_CODE, exception.code());
        assertEquals("RAG_QDRANT_UNAVAILABLE", exception.sample().get("sampleErrorCode"));
    }

    @Test
    void shouldRejectDegradedOrEmptyRecord() {
        assertThrows(RagBenchmarkMeasuredGate.MeasuredGateException.class,
                () -> gate.validate(record("dense", null, true, List.of("doc-1"), 0, 0)));
        assertThrows(RagBenchmarkMeasuredGate.MeasuredGateException.class,
                () -> gate.validate(record("dense", null, false, List.of(), 0, 0)));
    }

    @Test
    void shouldRejectRerankFallbackDisguisedAsRanking() {
        assertThrows(RagBenchmarkMeasuredGate.MeasuredGateException.class,
                () -> gate.validate(record("hybrid_rrf_rerank", null, false,
                        List.of("doc-1"), 0, 0)));
    }

    private RagBenchmarkRunIO.RunRecord record(String variant, String errorCode, boolean degraded,
                                                List<String> ranking, int rerankCandidates, long rerankMs) {
        return new RagBenchmarkRunIO.RunRecord("run", variant, "query", "query-sha", "retrieval",
                ranking, 1, degraded, degraded ? List.of("fallback") : List.of(), errorCode,
                Map.of("totalMs", 1L, "rerankMs", rerankMs),
                Map.of("rerankCandidateCount", rerankCandidates));
    }
}
