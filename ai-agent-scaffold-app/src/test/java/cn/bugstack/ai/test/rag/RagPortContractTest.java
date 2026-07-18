package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * RAG 外部端口的资源与检索隔离边界测试。
 */
public class RagPortContractTest {

    @Test
    public void shouldUseStagedPathInsteadOfWholeDocumentBytes() {
        Path stagedFile = Path.of("work", "..", "work", "job-1.pdf");

        RagDocumentParserPort.ParseCommand command = new RagDocumentParserPort.ParseCommand(
                "tenant-a", "job-1", "version-1", "manual.pdf", "application/pdf",
                stagedFile, 1024L, false);

        Assert.assertEquals(Path.of("work", "job-1.pdf"), command.contentPath());
        Assert.assertEquals(1024L, command.contentLength());
    }

    @Test
    public void shouldRejectEmptyStagedDocument() {
        assertIllegalArgument(() -> new RagDocumentParserPort.ParseCommand(
                "tenant-a", "job-1", "version-1", "manual.pdf", "application/pdf",
                Path.of("work", "job-1.pdf"), 0L, false));
    }

    @Test
    public void shouldRequireActiveGenerationForEveryVectorSearchScope() {
        VectorStorePort.KnowledgeBaseScope scope = new VectorStorePort.KnowledgeBaseScope("kb-1", 3L);
        VectorStorePort.VectorSearchCommand command = new VectorStorePort.VectorSearchCommand(
                Set.of(scope), List.of(0.1F, 0.2F), null, 10);

        Assert.assertEquals(Set.of(scope), command.scopes());
        assertIllegalArgument(() -> new VectorStorePort.KnowledgeBaseScope("kb-1", 0L));
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            Assert.fail("预期拒绝非法端口参数");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }
}
