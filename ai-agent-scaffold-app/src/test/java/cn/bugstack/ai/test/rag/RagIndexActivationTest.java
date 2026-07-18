package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import org.junit.Assert;
import org.junit.Test;

/** 索引完成后版本、文档和知识库切换规则测试。 */
public class RagIndexActivationTest {

    @Test
    public void shouldActivateMatchingGenerationWithoutRegression() {
        RagDocumentVersionEntity version = version().processing("docling-1", "chunker-1", "e5-rev").ready();
        RagDocumentEntity document = document().activate("ver-a", 1);
        RagKnowledgeBaseEntity knowledgeBase = knowledgeBase().activateGeneration(1);

        Assert.assertEquals(RagDocumentVersionStatus.READY, version.status());
        Assert.assertEquals(RagDocumentStatus.READY, document.status());
        Assert.assertEquals("ver-a", document.activeVersionId());
        Assert.assertNull(document.targetGeneration());
        Assert.assertEquals(1, knowledgeBase.currentGeneration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMismatchedDocumentGeneration() {
        document().activate("ver-a", 2);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectReadyVersionBeforeProcessing() {
        version().ready();
    }

    private RagDocumentEntity document() {
        return new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                "文档", null, 0, 1L, RagDocumentStatus.PROCESSING, 0);
    }

    private RagDocumentVersionEntity version() {
        return new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", "ver-a", 1, 1,
                "bucket", "key", "文档.md", "a".repeat(64), "text/markdown", 10,
                RagDocumentVersionStatus.QUEUED, null, null, null, 0);
    }

    private RagKnowledgeBaseEntity knowledgeBase() {
        return new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "知识库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                "collection", 0, 0);
    }
}
