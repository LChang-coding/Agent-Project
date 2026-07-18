package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagBenchmarkRunIOTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRoundTripVariantRunsAndRejectDuplicateVariantQuery() throws Exception {
        RagBenchmarkRunIO io = new RagBenchmarkRunIO(new ObjectMapper());
        Path run = temporaryDirectory.resolve("run.jsonl");
        RagBenchmarkRunIO.RunRecord record = new RagBenchmarkRunIO.RunRecord("run-1", "dense", "q1",
                "query-sha", "retrieval-1", List.of("d1", "d2"), 12L, false, List.of(), null,
                Map.of("dense", 8L), Map.of("dense", 2));
        io.append(run, record);

        assertEquals(List.of("d1", "d2"), io.read(run).get("dense").get("q1"));
        io.append(run, record);
        assertThrows(IllegalArgumentException.class, () -> io.read(run));
    }
}
