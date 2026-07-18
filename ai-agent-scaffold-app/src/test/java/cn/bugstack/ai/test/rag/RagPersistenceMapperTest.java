package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * RAG 领域对象与数据库 PO 的无损核心字段映射测试。
 */
public class RagPersistenceMapperTest {

    private final RagPersistenceMapper mapper =
            new RagPersistenceMapper(new RagPersistenceCodec(new ObjectMapper()));

    @Test
    public void shouldRoundTripDocumentVersionChunkAndTask() {
        RagDocumentVersionEntity version = new RagDocumentVersionEntity(
                "tenant-a", "kb-1", "doc-1", "version-1", 2, 3L,
                "rag-source", "tenant-a/doc-1/v2.pdf", "manual.pdf", "a".repeat(64),
                "application/pdf", 4096L, RagDocumentVersionStatus.PROCESSING,
                "docling-2", "chunker-1", "e5-v1", 4L);
        RagChunkEntity chunk = new RagChunkEntity("tenant-a", "owner-1", RagVisibility.TENANT,
                "kb-1", "doc-1", "version-1", 2, 3L, "chunk-1", 0,
                null, null, "chunk-2", "正文", 2, 1, "标题", "b".repeat(64),
                "point-1", Map.of("language", "zh-CN"));
        RagIngestJobEntity task = RagIngestJobEntity.pending("tenant-a", "kb-1", "doc-1",
                "version-1", "task-1", "c".repeat(64), RagIngestOperation.INGEST, 3L, 3);

        Assert.assertEquals(version, mapper.toDocumentVersion(mapper.toDocumentVersionPo(version)));
        Assert.assertEquals(chunk, mapper.toChunk(mapper.toChunkPo(chunk)));
        Assert.assertEquals(task, mapper.toIngestJob(mapper.toIngestTaskPo(task)));
    }

    @Test
    public void shouldNormalizeLegacyDocumentStatusesWithoutAcceptingUnknownValues() {
        RagDocumentPO legacy = RagDocumentPO.builder().tenantId("tenant-a").ownerUserId("owner-1")
                .visibility("tenant_public").knowledgeBaseId("kb-1").documentId("doc-1")
                .fileName("manual.md").activeGeneration(1L).status("indexed").revision(1L).build();

        RagDocumentEntity document = mapper.toDocument(legacy);

        Assert.assertEquals(RagDocumentStatus.READY, document.status());
        legacy.setStatus("mystery");
        try {
            mapper.toDocument(legacy);
            Assert.fail("预期拒绝未知文档状态");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("未知值"));
        }
    }
}
