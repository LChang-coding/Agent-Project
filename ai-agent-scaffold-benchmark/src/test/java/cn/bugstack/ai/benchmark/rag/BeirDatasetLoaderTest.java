package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeirDatasetLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldLoadStrictBeirFilesAndBuildPositiveClosedSubset() throws Exception {
        Path corpus = write("corpus.jsonl", """
                {"_id":"d1","title":"One","text":"alpha"}
                {"_id":"d2","title":"Two","text":"beta"}
                {"_id":"d3","title":"Three","text":"gamma"}
                """);
        Path queries = write("queries.jsonl", """
                {"_id":"q1","text":"alpha query"}
                {"_id":"q2","text":"beta query"}
                {"_id":"train-only","text":"must not enter test split"}
                """);
        Path qrels = write("test.tsv", """
                query-id\tcorpus-id\tscore
                q1\td1\t1
                q2\td2\t2
                """);

        RagBenchmarkDataset dataset = new BeirDatasetLoader(new ObjectMapper())
                .load(corpus, queries, qrels, BeirDatasetLoader.Limits.defaults());
        RagBenchmarkDataset subset = dataset.deterministicSubset(2, 1, 17L);

        assertEquals(3, dataset.documents().size());
        assertEquals(2, dataset.queries().size());
        assertEquals(1, subset.queries().size());
        assertEquals(2, subset.documents().size());
        subset.qrels().values().forEach(values -> values.keySet()
                .forEach(documentId -> assertTrue(subset.documents().containsKey(documentId))));
    }

    @Test
    void shouldRejectQrelThatReferencesMissingDocument() throws Exception {
        Path corpus = write("corpus.jsonl", "{\"_id\":\"d1\",\"text\":\"alpha\"}\n");
        Path queries = write("queries.jsonl", "{\"_id\":\"q1\",\"text\":\"query\"}\n");
        Path qrels = write("test.tsv", "query-id\tcorpus-id\tscore\nq1\tmissing\t1\n");

        assertThrows(IllegalArgumentException.class, () -> new BeirDatasetLoader(new ObjectMapper())
                .load(corpus, queries, qrels, BeirDatasetLoader.Limits.defaults()));
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(name), content.stripIndent(), StandardCharsets.UTF_8);
    }
}
