package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * RAG 持久化编解码严格性测试。
 */
public class RagPersistenceCodecTest {

    private final RagPersistenceCodec codec = new RagPersistenceCodec(new ObjectMapper());

    @Test
    public void shouldRoundTripMetadataAndCheckpoint() {
        Map<String, String> metadata = Map.of("language", "zh-CN", "source", "manual");
        RagIngestCheckpoint checkpoint = new RagIngestCheckpoint(
                RagIngestStage.EMBEDDING, 4, 10, 2, 0);

        Assert.assertEquals(metadata, codec.readMetadata(codec.writeMetadata(metadata)));
        Assert.assertEquals(checkpoint,
                codec.readCheckpoint(codec.writeCheckpoint(checkpoint), "embedding"));
    }

    @Test
    public void shouldRejectUnknownEnumAndStageMismatch() {
        assertIllegalState(() -> codec.enumValue(RagIngestStage.class, "silently_ignore", "摄取阶段"));
        assertIllegalState(() -> codec.readCheckpoint(
                "{\"stage\":\"embedding\",\"processedChunks\":0,\"totalChunks\":1,"
                        + "\"embeddingBatchIndex\":0,\"vectorUpsertIndex\":0}", "indexing"));
    }

    @Test
    public void shouldOnlyDefaultMissingInitialCheckpoint() {
        Assert.assertEquals(RagIngestCheckpoint.initial(), codec.readCheckpoint(null, "received"));
        assertIllegalState(() -> codec.readCheckpoint(null, "parsing"));
    }

    private void assertIllegalState(Runnable action) {
        try {
            action.run();
            Assert.fail("预期拒绝损坏的持久化数据");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }
}
