package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagLoadBenchmarkStatisticsTest {

    @Test
    void shouldKeepFailuresInDenominatorAndSeparateClientOverhead() {
        List<RagLoadBenchmarkRunner.LoadRecord> records = List.of(
                record(1, 0, 30, 10, null, false),
                record(1, 1, 50, 10, null, true),
                record(1, 2, 70, 0, "TIMEOUT", false));

        RagLoadBenchmarkStatistics.ConcurrencyStatistics level = new RagLoadBenchmarkStatistics()
                .aggregate(records, Map.of(1, 160L)).get(1);
        RagLoadBenchmarkStatistics.VariantStatistics variant = level.variants().get("hybrid");

        assertEquals(3, level.requestCount());
        assertEquals(18.75, level.throughputRequestsPerSecond(), 0.000001);
        assertEquals(1, variant.errorCount());
        assertEquals(1.0 / 3, variant.errorRate(), 0.000001);
        assertEquals(1, variant.degradedCount());
        assertEquals(1, variant.emptyResultCount());
        assertEquals(40, variant.stageTimingsMs().get("outsideReportedServiceMs").p95());
        assertEquals("outsideReportedServiceMs", variant.observedDominantLatencyComponent());
    }

    @Test
    void shouldPreferCompleteServiceBoundaryOverRetrievalTotal() {
        RagLoadBenchmarkRunner.LoadRecord record = new RagLoadBenchmarkRunner.LoadRecord(
                "run", 1, 0, "worker", "dense", "query", "hash", null, List.of("doc"),
                30, false, List.of(), null, Map.of("totalMs", 10L, "serviceMs", 24L), Map.of());

        RagLoadBenchmarkStatistics.VariantStatistics variant = new RagLoadBenchmarkStatistics()
                .aggregate(List.of(record), Map.of(1, 30L)).get(1).variants().get("dense");

        assertEquals(6, variant.stageTimingsMs().get("outsideReportedServiceMs").p95());
    }

    private RagLoadBenchmarkRunner.LoadRecord record(int concurrency, long sequence, long elapsed,
                                                     long total, String error, boolean degraded) {
        return new RagLoadBenchmarkRunner.LoadRecord("run", concurrency, sequence, "worker", "hybrid",
                "query-" + sequence, "hash", null, error == null ? List.of("doc") : List.of(), elapsed,
                degraded, degraded ? List.of("rerank_unavailable") : List.of(), error,
                error == null ? Map.of("embeddingMs", 5L, "totalMs", total) : Map.of(), Map.of());
    }
}
