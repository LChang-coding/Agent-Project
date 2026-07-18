package cn.bugstack.ai.benchmark.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagRetrievalScorerTest {

    private final RagRetrievalScorer scorer = new RagRetrievalScorer();

    @Test
    void shouldCalculateGradedAndBinaryMetricsWithoutInflatingMissingRelevantDocuments() {
        RagRetrievalScorer.QueryMetrics metrics = scorer.scoreQuery("q1", Map.of("d1", 2, "d2", 1),
                List.of("noise", "d2", "d1"));

        assertEquals(0D, metrics.recallAt1(), 1e-12);
        assertEquals(1D, metrics.recallAt5(), 1e-12);
        assertEquals(0.5D, metrics.mrrAt10(), 1e-12);
        assertEquals((0.5D + 2D / 3D) / 2D, metrics.mapAt10(), 1e-12);
        assertEquals(0.2D, metrics.precisionAt10(), 1e-12);
        double expectedDcg = 1D / log2(3D) + 3D / log2(4D);
        double expectedIdeal = 3D / log2(2D) + 1D / log2(3D);
        assertEquals(expectedDcg / expectedIdeal, metrics.ndcgAt10(), 1e-12);
        assertEquals(1D, metrics.successAt10(), 1e-12);
        assertEquals(0D, metrics.successAt1(), 1e-12);
        assertEquals(1D, metrics.successAt5(), 1e-12);
    }

    @Test
    void shouldCountMissingRunAsZeroAndRejectDuplicateRankedDocuments() {
        RagRetrievalScorer.AggregateMetrics metrics = scorer.scoreAll(
                Map.of("q1", Map.of("d1", 1), "q2", Map.of("d2", 1)),
                Map.of("q1", List.of("d1")));

        assertEquals(2, metrics.queryCount());
        assertEquals(1, metrics.missingRunCount());
        assertEquals(0.5D, metrics.recallAt10(), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> scorer.scoreQuery("q1", Map.of("d1", 1), List.of("d1", "d1")));
    }

    private double log2(double value) { return Math.log(value) / Math.log(2D); }
}
