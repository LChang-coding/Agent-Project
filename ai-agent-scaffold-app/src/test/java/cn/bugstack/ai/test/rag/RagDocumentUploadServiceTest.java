package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagUploadRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagDocumentUploadService;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RAG 上传编排的鉴权、幂等和对象补偿测试。 */
public class RagDocumentUploadServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldStoreAndRegisterWithoutExposingObjectKey() throws Exception {
        Fixture fixture = fixture();
        when(fixture.registration.register(eq("tenant-a"), any())).thenReturn(true);

        var result = fixture.service.upload(command(markdown(), "admin"));

        Assert.assertEquals("queued", result.status());
        Assert.assertFalse(result.deduplicated());
        verify(fixture.storage).putFile(any(ObjectStorageFileCommandEntity.class));
        verify(fixture.registration).register(eq("tenant-a"), any());
        verify(fixture.storage, never()).deleteObject(any(), any());
    }

    @Test
    public void shouldCompensateStoredObjectWhenDatabaseRegistrationFails() throws Exception {
        Fixture fixture = fixture();
        when(fixture.registration.register(eq("tenant-a"), any()))
                .thenThrow(new IllegalStateException("database down"));

        try {
            fixture.service.upload(command(markdown(), "owner"));
            Assert.fail("数据库失败必须向上返回");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("database down", expected.getMessage());
        }
        verify(fixture.storage).deleteObject("rag-bucket", "stored-object");
    }

    @Test
    public void shouldDeduplicateConcurrentRegistrationAndDeleteLosingObject() throws Exception {
        Fixture fixture = fixture();
        RagIngestJobEntity existing = RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-existing",
                "ver-existing", "task-existing", fixture.idempotencyKey, RagIngestOperation.INGEST, 1, 3);
        when(fixture.repository.findIngestJobByIdempotencyKey("tenant-a", fixture.idempotencyKey))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(fixture.registration.register(eq("tenant-a"), any())).thenReturn(false);

        var result = fixture.service.upload(command(markdown(), "admin"));

        Assert.assertTrue(result.deduplicated());
        Assert.assertEquals("task-existing", result.taskId());
        verify(fixture.storage).deleteObject("rag-bucket", "stored-object");
    }

    @Test
    public void shouldRejectNonAdminBeforeStorageSideEffect() throws Exception {
        Fixture fixture = fixture();
        try {
            fixture.service.upload(command(markdown(), "member"));
            Assert.fail("普通成员不能上传企业知识库");
        } catch (AppException e) {
            Assert.assertEquals("RAG_ADMIN_REQUIRED", e.getCode());
        }
        verify(fixture.storage, never()).putFile(any());
    }

    private Fixture fixture() {
        IRagRepository repository = mock(IRagRepository.class);
        RagUploadRegistrationPort registration = mock(RagUploadRegistrationPort.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-a", "owner", "kb-a", "知识库", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                        "collection", 0, 0)));
        when(storage.ragBucket()).thenReturn("rag-bucket");
        String fileHash = sha256("# knowledge");
        when(storage.putFile(any())).thenReturn(ObjectStorageResultEntity.builder()
                .bucket("rag-bucket").objectKey("stored-object").sha256(fileHash).sizeBytes(11L).build());
        String taskKey = sha256("ingest\ntenant-a\nkb-a\n" + fileHash);
        when(repository.findIngestJobByIdempotencyKey("tenant-a", taskKey)).thenReturn(Optional.empty());
        return new Fixture(repository, registration, storage,
                new RagDocumentUploadService(repository, registration, storage), taskKey);
    }

    private RagDocumentUploadCommand command(Path path, String role) throws Exception {
        return new RagDocumentUploadCommand("tenant-a", "user-a", role, "kb-a",
                new RagUploadFileCandidate(path, Files.size(path), "knowledge.md", "text/markdown"));
    }

    private Path markdown() throws Exception {
        Path path = temporaryFolder.newFile("upload.md").toPath();
        Files.writeString(path, "# knowledge", StandardCharsets.UTF_8);
        return path;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record Fixture(IRagRepository repository, RagUploadRegistrationPort registration,
                           ObjectStorageService storage, RagDocumentUploadService service,
                           String idempotencyKey) {
    }
}
