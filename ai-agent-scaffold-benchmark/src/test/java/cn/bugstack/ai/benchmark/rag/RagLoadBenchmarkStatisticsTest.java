package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagLoadBenchmarkStatisticsTest {

    @Test
    void shouldSeparateAttemptsSuccessesAndClassifyMixedSamples() {
        List<RagLoadBenchmarkRunner.LoadRecord> records = List.of(
                record(1, 0, 30, 10, null, false, List.of("doc"), 5),
                record(1, 1, 50, 10, null, true, List.of("doc"), 7),
                record(1, 2, 40, 10, null, false, List.of(), 6),
                record(1, 3, 70, 0, "TIMEOUT", false, List.of(), 999),
                record(1, 4, 90, 0, "HTTP_500", false, List.of(), 998));

        RagLoadBenchmarkStatistics.ConcurrencyStatistics level = new RagLoadBenchmarkStatistics()
                .aggregate(records, Map.of(1, 200L)).get(1);
        RagLoadBenchmarkStatistics.VariantStatistics variant = level.variants().get("hybrid");

        assertEquals(5, level.requestCount());
        assertEquals(5, level.attemptCount());
        assertEquals(3, level.successCount());
        assertEquals(25.0, level.throughputRequestsPerSecond(), 0.000001);
        assertEquals(25.0, level.attemptThroughputRequestsPerSecond(), 0.000001);
        assertEquals(15.0, level.successThroughputRequestsPerSecond(), 0.000001);
        assertEquals(5, variant.requestCount());
        assertEquals(5, variant.attemptCount());
        assertEquals(3, variant.successCount());
        assertEquals(2, variant.errorCount());
        assertEquals(Map.of("HTTP_500", 1L, "TIMEOUT", 1L), variant.errorCodeCounts());
        assertEquals(2.0 / 5, variant.errorRate(), 0.000001);
        assertEquals(1, variant.degradedCount());
        assertEquals(1.0 / 3, variant.degradedRate(), 0.000001);
        assertEquals(1, variant.emptyResultCount());
        assertEquals(1.0 / 3, variant.emptyResultRate(), 0.000001);
        assertEquals(1, variant.successfulEmptyCount());
        assertEquals(1.0 / 3, variant.successfulEmptyRate(), 0.000001);
        assertEquals(5, variant.elapsedMs().count());
        assertEquals(50, variant.allAttemptElapsedMs().p50());
        assertEquals(90, variant.allAttemptElapsedMs().p95());
        assertEquals(3, variant.successOnlyElapsedMs().count());
        assertEquals(40, variant.successOnlyElapsedMs().p50());
        assertEquals(50, variant.successOnlyElapsedMs().p95());
        assertEquals(3, variant.stageTimingsMs().get("embeddingMs").count());
        assertEquals(7, variant.stageTimingsMs().get("embeddingMs").max());
        assertEquals(40, variant.stageTimingsMs().get("outsideReportedServiceMs").p95());
        assertEquals("outsideReportedServiceMs", variant.observedDominantLatencyComponent());
    }

    @Test
    void shouldPreferCompleteServiceBoundaryOverRetrievalTotal() {
        RagLoadBenchmarkRunner.LoadRecord record = new RagLoadBenchmarkRunner.LoadRecord(
                "run", 1, 0, "worker", "dense", "query", "hash", null, List.of("doc"),
                30, false, List.of(), null, Map.of("totalMs", 10L, "serviceMs", 24L), Map.of(),
                "2026-07-20T00:00:00Z", "2026-07-20T00:00:00.030Z", 200, 128);

        RagLoadBenchmarkStatistics.VariantStatistics variant = new RagLoadBenchmarkStatistics()
                .aggregate(List.of(record), Map.of(1, 30L)).get(1).variants().get("dense");

        assertEquals(6, variant.stageTimingsMs().get("outsideReportedServiceMs").p95());
    }

    private RagLoadBenchmarkRunner.LoadRecord record(int concurrency, long sequence, long elapsed, long total,
                                                     String error, boolean degraded, List<String> documents,
                                                     long embeddingMs) {
        return new RagLoadBenchmarkRunner.LoadRecord("run", concurrency, sequence, "worker", "hybrid",
                "query-" + sequence, "hash", null, documents, elapsed,
                degraded, degraded ? List.of("rerank_unavailable") : List.of(), error,
                Map.of("embeddingMs", embeddingMs, "totalMs", total), Map.of(),
                "2026-07-20T00:00:00Z", "2026-07-20T00:00:00.100Z",
                error == null ? 200 : 500, 128);
    }
}
