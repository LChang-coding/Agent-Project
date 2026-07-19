package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort;
import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagLease;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RAG 摄取 Worker 状态机、副作用屏障和本地工作目录测试。 */
public class RagIngestWorkerTest {

    @Test
    public void shouldCompleteMarkdownIngestThroughAtomicActivation() throws Exception {
        Fixture fixture = new Fixture();

        Assert.assertTrue(fixture.worker.execute(Fixture.TENANT, Fixture.JOB_ID, Fixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.COMPLETED, fixture.job.get().status());
        Assert.assertEquals(RagDocumentVersionStatus.READY, fixture.version.get().status());
        Assert.assertEquals(RagDocumentStatus.READY, fixture.document.get().status());
        Assert.assertEquals(Fixture.VERSION_ID, fixture.document.get().activeVersionId());
        Assert.assertEquals(1, fixture.vectorPoints.size());
        verify(fixture.repository, times(1)).completeClaimedIngestJob(
                anyString(), any(), anyLong(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.argThat((RagIndexActivation activation) -> activation.chunkCount() == 1
                        && activation.characterCount() == new String(Fixture.MARKDOWN, StandardCharsets.UTF_8)
                        .codePointCount(0, new String(Fixture.MARKDOWN, StandardCharsets.UTF_8).length())
                        && activation.parsedObjectKey().endsWith("/parsed/normalized.md")), any());
        verify(fixture.objectStorageService).putFile(any());
        verify(fixture.vectorStore, never()).deleteVersion(anyString(), anyString());
        Assert.assertNotNull(fixture.downloadedTarget.get());
        Assert.assertFalse(Files.exists(fixture.downloadedTarget.get()));
    }

    @Test
    public void shouldStopBeforeEmbeddingWhenDatabaseCancellationAppears() throws Exception {
        Fixture fixture = new Fixture();
        fixture.cancelAtHeartbeat = 6;

        Assert.assertTrue(fixture.worker.execute(Fixture.TENANT, Fixture.JOB_ID, Fixture.OWNER));

        verify(fixture.embedding, never()).embed(any());
        verify(fixture.vectorStore, never()).upsert(anyString(), anyString(), any());
        verify(fixture.vectorStore, times(1)).deleteVersion(Fixture.TENANT, Fixture.VERSION_ID);
        verify(fixture.objectStorageService).deleteObject(eq("rag-bucket"),
                eq(RagObjectStorageScope.parsedObjectKey(Fixture.TENANT, Fixture.KB_ID,
                        Fixture.DOCUMENT_ID, Fixture.VERSION_ID)));
        verify(fixture.repository, times(1)).cancelClaimedIngestJob(
                anyString(), any(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
        Assert.assertEquals(RagIngestJobStatus.CANCELLED, fixture.job.get().status());
        Assert.assertTrue(fixture.chunks.isEmpty());
        Assert.assertFalse(Files.exists(fixture.downloadedTarget.get()));
    }

    @Test
    public void shouldNotActivateAndShouldCleanTerminallyWhenQdrantCountMismatches() {
        Fixture fixture = new Fixture();
        fixture.vectorCountOffset = 1;

        Assert.assertTrue(fixture.worker.execute(Fixture.TENANT, Fixture.JOB_ID, Fixture.OWNER));

        verify(fixture.repository, never()).completeClaimedIngestJob(
                anyString(), any(), anyLong(), anyString(), anyLong(), any(), any());
        verify(fixture.vectorStore, times(1)).deleteVersion(Fixture.TENANT, Fixture.VERSION_ID);
        verify(fixture.repository, times(1)).failClaimedIngestJob(
                anyString(), any(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
        Assert.assertEquals(RagIngestJobStatus.FAILED, fixture.job.get().status());
        Assert.assertEquals("RAG_INGEST_INDEX_COUNT_MISMATCH", fixture.job.get().errorCode());
        Assert.assertTrue(fixture.chunks.isEmpty());
        Assert.assertTrue(fixture.vectorPoints.isEmpty());
    }

    @Test
    public void shouldStopAllSideEffectsWhenHeartbeatRevealsNewFence() {
        Fixture fixture = new Fixture();
        fixture.takeOverAtHeartbeat = 1;

        Assert.assertTrue(fixture.worker.execute(Fixture.TENANT, Fixture.JOB_ID, Fixture.OWNER));

        verify(fixture.objectStorageService, never()).downloadToFile(any());
        verify(fixture.parser, never()).parse(any());
        verify(fixture.embedding, never()).embed(any());
        verify(fixture.vectorStore, never()).upsert(anyString(), anyString(), any());
        verify(fixture.vectorStore, never()).deleteVersion(anyString(), anyString());
        verify(fixture.repository, never()).completeClaimedIngestJob(
                anyString(), any(), anyLong(), anyString(), anyLong(), any(), any());
        Assert.assertEquals("new-worker", fixture.job.get().lease().owner());
        Assert.assertEquals(2L, fixture.job.get().fencingToken());
    }

    @Test
    public void shouldDeleteAllVersionsSourceParsedObjectsAndCompleteAtomically() {
        DeleteFixture fixture = new DeleteFixture(false);

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.COMPLETED, fixture.job.get().status());
        Assert.assertEquals(RagDocumentStatus.DELETED, fixture.document.get().status());
        Assert.assertTrue(fixture.versions.get().stream()
                .allMatch(version -> version.status() == RagDocumentVersionStatus.DELETED));
        Assert.assertEquals(Set.of("version-1", "version-2"), fixture.deletedVectors);
        Assert.assertEquals(Set.of(
                DeleteFixture.key("version-1", "source-v1.md"),
                DeleteFixture.key("version-1", "parsed-v1.json"),
                DeleteFixture.key("version-2", "source-v2.md")), fixture.deletedObjects);
        verify(fixture.repository, times(2)).purgeChunks(eq(DeleteFixture.TENANT), anyString());
        verify(fixture.repository, times(2)).countAllChunks(eq(DeleteFixture.TENANT), anyString());
        verify(fixture.repository).completeClaimedDeleteJob(anyString(), any(), anyLong(), anyString(),
                anyLong(), any(), any(), any());
        verify(fixture.repository, never()).completeClaimedIngestJob(
                anyString(), any(), anyLong(), anyString(), anyLong(), any(), any());
        verify(fixture.repository, never()).failClaimedIngestJob(
                anyString(), any(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    public void shouldKeepDeleteTombstoneAndRetryWhenSourceDeletionFails() {
        DeleteFixture fixture = new DeleteFixture(true);

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.RETRYING, fixture.job.get().status());
        Assert.assertEquals(RagDocumentStatus.DELETING, fixture.document.get().status());
        Assert.assertTrue(fixture.versions.get().stream()
                .allMatch(version -> version.status() == RagDocumentVersionStatus.DELETING));
        verify(fixture.repository, never()).completeClaimedDeleteJob(
                anyString(), any(), anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(fixture.repository, never()).failClaimedIngestJob(
                anyString(), any(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    public void shouldLeaveDeleteRunningForLeaseRecoveryWhenFailureAccountingAlsoFails() {
        DeleteFixture fixture = new DeleteFixture(true, true);

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.RUNNING, fixture.job.get().status());
        Assert.assertEquals(RagIngestStage.DELETING_SOURCE, fixture.job.get().checkpoint().stage());
        Assert.assertNull(fixture.job.get().cancelReason());
        verify(fixture.repository, never()).failClaimedIngestJob(
                anyString(), any(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    public void shouldFailClosedBeforeDeletingObjectOutsideDocumentScope() {
        DeleteFixture fixture = new DeleteFixture(false);
        List<RagDocumentVersionEntity> current = fixture.versions.get();
        RagDocumentVersionEntity source = current.get(0);
        fixture.versions.set(List.of(new RagDocumentVersionEntity(source.tenantId(), source.knowledgeBaseId(),
                        source.documentId(), source.versionId(), source.versionNumber(), source.generation(),
                        source.objectBucket(), "tenants/other/rag/other/doc/version/private.md",
                        source.parsedObjectBucket(), source.parsedObjectKey(), source.fileName(), source.sha256(),
                        source.mimeType(), source.sizeBytes(), source.status(), source.parserVersion(),
                        source.chunkerVersion(), source.embeddingModelRevision(), source.revision()), current.get(1)));

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.FAILED, fixture.job.get().status());
        Assert.assertEquals("RAG_DELETE_OBJECT_SCOPE_INVALID", fixture.job.get().errorCode());
        verify(fixture.storage, never()).deleteObject(anyString(), anyString());
    }

    @Test
    public void shouldFailClosedBeforeDeletingTraversalObjectKey() {
        DeleteFixture fixture = new DeleteFixture(false);
        List<RagDocumentVersionEntity> current = fixture.versions.get();
        RagDocumentVersionEntity source = current.get(0);
        String traversal = RagObjectStorageScope.versionPrefix(DeleteFixture.TENANT, DeleteFixture.KB_ID,
                DeleteFixture.DOCUMENT_ID, source.versionId()) + "../../other/private.md";
        fixture.versions.set(List.of(new RagDocumentVersionEntity(source.tenantId(), source.knowledgeBaseId(),
                        source.documentId(), source.versionId(), source.versionNumber(), source.generation(),
                        source.objectBucket(), traversal, source.parsedObjectBucket(), source.parsedObjectKey(),
                        source.fileName(), source.sha256(), source.mimeType(), source.sizeBytes(), source.status(),
                        source.parserVersion(), source.chunkerVersion(), source.embeddingModelRevision(),
                        source.revision()), current.get(1)));

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.FAILED, fixture.job.get().status());
        Assert.assertEquals("RAG_DELETE_OBJECT_SCOPE_INVALID", fixture.job.get().errorCode());
        verify(fixture.storage, never()).deleteObject(anyString(), anyString());
    }

    @Test
    public void shouldRetryWhenDeleteReturnsButObjectStillExists() {
        DeleteFixture fixture = new DeleteFixture(false);
        when(fixture.storage.objectExists(anyString(), anyString())).thenReturn(true);

        Assert.assertTrue(fixture.worker.execute(DeleteFixture.TENANT, DeleteFixture.JOB_ID, DeleteFixture.OWNER));

        Assert.assertEquals(RagIngestJobStatus.RETRYING, fixture.job.get().status());
        Assert.assertEquals("RAG_DELETE_OBJECT_REMAINS", fixture.job.get().errorCode());
        verify(fixture.repository, never()).completeClaimedDeleteJob(
                anyString(), any(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    public void errorClassifierShouldHideOriginalMessageAndRetryRealStorageCode() {
        RagIngestErrorClassifier classifier = new RagIngestErrorClassifier();
        String secret = "Bearer production-secret remote-body=/private/path";

        RagIngestErrorClassifier.Failure terminal = classifier.classify(
                new AppException("RAG_INGEST_INDEX_COUNT_MISMATCH", secret));
        RagIngestErrorClassifier.Failure retryable = classifier.classify(
                new AppException("OBJECT_STORAGE_DOWNLOAD_FAILED", secret));
        RagIngestErrorClassifier.Failure transientEmbedding = classifier.classify(
                new AppException("RAG_EMBEDDING_TRANSIENT_HTTP_ERROR", secret));
        RagIngestErrorClassifier.Failure permanentEmbedding = classifier.classify(
                new AppException("RAG_EMBEDDING_HTTP_ERROR", secret));

        Assert.assertEquals("RAG_INGEST_INDEX_COUNT_MISMATCH", terminal.code());
        Assert.assertFalse(terminal.retryable());
        Assert.assertFalse(terminal.safeMessage().contains(secret));
        Assert.assertFalse(terminal.safeMessage().contains("production-secret"));
        Assert.assertEquals("OBJECT_STORAGE_DOWNLOAD_FAILED", retryable.code());
        Assert.assertTrue(retryable.retryable());
        Assert.assertFalse(retryable.safeMessage().contains(secret));
        Assert.assertTrue(transientEmbedding.retryable());
        Assert.assertFalse(permanentEmbedding.retryable());
        Assert.assertFalse(transientEmbedding.safeMessage().contains(secret));
    }

    @Test
    public void workspaceCleanupShouldDeleteLinksWithoutFollowingThem() throws Exception {
        RagIngestWorkspace workspace = RagIngestWorkspace.create();
        Path root = workspace.root();
        Path outside = Files.createTempDirectory("rag-workspace-outside-");
        Path protectedFile = outside.resolve("keep.txt");
        Files.writeString(protectedFile, "must-stay", StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("external-link"), outside);
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/local.txt"), "delete-me", StandardCharsets.UTF_8);

        workspace.close();

        Assert.assertFalse(Files.exists(root));
        Assert.assertTrue(Files.exists(protectedFile));
        Assert.assertEquals("must-stay", Files.readString(protectedFile, StandardCharsets.UTF_8));

        RagIngestWorkspace replacedWorkspace = RagIngestWorkspace.create();
        Path replacedRoot = replacedWorkspace.root();
        Files.delete(replacedRoot);
        Files.createSymbolicLink(replacedRoot, outside.resolve("missing-target"));
        replacedWorkspace.close();
        Assert.assertFalse(Files.exists(replacedRoot, LinkOption.NOFOLLOW_LINKS));
    }

    private static final class Fixture {
        private static final String TENANT = "tenant-a";
        private static final String KB_ID = "kb-a";
        private static final String DOCUMENT_ID = "doc-a";
        private static final String VERSION_ID = "version-a";
        private static final String JOB_ID = "job-a";
        private static final String OWNER = "worker-a";
        private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
        private static final byte[] MARKDOWN = "# 标题\n\n这是用于摄取测试的 Markdown 正文。"
                .getBytes(StandardCharsets.UTF_8);

        private final IRagRepository repository = mock(IRagRepository.class);
        private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        private final RagDocumentParserPort parser = mock(RagDocumentParserPort.class);
        private final EmbeddingPort embedding = mock(EmbeddingPort.class);
        private final SparseEncoderPort sparseEncoder = mock(SparseEncoderPort.class);
        private final VectorStorePort vectorStore = mock(VectorStorePort.class);
        private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        private final AtomicReference<RagIngestJobEntity> job = new AtomicReference<>(pendingJob());
        private final AtomicReference<RagDocumentVersionEntity> version = new AtomicReference<>(queuedVersion());
        private final AtomicReference<RagDocumentEntity> document = new AtomicReference<>(processingDocument());
        private final AtomicReference<RagKnowledgeBaseEntity> knowledgeBase = new AtomicReference<>(knowledgeBase());
        private final List<RagChunkEntity> chunks = new ArrayList<>();
        private final List<VectorStorePort.VectorPoint> vectorPoints = new ArrayList<>();
        private final AtomicReference<Path> downloadedTarget = new AtomicReference<>();
        private final AtomicInteger heartbeatCalls = new AtomicInteger();
        private int cancelAtHeartbeat = -1;
        private int takeOverAtHeartbeat = -1;
        private long vectorCountOffset;
        private final RagIngestWorker worker;

        private Fixture() {
            RagProperties properties = new RagProperties();
            properties.getWorker().setLeaseDurationMs(Duration.ofMinutes(3).toMillis());
            properties.getWorker().setHeartbeatIntervalMs(Duration.ofSeconds(30).toMillis());
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
                    .thenAnswer(invocation -> future);
            stubRepository();
            stubExternalServices();
            worker = new RagIngestWorker(repository, objectStorageService, parser, embedding,
                    sparseEncoder, vectorStore, properties, Clock.fixed(NOW, ZoneOffset.UTC), executor);
        }

        private void stubRepository() {
            when(repository.findIngestJob(TENANT, JOB_ID)).thenAnswer(invocation -> Optional.of(job.get()));
            when(repository.findDocumentVersion(TENANT, VERSION_ID))
                    .thenAnswer(invocation -> Optional.of(version.get()));
            when(repository.findDocument(TENANT, DOCUMENT_ID))
                    .thenAnswer(invocation -> Optional.of(document.get()));
            when(repository.findKnowledgeBase(TENANT, KB_ID))
                    .thenAnswer(invocation -> Optional.of(knowledgeBase.get()));
            when(repository.claimDueIngestJob(anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(invocation -> {
                        String owner = invocation.getArgument(2);
                        Instant now = invocation.getArgument(3);
                        Instant until = invocation.getArgument(4);
                        RagIngestJobEntity claimed = job.get().claim(owner,
                                job.get().fencingToken() + 1, now, Duration.between(now, until));
                        job.set(claimed);
                        return Optional.of(claimed);
                    });
            when(repository.updateDocumentVersion(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                RagDocumentVersionEntity target = invocation.getArgument(1);
                long expected = invocation.getArgument(2);
                if (version.get().revision() != expected) return 0;
                version.set(target);
                return 1;
            });
            when(repository.heartbeatClaimedIngestJob(anyString(), anyString(), anyString(), anyLong(), any(), any()))
                    .thenAnswer(invocation -> heartbeat(invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4), invocation.getArgument(5)));
            when(repository.updateClaimedIngestJob(anyString(), any(), anyLong(), anyString(), anyLong(), any()))
                    .thenAnswer(invocation -> {
                        RagIngestJobEntity target = invocation.getArgument(1);
                        long expectedRevision = invocation.getArgument(2);
                        String owner = invocation.getArgument(3);
                        long fence = invocation.getArgument(4);
                        RagIngestJobEntity current = job.get();
                        if (current.status() != RagIngestJobStatus.RUNNING || current.revision() != expectedRevision
                                || current.lease() == null || !owner.equals(current.lease().owner())
                                || current.fencingToken() != fence) return 0;
                        job.set(target);
                        return 1;
                    });
            when(repository.listChunks(TENANT, VERSION_ID)).thenAnswer(invocation -> List.copyOf(chunks));
            when(repository.upsertChunks(anyString(), anyString(), any())).thenAnswer(invocation -> {
                chunks.clear();
                chunks.addAll(invocation.getArgument(2));
                return chunks.size();
            });
            when(repository.deleteChunks(TENANT, VERSION_ID)).thenAnswer(invocation -> {
                int count = chunks.size();
                chunks.clear();
                return count;
            });
            doAnswer(invocation -> {
                RagIngestJobEntity completed = invocation.getArgument(1);
                job.set(completed);
                version.set(version.get().ready());
                document.set(document.get().activate(VERSION_ID, 1));
                knowledgeBase.set(knowledgeBase.get().activateGeneration(1));
                return null;
            }).when(repository).completeClaimedIngestJob(anyString(), any(), anyLong(), anyString(),
                    anyLong(), any(), any());
            doAnswer(invocation -> {
                RagIngestJobEntity cancelled = invocation.getArgument(1);
                job.set(cancelled);
                version.set(version.get().cancelled());
                document.set(document.get().failProcessing());
                return null;
            }).when(repository).cancelClaimedIngestJob(anyString(), any(), anyLong(), anyLong(), anyLong(),
                    anyString(), anyLong(), any());
            doAnswer(invocation -> {
                RagIngestJobEntity failed = invocation.getArgument(1);
                job.set(failed);
                version.set(version.get().failed());
                document.set(document.get().failProcessing());
                return null;
            }).when(repository).failClaimedIngestJob(anyString(), any(), anyLong(), anyLong(), anyLong(),
                    anyString(), anyLong(), any());
        }

        private int heartbeat(String owner, long fence, Instant now, Instant until) {
            int call = heartbeatCalls.incrementAndGet();
            if (call == takeOverAtHeartbeat) {
                RagIngestJobEntity current = job.get();
                job.set(copyWithLease(current, RagIngestJobStatus.RUNNING,
                        new RagLease("new-worker", until), fence + 1, current.revision() + 1));
                return 0;
            }
            if (call == cancelAtHeartbeat) {
                job.set(job.get().requestCancel("数据库取消"));
                return 0;
            }
            RagIngestJobEntity current = job.get();
            if (current.lease() == null || !owner.equals(current.lease().owner())
                    || current.fencingToken() != fence
                    || current.status() != RagIngestJobStatus.RUNNING
                    && current.status() != RagIngestJobStatus.CANCEL_REQUESTED) return 0;
            job.set(copyWithLease(current, current.status(), new RagLease(owner, until),
                    fence, current.revision()));
            return 1;
        }

        private void stubExternalServices() {
            when(objectStorageService.ragBucket()).thenReturn("rag-bucket");
            when(objectStorageService.downloadToFile(any())).thenAnswer(invocation -> {
                var command = (cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity)
                        invocation.getArgument(0);
                Path target = command.getTargetRoot().resolve(command.getRelativeTargetPath());
                Files.write(target, MARKDOWN);
                downloadedTarget.set(target);
                return ObjectStorageDownloadResultEntity.builder().bucket("rag-bucket").objectKey("source")
                        .targetPath(target).sizeBytes(MARKDOWN.length).sha256(sha256(MARKDOWN)).build();
            });
            when(objectStorageService.putFile(any())).thenAnswer(invocation -> {
                var command = (cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity)
                        invocation.getArgument(0);
                byte[] bytes = Files.readAllBytes(command.getSourcePath());
                return ObjectStorageResultEntity.builder().bucket(command.getBucket())
                        .objectKey(command.getObjectKey()).sizeBytes((long) bytes.length)
                        .sha256(sha256(bytes)).build();
            });
            when(parser.parse(any())).thenReturn(new RagDocumentParserPort.ParsedDocument(
                    new String(MARKDOWN, StandardCharsets.UTF_8), List.of(), 0,
                    "docling-test-revision", Map.of()));
            when(embedding.embed(any())).thenAnswer(invocation -> {
                EmbeddingPort.EmbeddingCommand command = invocation.getArgument(0);
                List<List<Float>> vectors = command.inputs().stream().map(value -> denseVector()).toList();
                return new EmbeddingPort.EmbeddingResult(vectors, 768, "embedding-test-revision");
            });
            when(sparseEncoder.encode(any())).thenAnswer(invocation -> {
                SparseEncoderPort.SparseEncodingCommand command = invocation.getArgument(0);
                List<SparseEncoderPort.SparseVector> vectors = command.inputs().stream()
                        .map(value -> new SparseEncoderPort.SparseVector(Map.of(1, 1F))).toList();
                return new SparseEncoderPort.SparseEncodingResult(vectors, command.vocabularyRevision());
            });
            doAnswer(invocation -> {
                vectorPoints.addAll(invocation.getArgument(2));
                return null;
            }).when(vectorStore).upsert(anyString(), anyString(), any());
            when(vectorStore.countVersion(TENANT, VERSION_ID))
                    .thenAnswer(invocation -> vectorPoints.size() + vectorCountOffset);
            doAnswer(invocation -> {
                vectorPoints.clear();
                return null;
            }).when(vectorStore).deleteVersion(TENANT, VERSION_ID);
        }

        private static RagIngestJobEntity pendingJob() {
            return RagIngestJobEntity.pending(TENANT, KB_ID, DOCUMENT_ID, VERSION_ID, JOB_ID,
                    "idempotency-key", RagIngestOperation.INGEST, 1, 3);
        }

        private static RagDocumentVersionEntity queuedVersion() {
            return new RagDocumentVersionEntity(TENANT, KB_ID, DOCUMENT_ID, VERSION_ID, 1, 1,
                    "rag-bucket", "source", null, null, "document.md", sha256(MARKDOWN), "text/markdown",
                    MARKDOWN.length, RagDocumentVersionStatus.QUEUED, null, null, null, 0);
        }

        private static RagDocumentEntity processingDocument() {
            return new RagDocumentEntity(TENANT, "owner-a", RagVisibility.TENANT, KB_ID, DOCUMENT_ID,
                    "document.md", null, 0, 1L, RagDocumentStatus.PROCESSING, 0);
        }

        private static RagKnowledgeBaseEntity knowledgeBase() {
            return new RagKnowledgeBaseEntity(TENANT, "owner-a", KB_ID, "知识库", null,
                    RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                    "collection", 0, 0);
        }

        private static RagIngestJobEntity copyWithLease(RagIngestJobEntity source,
                                                         RagIngestJobStatus status, RagLease lease,
                                                         long fence, long revision) {
            return new RagIngestJobEntity(source.tenantId(), source.knowledgeBaseId(), source.documentId(),
                    source.versionId(), source.jobId(), source.idempotencyKey(), source.operation(),
                    source.generation(), status, source.checkpoint(), source.attemptCount(), source.maxAttempts(),
                    null, lease, fence, revision, source.cancelReason(), source.errorCode(), source.errorMessage());
        }

        private static List<Float> denseVector() {
            List<Float> values = new ArrayList<>(768);
            for (int i = 0; i < 768; i++) values.add(0F);
            return values;
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class DeleteFixture {
        private static final String TENANT = "tenant-a";
        private static final String KB_ID = "kb-a";
        private static final String DOCUMENT_ID = "doc-a";
        private static final String JOB_ID = "delete-a";
        private static final String OWNER = "worker-a";
        private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");

        private final IRagRepository repository = mock(IRagRepository.class);
        private final ObjectStorageService storage = mock(ObjectStorageService.class);
        private final VectorStorePort vectorStore = mock(VectorStorePort.class);
        private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        private final AtomicReference<RagIngestJobEntity> job = new AtomicReference<>(RagIngestJobEntity.pending(
                TENANT, KB_ID, DOCUMENT_ID, "version-2", JOB_ID, "delete-key",
                RagIngestOperation.DELETE, 3L, 3));
        private final AtomicReference<RagDocumentEntity> document = new AtomicReference<>(
                new RagDocumentEntity(TENANT, "owner-a", RagVisibility.TENANT, KB_ID, DOCUMENT_ID,
                        "document.md", "version-2", 3L, null, RagDocumentStatus.DELETING, 8L));
        private final AtomicReference<List<RagDocumentVersionEntity>> versions = new AtomicReference<>(List.of(
                version("version-1", 1, key("version-1", "source-v1.md"),
                        key("version-1", "parsed-v1.json"), 5L),
                version("version-2", 2, key("version-2", "source-v2.md"), null, 6L)));
        private final Set<String> deletedVectors = new LinkedHashSet<>();
        private final Set<String> deletedObjects = new LinkedHashSet<>();
        private final RagIngestWorker worker;

        private DeleteFixture(boolean failSourceDeletion) {
            this(failSourceDeletion, false);
        }

        private DeleteFixture(boolean failSourceDeletion, boolean failFailureAccounting) {
            RagProperties properties = new RagProperties();
            properties.getWorker().setLeaseDurationMs(Duration.ofMinutes(3).toMillis());
            properties.getWorker().setHeartbeatIntervalMs(Duration.ofSeconds(30).toMillis());
            when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
                    .thenReturn(mock(ScheduledFuture.class));
            when(repository.findIngestJob(TENANT, JOB_ID)).thenAnswer(invocation -> Optional.of(job.get()));
            when(repository.findDocument(TENANT, DOCUMENT_ID))
                    .thenAnswer(invocation -> Optional.of(document.get()));
            when(repository.listDocumentVersions(TENANT, DOCUMENT_ID))
                    .thenAnswer(invocation -> versions.get());
            when(repository.claimDueIngestJob(anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(invocation -> {
                        Instant now = invocation.getArgument(3);
                        Instant until = invocation.getArgument(4);
                        RagIngestJobEntity claimed = job.get().claim(invocation.getArgument(2), 1L, now,
                                Duration.between(now, until));
                        job.set(claimed);
                        return Optional.of(claimed);
                    });
            when(repository.heartbeatClaimedIngestJob(anyString(), anyString(), anyString(), anyLong(), any(), any()))
                    .thenReturn(1);
            when(repository.updateClaimedIngestJob(anyString(), any(), anyLong(), anyString(), anyLong(), any()))
                    .thenAnswer(invocation -> {
                        RagIngestJobEntity target = invocation.getArgument(1);
                        if (failFailureAccounting && (target.status() == RagIngestJobStatus.RETRYING
                                || target.status() == RagIngestJobStatus.FAILED
                                || target.status() == RagIngestJobStatus.DEAD)) {
                            throw new AppException("RAG_DATABASE_UNAVAILABLE", "模拟失败记账异常");
                        }
                        if (job.get().revision() != (long) invocation.getArgument(2)) return 0;
                        job.set(target);
                        return 1;
                    });
            when(repository.listChunks(anyString(), anyString())).thenReturn(List.of());
            when(vectorStore.countVersion(anyString(), anyString())).thenReturn(0L);
            when(storage.ragBucket()).thenReturn("rag");
            doAnswer(invocation -> {
                deletedVectors.add(invocation.getArgument(1));
                return null;
            }).when(vectorStore).deleteVersion(anyString(), anyString());
            doAnswer(invocation -> {
                String objectKey = invocation.getArgument(1);
                if (failSourceDeletion && key("version-1", "source-v1.md").equals(objectKey)) {
                    throw new AppException("OBJECT_STORAGE_DELETE_FAILED", "模拟对象存储暂时失败");
                }
                deletedObjects.add(objectKey);
                return null;
            }).when(storage).deleteObject(anyString(), anyString());
            doAnswer(invocation -> {
                job.set(invocation.getArgument(1));
                document.set(invocation.getArgument(5));
                versions.set(invocation.getArgument(6));
                return null;
            }).when(repository).completeClaimedDeleteJob(anyString(), any(), anyLong(), anyString(),
                    anyLong(), any(), any(), any());
            worker = new RagIngestWorker(repository, storage, mock(RagDocumentParserPort.class),
                    mock(EmbeddingPort.class), mock(SparseEncoderPort.class), vectorStore,
                    properties, Clock.fixed(NOW, ZoneOffset.UTC), executor);
        }

        private static RagDocumentVersionEntity version(String id, int number, String sourceKey,
                                                        String parsedKey, long revision) {
            return new RagDocumentVersionEntity(TENANT, KB_ID, DOCUMENT_ID, id, number, 3L,
                    "rag", sourceKey, parsedKey == null ? null : "rag", parsedKey,
                    id + ".md", "a".repeat(64), "text/markdown", 10L,
                    RagDocumentVersionStatus.DELETING, null, null, null, revision);
        }

        private static String key(String versionId, String fileName) {
            return RagObjectStorageScope.sourceObjectKey(TENANT, KB_ID, DOCUMENT_ID, versionId, fileName);
        }
    }
}
