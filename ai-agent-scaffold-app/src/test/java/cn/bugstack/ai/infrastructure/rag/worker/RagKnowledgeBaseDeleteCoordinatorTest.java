package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagDocumentDeletionService;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 知识库删除协调器的逐文档等待与空库收口测试。 */
public class RagKnowledgeBaseDeleteCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    public void shouldReleaseLeaseAsWaitingAfterRegisteringChildDelete() {
        Fixture fixture = fixture(1);
        RagKnowledgeBaseDeleteTaskEntity pending = pending(1);
        RagKnowledgeBaseDeleteTaskEntity running = pending.claim("worker-a", 1L, NOW, Duration.ofMinutes(3));
        RagKnowledgeBaseDeleteTaskEntity deleting = running.advance("worker-a", 1L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                        1, 0, null));
        Mockito.when(fixture.deletionRepository.findByTaskId("tenant-a", "task-a"))
                .thenReturn(Optional.of(pending), Optional.of(running), Optional.of(running),
                        Optional.of(deleting), Optional.of(deleting), Optional.of(deleting));
        Mockito.when(fixture.deletionRepository.claim(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(Optional.of(running));
        Mockito.when(fixture.deletionRepository.heartbeat(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(fixture.deletionRepository.updateClaimed(Mockito.anyString(), Mockito.any(),
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.any())).thenReturn(1);
        RagDocumentEntity ready = document(RagDocumentStatus.READY, 2L);
        Mockito.when(fixture.repository.listDocuments("tenant-a", "kb-a")).thenReturn(List.of(ready));
        Mockito.when(fixture.documentDeletionService.ensureCascadeDeletion("tenant-a", "kb-a", "doc-a"))
                .thenReturn(RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-a",
                        "child-a", "b".repeat(64), RagIngestOperation.DELETE, 1L, 3));
        Mockito.when(fixture.repository.findDocument("tenant-a", "doc-a"))
                .thenReturn(Optional.of(document(RagDocumentStatus.DELETING, 3L)));

        Assert.assertTrue(fixture.coordinator.execute("tenant-a", "task-a", "worker-a"));

        Mockito.verify(fixture.deletionRepository).updateClaimed(Mockito.eq("tenant-a"),
                Mockito.argThat(task -> task.status() == RagKnowledgeBaseDeleteStatus.WAITING
                        && "doc-a".equals(task.checkpoint().currentDocumentId())),
                Mockito.eq(2L), Mockito.eq("worker-a"), Mockito.eq(1L), Mockito.any());
        Mockito.verify(fixture.deletionRepository, Mockito.never()).completeClaimed(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void shouldFinalizeEmptyKnowledgeBaseInSameExecution() {
        Fixture fixture = fixture(0);
        RagKnowledgeBaseDeleteTaskEntity pending = pending(0);
        RagKnowledgeBaseDeleteTaskEntity running = pending.claim("worker-a", 1L, NOW, Duration.ofMinutes(3));
        RagKnowledgeBaseDeleteTaskEntity deleting = running.advance("worker-a", 1L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                        0, 0, null));
        RagKnowledgeBaseDeleteTaskEntity verifying = deleting.advance("worker-a", 1L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.VERIFYING,
                        0, 0, null));
        Mockito.when(fixture.deletionRepository.findByTaskId("tenant-a", "task-a"))
                .thenReturn(Optional.of(pending), Optional.of(running), Optional.of(running),
                        Optional.of(deleting), Optional.of(verifying), Optional.of(verifying),
                        Optional.of(verifying));
        Mockito.when(fixture.deletionRepository.claim(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(Optional.of(running));
        Mockito.when(fixture.deletionRepository.heartbeat(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(fixture.deletionRepository.updateClaimed(Mockito.anyString(), Mockito.any(),
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.any())).thenReturn(1);
        Mockito.when(fixture.repository.listDocuments("tenant-a", "kb-a")).thenReturn(List.of());

        Assert.assertTrue(fixture.coordinator.execute("tenant-a", "task-a", "worker-a"));

        Mockito.verify(fixture.deletionRepository).completeClaimed(
                "tenant-a", "task-a", 3L, "worker-a", 1L, NOW);
        Mockito.verifyNoInteractions(fixture.documentDeletionService);
    }

    private Fixture fixture(int documents) {
        RagKnowledgeBaseDeletionRepository deletionRepository = Mockito.mock(
                RagKnowledgeBaseDeletionRepository.class);
        IRagRepository repository = Mockito.mock(IRagRepository.class);
        RagDocumentDeletionService documentDeletionService = Mockito.mock(RagDocumentDeletionService.class);
        RagProperties properties = new RagProperties();
        properties.getWorker().setPollDelayMs(2000L);
        properties.getWorker().setLeaseDurationMs(180000L);
        return new Fixture(deletionRepository, repository, documentDeletionService,
                new RagKnowledgeBaseDeleteCoordinator(deletionRepository, repository,
                        documentDeletionService, properties, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private RagKnowledgeBaseDeleteTaskEntity pending(int documents) {
        return RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "owner-a", "task-a", "a".repeat(64), documents, 5);
    }

    private RagDocumentEntity document(RagDocumentStatus status, long revision) {
        return new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT,
                "kb-a", "doc-a", "document.md", "ver-a", 1L, null, status, revision);
    }

    private record Fixture(RagKnowledgeBaseDeletionRepository deletionRepository,
                           IRagRepository repository,
                           RagDocumentDeletionService documentDeletionService,
                           RagKnowledgeBaseDeleteCoordinator coordinator) {
    }
}
