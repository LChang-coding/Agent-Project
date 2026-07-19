package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagDocumentDeletionRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagDocumentDeletionService;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档删除受理的鉴权、CAS、全版本墓碑与幂等测试。 */
public class RagDocumentDeletionServiceTest {

    @Test
    public void shouldRegisterOneDeleteTaskForAllVersions() {
        Fixture fixture = fixture();
        when(fixture.registrationPort.register(any(), any())).thenReturn(true);

        RagIngestJobEntity task = fixture.service.deleteDocument(
                "tenant-a", "owner-a", "owner", "kb-a", "doc-a", 7L);

        Assert.assertEquals(RagIngestOperation.DELETE, task.operation());
        ArgumentCaptor<RagDocumentDeletionRegistration> captor =
                ArgumentCaptor.forClass(RagDocumentDeletionRegistration.class);
        verify(fixture.registrationPort).register(org.mockito.ArgumentMatchers.eq("tenant-a"), captor.capture());
        Assert.assertEquals(RagDocumentStatus.DELETING, captor.getValue().document().status());
        Assert.assertEquals(2, captor.getValue().versions().size());
        Assert.assertTrue(captor.getValue().versions().stream()
                .allMatch(version -> version.status() == RagDocumentVersionStatus.DELETING));
        Assert.assertEquals("ver-active", captor.getValue().job().versionId());
    }

    @Test
    public void shouldReturnExistingDeleteTaskWithoutRegisteringAgain() {
        Fixture fixture = fixture();
        RagIngestJobEntity existing = RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a",
                "ver-active", "task-existing", deletionKey(), RagIngestOperation.DELETE, 3L, 3);
        when(fixture.repository.findIngestJobByIdempotencyKey("tenant-a", deletionKey()))
                .thenReturn(Optional.of(existing));
        when(fixture.repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(
                new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                        "document.md", "ver-active", 3L, null, RagDocumentStatus.DELETING, 8L)));

        RagIngestJobEntity result = fixture.service.deleteDocument(
                "tenant-a", "owner-a", "owner", "kb-a", "doc-a", 0L);

        Assert.assertSame(existing, result);
        verify(fixture.registrationPort, never()).register(any(), any());
    }

    @Test
    public void shouldRejectStaleRevisionAndMemberRole() {
        Fixture fixture = fixture();
        AppException stale = Assert.assertThrows(AppException.class, () -> fixture.service.deleteDocument(
                "tenant-a", "owner-a", "owner", "kb-a", "doc-a", 6L));
        Assert.assertEquals("RAG_DOCUMENT_REVISION_CONFLICT", stale.getCode());

        AppException forbidden = Assert.assertThrows(AppException.class, () -> fixture.service.deleteDocument(
                "tenant-a", "member-a", "member", "kb-a", "doc-a", 7L));
        Assert.assertEquals("RAG_ADMIN_REQUIRED", forbidden.getCode());
    }

    private Fixture fixture() {
        IRagRepository repository = mock(IRagRepository.class);
        RagDocumentDeletionRegistrationPort registrationPort = mock(RagDocumentDeletionRegistrationPort.class);
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "知识库", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null,
                        768, "collection", 3L, 1L)));
        when(repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(
                new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                        "document.md", "ver-active", 3L, null, RagDocumentStatus.READY, 7L)));
        when(repository.findIngestJobByIdempotencyKey("tenant-a", deletionKey())).thenReturn(Optional.empty());
        when(repository.listDocumentVersions("tenant-a", "doc-a")).thenReturn(List.of(
                version("ver-old", 1, RagDocumentVersionStatus.SUPERSEDED, 4L),
                version("ver-active", 2, RagDocumentVersionStatus.READY, 5L)));
        return new Fixture(repository, registrationPort,
                new RagDocumentDeletionService(repository, registrationPort,
                        new RagKnowledgeBaseAuthorizationService()));
    }

    private RagDocumentVersionEntity version(String id, int number, RagDocumentVersionStatus status,
                                             long revision) {
        return new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", id, number, 3L,
                "rag", "source/" + id, null, null, id + ".md", "a".repeat(64),
                "text/markdown", 10L, status, null, null, null, revision);
    }

    private String deletionKey() {
        return "69f30cd19baa73f8a97ebd807d2051fe9c57f7437884309c420edd0a83d09ff8";
    }

    private record Fixture(IRagRepository repository,
                           RagDocumentDeletionRegistrationPort registrationPort,
                           RagDocumentDeletionService service) {
    }
}
