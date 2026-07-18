package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagBenchmarkRunStatisticsTest {

    @Test
    void shouldAggregateLatencyErrorsDegradationAndCandidates() {
        List<RagBenchmarkRunIO.RunRecord> records = List.of(
                record(10, false, null, 4, 20),
                record(20, true, null, 8, 30),
                record(30, false, "TIMEOUT", 0, 0));

        RagBenchmarkRunStatistics.VariantStatistics value =
                new RagBenchmarkRunStatistics().aggregate(records).get("hybrid");

        assertEquals(3, value.requestCount());
        assertEquals(1, value.errorCount());
        assertEquals(1.0 / 3, value.errorRate(), 0.000001);
        assertEquals(1, value.degradedCount());
        assertEquals(1, value.emptyResultCount());
        assertEquals(20, value.elapsedMs().p50());
        assertEquals(30, value.elapsedMs().p95());
        assertEquals(25, value.stageTimingsMs().get("denseMs").mean());
        assertEquals(8, value.candidateCounts().get("denseCandidateCount").p95());
    }

    private RagBenchmarkRunIO.RunRecord record(long elapsed, boolean degraded, String error, int candidates,
                                                long denseMs) {
        return new RagBenchmarkRunIO.RunRecord("run", "hybrid", "q" + elapsed, "hash", null,
                error == null ? List.of("d") : List.of(), elapsed, degraded,
                degraded ? List.of("rerank_unavailable") : List.of(), error,
                error == null ? Map.of("denseMs", denseMs) : Map.of(),
                error == null ? Map.of("denseCandidateCount", candidates) : Map.of());
    }
}
