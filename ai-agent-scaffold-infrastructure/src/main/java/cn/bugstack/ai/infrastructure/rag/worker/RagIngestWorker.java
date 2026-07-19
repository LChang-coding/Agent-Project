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
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.service.StructuredRagChunker;
import cn.bugstack.ai.domain.rag.service.DeterministicSparseEncoder;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以 MySQL 任务账本为真相源的单任务 RAG 摄取 Worker。
 * <p>所有远程调用前后均重读任务并校验取消、租约和 fencing token。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.worker", name = "enabled", havingValue = "true")
public class RagIngestWorker {

    private static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;

    private final IRagRepository repository;
    private final ObjectStorageService objectStorageService;
    private final RagDocumentParserPort parser;
    private final EmbeddingPort embedding;
    private final SparseEncoderPort sparseEncoder;
    private final VectorStorePort vectorStore;
    private final RagProperties properties;
    private final StructuredRagChunker chunker = new StructuredRagChunker();
    private final RagIngestErrorClassifier errorClassifier = new RagIngestErrorClassifier();
    private final Clock clock;
    private final ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public RagIngestWorker(IRagRepository repository, ObjectStorageService objectStorageService,
                           RagDocumentParserPort parser, EmbeddingPort embedding,
                           SparseEncoderPort sparseEncoder, VectorStorePort vectorStore,
                           RagProperties properties) {
        this(repository, objectStorageService, parser, embedding, sparseEncoder, vectorStore,
                properties, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "rag-ingest-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    RagIngestWorker(IRagRepository repository, ObjectStorageService objectStorageService,
                    RagDocumentParserPort parser, EmbeddingPort embedding,
                    SparseEncoderPort sparseEncoder, VectorStorePort vectorStore,
                    RagProperties properties, Clock clock, ScheduledExecutorService heartbeatExecutor) {
        this.repository = repository;
        this.objectStorageService = objectStorageService;
        this.parser = parser;
        this.embedding = embedding;
        this.sparseEncoder = sparseEncoder;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.clock = clock;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    /** 尝试领取并执行一个任务；重复唤醒领取失败时安全返回 false。 */
    public boolean execute(String tenantId, String jobId, String leaseOwner) {
        Instant now = clock.instant();
        Optional<RagIngestJobEntity> current = repository.findIngestJob(tenantId, jobId);
        if (current.isEmpty() || current.get().status().terminal()) return false;
        Optional<RagIngestJobEntity> claimed = current.get().status() == RagIngestJobStatus.CANCEL_REQUESTED
                ? repository.claimCancelledIngestJobForCleanup(tenantId, jobId, leaseOwner,
                now, now.plus(leaseDuration()))
                : repository.claimDueIngestJob(tenantId, jobId, leaseOwner,
                now, now.plus(leaseDuration()));
        if (claimed.isEmpty()) return false;
        RagIngestJobEntity job = claimed.get();
        try (LeaseHeartbeat heartbeat = startHeartbeat(job, leaseOwner)) {
            if (job.status() == RagIngestJobStatus.CANCEL_REQUESTED) {
                cleanupCancelled(job, leaseOwner, heartbeat);
            } else if (job.operation() == RagIngestOperation.DELETE) {
                deleteDocument(job, leaseOwner, heartbeat);
            } else {
                ingest(job, leaseOwner, heartbeat);
            }
            return true;
        } catch (Exception error) {
            handleFailure(tenantId, jobId, leaseOwner, job.fencingToken(), error);
            return true;
        }
    }

    private void ingest(RagIngestJobEntity claimed, String leaseOwner, LeaseHeartbeat heartbeat) {
        if (claimed.operation() != RagIngestOperation.INGEST) {
            throw new AppException("RAG_INGEST_OPERATION_UNSUPPORTED",
                    "当前 Worker 仅支持文档 INGEST，禁止单文档 REBUILD 破坏知识库 generation");
        }
        Scope scope = loadScope(claimed);
        RagIngestJobEntity job = barrier(claimed.tenantId(), claimed.jobId(), leaseOwner,
                claimed.fencingToken(), heartbeat, true);
        startVersionProcessing(scope.version());

        List<RagChunkEntity> children = loadOrCreateChunks(job, scope, leaseOwner, heartbeat);
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        if (job.checkpoint().stage() == RagIngestStage.EMBEDDING) {
            job = advance(job, leaseOwner, carry(job.checkpoint(), RagIngestStage.INDEXING,
                    job.checkpoint().processedChunks(), job.checkpoint().totalChunks(),
                    job.checkpoint().embeddingBatchIndex(), job.checkpoint().vectorUpsertIndex()));
        }
        if (job.checkpoint().stage() != RagIngestStage.INDEXING) {
            throw new AppException("RAG_INGEST_STAGE_INVALID", "摄取任务不在可恢复的索引阶段");
        }
        job = indexBatches(job, children, leaseOwner, heartbeat);

        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        long vectorCount = vectorStore.countVersion(job.tenantId(), job.versionId());
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        long databaseCount = repository.listChunks(job.tenantId(), job.versionId()).stream()
                .filter(this::isChild).count();
        if (vectorCount != children.size() || databaseCount != children.size()) {
            throw new AppException("RAG_INGEST_INDEX_COUNT_MISMATCH", "向量索引与分块快照数量不一致");
        }
        job = advance(job, leaseOwner, carry(job.checkpoint(), RagIngestStage.VERIFYING,
                children.size(), children.size(), job.checkpoint().embeddingBatchIndex(), children.size()));
        activate(job, leaseOwner, heartbeat);
    }

    private void deleteDocument(RagIngestJobEntity claimed, String leaseOwner, LeaseHeartbeat heartbeat) {
        DeleteScope scope = loadDeleteScope(claimed);
        RagIngestJobEntity job = barrier(claimed.tenantId(), claimed.jobId(), leaseOwner,
                claimed.fencingToken(), heartbeat, true);
        if (job.checkpoint().stage() == RagIngestStage.RECEIVED) {
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_VECTORS);
        }
        if (job.checkpoint().stage() == RagIngestStage.DELETING_VECTORS) {
            for (RagDocumentVersionEntity version : scope.versions()) {
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                vectorStore.deleteVersion(job.tenantId(), version.versionId());
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                if (vectorStore.countVersion(job.tenantId(), version.versionId()) != 0L) {
                    throw new AppException("RAG_DELETE_VECTOR_REMAINS", "文档版本仍存在向量索引");
                }
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            }
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_CHUNKS);
        }
        if (job.checkpoint().stage() == RagIngestStage.DELETING_CHUNKS) {
            for (RagDocumentVersionEntity version : scope.versions()) {
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                repository.purgeChunks(job.tenantId(), version.versionId());
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                if (repository.countAllChunks(job.tenantId(), version.versionId()) != 0L) {
                    throw new AppException("RAG_DELETE_CHUNK_REMAINS", "文档版本仍存在业务分块");
                }
            }
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_SOURCE);
        }
        if (job.checkpoint().stage() != RagIngestStage.DELETING_SOURCE) {
            throw new AppException("RAG_DELETE_STAGE_INVALID", "删除任务不在可恢复的原件清理阶段");
        }
        for (RagDocumentVersionEntity version : scope.versions()) {
            validateDeleteObjectLocation(job, version, version.objectBucket(), version.objectKey(), "source");
            job = deleteObjectWithBarrier(job, leaseOwner, heartbeat,
                    version.objectBucket(), version.objectKey());
            if (hasText(version.parsedObjectBucket()) && hasText(version.parsedObjectKey())) {
                validateDeleteObjectLocation(job, version, version.parsedObjectBucket(),
                        version.parsedObjectKey(), "parsed");
                job = deleteObjectWithBarrier(job, leaseOwner, heartbeat,
                        version.parsedObjectBucket(), version.parsedObjectKey());
            }
        }
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        completeDeletion(job, leaseOwner);
    }

    private RagIngestJobEntity deleteObjectWithBarrier(RagIngestJobEntity job, String leaseOwner,
                                                        LeaseHeartbeat heartbeat, String bucket,
                                                        String objectKey) {
        RagIngestJobEntity current = barrier(job.tenantId(), job.jobId(), leaseOwner,
                job.fencingToken(), heartbeat, true);
        objectStorageService.deleteObject(bucket, objectKey);
        current = barrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat, true);
        if (objectStorageService.objectExists(bucket, objectKey)) {
            throw new AppException("RAG_DELETE_OBJECT_REMAINS", "对象删除后仍然存在");
        }
        return barrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat, true);
    }

    private void validateDeleteObjectLocation(RagIngestJobEntity job, RagDocumentVersionEntity version,
                                              String bucket, String objectKey, String kind) {
        if (!objectStorageService.ragBucket().equals(bucket)
                || !RagObjectStorageScope.containsVersionObject(objectKey, job.tenantId(),
                job.knowledgeBaseId(), job.documentId(), version.versionId())) {
            throw new AppException("RAG_DELETE_OBJECT_SCOPE_INVALID",
                    "待删除" + kind + "对象超出文档存储范围");
        }
    }

    private DeleteScope loadDeleteScope(RagIngestJobEntity job) {
        RagDocumentEntity document = repository.findDocument(job.tenantId(), job.documentId())
                .orElseThrow(() -> new AppException("RAG_DELETE_DOCUMENT_NOT_FOUND", "待删除文档不存在"));
        List<RagDocumentVersionEntity> versions = repository.listDocumentVersions(job.tenantId(), job.documentId());
        if (job.operation() != RagIngestOperation.DELETE || document.status()
                != cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus.DELETING
                || !document.knowledgeBaseId().equals(job.knowledgeBaseId()) || versions.isEmpty()
                || versions.stream().anyMatch(version -> !version.knowledgeBaseId().equals(job.knowledgeBaseId())
                || !version.documentId().equals(job.documentId())
                || version.status() != RagDocumentVersionStatus.DELETING)) {
            throw new AppException("RAG_DELETE_SCOPE_MISMATCH", "删除任务与文档或版本墓碑范围不一致");
        }
        return new DeleteScope(document, versions);
    }

    private void completeDeletion(RagIngestJobEntity job, String leaseOwner) {
        DeleteScope current = loadDeleteScope(job);
        long expectedTaskRevision = job.revision();
        RagIngestJobEntity completed = job.completeDeletion(leaseOwner, job.fencingToken(), clock.instant());
        repository.completeClaimedDeleteJob(job.tenantId(), completed, expectedTaskRevision,
                leaseOwner, job.fencingToken(), current.document().deleted(),
                current.versions().stream().map(RagDocumentVersionEntity::deleted).toList(), clock.instant());
    }

    private Scope loadScope(RagIngestJobEntity job) {
        RagDocumentVersionEntity version = repository.findDocumentVersion(job.tenantId(), job.versionId())
                .orElseThrow(() -> new AppException("RAG_INGEST_VERSION_NOT_FOUND", "摄取文档版本不存在"));
        RagDocumentEntity document = repository.findDocument(job.tenantId(), job.documentId())
                .orElseThrow(() -> new AppException("RAG_INGEST_DOCUMENT_NOT_FOUND", "摄取文档不存在"));
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(job.tenantId(), job.knowledgeBaseId())
                .orElseThrow(() -> new AppException("RAG_INGEST_KB_NOT_FOUND", "摄取知识库不存在"));
        if (!version.documentId().equals(job.documentId())
                || !version.knowledgeBaseId().equals(job.knowledgeBaseId())
                || version.generation() != job.generation()
                || !document.knowledgeBaseId().equals(job.knowledgeBaseId())
                || document.targetGeneration() == null || document.targetGeneration() != job.generation()
                || job.generation() > Math.max(1L, knowledgeBase.currentGeneration())) {
            throw new AppException("RAG_INGEST_SCOPE_MISMATCH", "摄取任务与文档、版本或知识库范围不一致");
        }
        return new Scope(version, document, knowledgeBase);
    }

    private void startVersionProcessing(RagDocumentVersionEntity version) {
        if (version.status() == RagDocumentVersionStatus.PROCESSING) return;
        RagDocumentVersionEntity processing = version.processing(properties.getDocling().getParserRevision(),
                StructuredRagChunker.CHUNKER_VERSION, properties.getEmbedding().getModelRevision());
        if (repository.updateDocumentVersion(version.tenantId(), processing, version.revision()) != 1) {
            throw new AppException("RAG_INGEST_VERSION_CONFLICT", "文档版本处理状态已变更");
        }
    }

    private List<RagChunkEntity> loadOrCreateChunks(RagIngestJobEntity initial, Scope scope,
                                                     String leaseOwner, LeaseHeartbeat heartbeat) {
        RagIngestJobEntity job = initial;
        if (job.checkpoint().stage() == RagIngestStage.RECEIVED) {
            job = advance(job, leaseOwner, checkpoint(RagIngestStage.PARSING, 0, 0, 0, 0));
        }
        if (job.checkpoint().stage().ordinal() >= RagIngestStage.EMBEDDING.ordinal()) {
            return childSnapshot(job, job.checkpoint().totalChunks());
        }
        if (job.checkpoint().stage() != RagIngestStage.PARSING
                && job.checkpoint().stage() != RagIngestStage.CHUNKING) {
            throw new AppException("RAG_INGEST_STAGE_INVALID", "摄取任务分块阶段不合法");
        }

        List<RagChunkEntity> existing = repository.listChunks(job.tenantId(), job.versionId());
        if (job.checkpoint().stage() == RagIngestStage.CHUNKING && !existing.isEmpty()) {
            List<RagChunkEntity> children = existing.stream().filter(this::isChild)
                    .sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
            return advanceToEmbedding(job, children, leaseOwner);
        }

        try (RagIngestWorkspace workspace = RagIngestWorkspace.create()) {
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            ObjectStorageDownloadResultEntity download = objectStorageService.downloadToFile(
                    ObjectStorageDownloadCommandEntity.builder()
                            .bucket(scope.version().objectBucket())
                            .objectKey(scope.version().objectKey())
                            .targetRoot(workspace.root())
                            .relativeTargetPath(workspace.sourceRelativePath())
                            .maxBytes(Math.min(MAX_DOCUMENT_BYTES, scope.version().sizeBytes()))
                            .build());
            verifyDownload(scope.version(), download);
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            RagDocumentParserPort.ParsedDocument parsed = parser.parse(new RagDocumentParserPort.ParseCommand(
                    job.tenantId(), job.jobId(), job.versionId(), scope.version().fileName(),
                    scope.version().mimeType(), download.getTargetPath(), download.getSizeBytes(), false));
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            if (job.checkpoint().stage() == RagIngestStage.PARSING) {
                ObjectStorageResultEntity parsedObject = persistParsedArtifact(job, parsed, workspace);
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                int characterCount = parsed.normalizedMarkdown().codePointCount(
                        0, parsed.normalizedMarkdown().length());
                job = advance(job, leaseOwner, new RagIngestCheckpoint(RagIngestStage.CHUNKING,
                        0, 0, 0, 0, parsed.pageCount(), characterCount,
                        parsedObject.getBucket(), parsedObject.getObjectKey(), parsedObject.getSha256(),
                        parsedObject.getSizeBytes()));
            }
            StructuredRagChunker.ChunkingResult result = chunker.chunk(job.versionId(), parsed, chunkConfig());
            List<RagChunkEntity> records = toChunkEntities(scope, result.chunks());
            List<RagChunkEntity> children = records.stream().filter(this::isChild)
                    .sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
            if (children.isEmpty()) {
                throw new AppException("RAG_INGEST_NO_CHILD_CHUNKS", "文档未产生可检索分块");
            }
            repository.upsertChunks(job.tenantId(), job.versionId(), records);
            return advanceToEmbedding(job, children, leaseOwner);
        }
    }

    private List<RagChunkEntity> advanceToEmbedding(RagIngestJobEntity job, List<RagChunkEntity> children,
                                                     String leaseOwner) {
        RagIngestJobEntity advanced = advance(job, leaseOwner,
                carry(job.checkpoint(), RagIngestStage.EMBEDDING, 0, children.size(), 0, 0));
        return childSnapshot(advanced, children.size());
    }

    private List<RagChunkEntity> childSnapshot(RagIngestJobEntity job, int expected) {
        List<RagChunkEntity> children = repository.listChunks(job.tenantId(), job.versionId()).stream()
                .filter(this::isChild).sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
        if (expected < 1 || children.size() != expected) {
            throw new AppException("RAG_INGEST_CHUNK_SNAPSHOT_MISMATCH", "分块快照与摄取检查点不一致");
        }
        return children;
    }

    private RagIngestJobEntity indexBatches(RagIngestJobEntity initial, List<RagChunkEntity> children,
                                             String leaseOwner, LeaseHeartbeat heartbeat) {
        RagIngestJobEntity job = initial;
        int batchSize = Math.min(properties.getEmbedding().getBatchSize(), properties.getQdrant().getBatchSize());
        int start = job.checkpoint().vectorUpsertIndex();
        int batchIndex = job.checkpoint().embeddingBatchIndex();
        for (int from = start; from < children.size(); from += batchSize) {
            int to = Math.min(children.size(), from + batchSize);
            List<RagChunkEntity> batch = children.subList(from, to);
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            EmbeddingPort.EmbeddingResult dense = embedding.embed(new EmbeddingPort.EmbeddingCommand(
                    job.tenantId(), job.jobId(), EmbeddingPort.EmbeddingInputType.PASSAGE,
                    batch.stream().map(RagChunkEntity::content).toList()));
            SparseEncoderPort.SparseEncodingResult sparse = sparseEncoder.encode(
                    new SparseEncoderPort.SparseEncodingCommand(job.tenantId(), job.jobId(),
                            batch.stream().map(RagChunkEntity::content).toList(),
                            DeterministicSparseEncoder.VOCABULARY_REVISION));
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            List<VectorStorePort.VectorPoint> points = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                RagChunkEntity chunk = batch.get(i);
                points.add(new VectorStorePort.VectorPoint(chunk.vectorPointId(), chunk.knowledgeBaseId(),
                        chunk.documentId(), chunk.versionId(), chunk.generation(), chunk.chunkId(),
                        dense.vectors().get(i), sparse.vectors().get(i), Map.of(
                        "heading_path", value(chunk.headingPath()),
                        "page_number", chunk.pageNumber() == null ? "" : chunk.pageNumber().toString(),
                        "content_hash", chunk.contentHash())));
            }
            vectorStore.upsert(job.tenantId(), job.versionId(), points);
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            job = advance(job, leaseOwner, carry(job.checkpoint(), RagIngestStage.INDEXING, to, children.size(),
                    ++batchIndex, to));
        }
        return job;
    }

    private void activate(RagIngestJobEntity job, String leaseOwner, LeaseHeartbeat heartbeat) {
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        Scope current = loadScope(job);
        long expectedTaskRevision = job.revision();
        RagIngestJobEntity completed = job.complete(leaseOwner, job.fencingToken(), clock.instant());
        repository.completeClaimedIngestJob(job.tenantId(), completed, expectedTaskRevision, leaseOwner,
                job.fencingToken(), new RagIndexActivation(job.knowledgeBaseId(), job.documentId(),
                        job.versionId(), job.generation(), current.version().revision(),
                        current.document().revision(), current.knowledgeBase().revision(),
                        job.checkpoint().pageCount(), job.checkpoint().characterCount(),
                        job.checkpoint().totalChunks(), job.checkpoint().parsedObjectBucket(),
                        job.checkpoint().parsedObjectKey(), job.checkpoint().parsedContentHash(),
                        job.checkpoint().parsedSizeBytes()), clock.instant());
    }

    private void cleanupCancelled(RagIngestJobEntity job, String leaseOwner, LeaseHeartbeat heartbeat) {
        RagIngestJobEntity current = cancellationBarrier(job.tenantId(), job.jobId(), leaseOwner,
                job.fencingToken(), heartbeat);
        vectorStore.deleteVersion(current.tenantId(), current.versionId());
        current = cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat);
        repository.deleteChunks(current.tenantId(), current.versionId());
        current = deleteParsedArtifactWithBarrier(current, leaseOwner, heartbeat);
        Scope scope = loadScopeForClosing(current);
        long expectedTaskRevision = current.revision();
        if (RagIngestJobEntity.FAILURE_CLEANUP_FAILED.equals(current.cancelReason())
                || RagIngestJobEntity.FAILURE_CLEANUP_DEAD.equals(current.cancelReason())) {
            RagIngestJobEntity failed = current.markFailedAfterCleanup(
                    leaseOwner, current.fencingToken(), clock.instant());
            repository.failClaimedIngestJob(current.tenantId(), failed, expectedTaskRevision,
                    scope.version().revision(), scope.document().revision(), leaseOwner,
                    current.fencingToken(), clock.instant());
            return;
        }
        RagIngestJobEntity cancelled = current.markCancelled(leaseOwner, current.fencingToken(), clock.instant());
        repository.cancelClaimedIngestJob(current.tenantId(), cancelled, expectedTaskRevision,
                scope.version().revision(), scope.document().revision(), leaseOwner,
                current.fencingToken(), clock.instant());
    }

    private void handleFailure(String tenantId, String jobId, String leaseOwner, long fence, Exception error) {
        RagIngestErrorClassifier.Failure failure = errorClassifier.classify(error);
        Optional<RagIngestJobEntity> latestOptional = repository.findIngestJob(tenantId, jobId);
        if (latestOptional.isEmpty()) return;
        RagIngestJobEntity latest = latestOptional.get();
        if (latest.status() == RagIngestJobStatus.CANCEL_REQUESTED
                && latest.lease() != null && latest.lease().owner().equals(leaseOwner)
                && latest.fencingToken() == fence) {
            try {
                cleanupCancelled(latest, leaseOwner, LeaseHeartbeat.passive());
            } catch (Exception cleanupError) {
                log.warn("RAG取消清理失败，等待租约过期接管 tenantId:{} jobId:{} code:{}",
                        tenantId, jobId, errorClassifier.classify(cleanupError).code());
            }
            return;
        }
        if (latest.status() != RagIngestJobStatus.RUNNING || latest.lease() == null
                || !latest.lease().owner().equals(leaseOwner) || latest.fencingToken() != fence) return;
        try {
            if (failure.retryable() && latest.attemptCount() < latest.maxAttempts()) {
                Instant now = clock.instant();
                RagIngestJobEntity retry = latest.failRetryable(leaseOwner, fence, now,
                        now.plus(retryDelay(latest.attemptCount())), failure.code(), failure.safeMessage());
                repository.updateClaimedIngestJob(tenantId, retry, latest.revision(), leaseOwner, fence, now);
                return;
            }
            if (latest.operation() == RagIngestOperation.DELETE) {
                Instant now = clock.instant();
                RagIngestJobEntity failed = failure.retryable()
                        ? latest.failRetryable(leaseOwner, fence, now, now,
                        failure.code(), failure.safeMessage())
                        : latest.failTerminal(leaseOwner, fence, now,
                        failure.code(), failure.safeMessage());
                repository.updateClaimedIngestJob(tenantId, failed, latest.revision(), leaseOwner, fence, now);
                return;
            }
            cleanupForTerminalFailure(latest, leaseOwner, fence);
            Scope scope = loadScopeForClosing(latest);
            Instant now = clock.instant();
            RagIngestJobEntity failed = failure.retryable()
                    ? latest.failRetryable(leaseOwner, fence, now, now, failure.code(), failure.safeMessage())
                    : latest.failTerminal(leaseOwner, fence, now, failure.code(), failure.safeMessage());
            repository.failClaimedIngestJob(tenantId, failed, latest.revision(), scope.version().revision(),
                    scope.document().revision(), leaseOwner, fence, now);
        } catch (Exception commitError) {
            if (latest.operation() != RagIngestOperation.DELETE) {
                requestFailureCleanupIfOwned(tenantId, jobId, leaseOwner, fence,
                        failure.retryable() && latest.attemptCount() >= latest.maxAttempts(), failure);
            }
            log.warn("RAG失败结果或清理未提交，等待租约恢复 tenantId:{} jobId:{} code:{}",
                    tenantId, jobId, errorClassifier.classify(commitError).code());
        }
    }

    private void requestFailureCleanupIfOwned(String tenantId, String jobId, String leaseOwner, long fence,
                                              boolean dead, RagIngestErrorClassifier.Failure failure) {
        try {
            RagIngestJobEntity current = repository.findIngestJob(tenantId, jobId).orElse(null);
            if (current == null || current.status() != RagIngestJobStatus.RUNNING || current.lease() == null
                    || !current.lease().owner().equals(leaseOwner) || current.fencingToken() != fence
                    || current.lease().expiredAt(clock.instant())) return;
            RagIngestJobEntity cleanup = current.requestFailureCleanup(dead,
                    failure.code(), failure.safeMessage());
            repository.updateClaimedIngestJob(tenantId, cleanup, current.revision(), leaseOwner,
                    fence, clock.instant());
        } catch (Exception ignored) {
            // 尽力转入可接管清理态；CAS失败时交由当前数据库真实状态决定后续。
        }
    }

    private void cleanupForTerminalFailure(RagIngestJobEntity job, String leaseOwner, long fence) {
        RagIngestJobEntity current = barrier(job.tenantId(), job.jobId(), leaseOwner, fence,
                LeaseHeartbeat.passive(), true);
        vectorStore.deleteVersion(job.tenantId(), job.versionId());
        barrier(job.tenantId(), job.jobId(), leaseOwner, fence, LeaseHeartbeat.passive(), true);
        repository.deleteChunks(job.tenantId(), job.versionId());
    }

    private RagIngestJobEntity barrier(String tenantId, String jobId, String leaseOwner, long fence,
                                       LeaseHeartbeat heartbeat, boolean renew) {
        if (heartbeat.lost()) throw new AppException("RAG_INGEST_LEASE_LOST", "摄取任务租约已丢失");
        Instant now = clock.instant();
        RagIngestJobEntity job = repository.findIngestJob(tenantId, jobId)
                .orElseThrow(() -> new AppException("RAG_INGEST_JOB_NOT_FOUND", "摄取任务不存在"));
        job.assertExternalCallAllowed(leaseOwner, fence, now);
        if (renew && repository.heartbeatClaimedIngestJob(tenantId, jobId, leaseOwner, fence,
                now, now.plus(leaseDuration())) != 1) {
            throw new AppException("RAG_INGEST_LEASE_LOST", "摄取任务续租失败");
        }
        return repository.findIngestJob(tenantId, jobId).orElseThrow();
    }

    private RagIngestJobEntity cancellationBarrier(String tenantId, String jobId, String leaseOwner,
                                                    long fence, LeaseHeartbeat heartbeat) {
        if (heartbeat.lost()) throw new AppException("RAG_INGEST_LEASE_LOST", "取消清理租约已丢失");
        Instant now = clock.instant();
        RagIngestJobEntity job = repository.findIngestJob(tenantId, jobId).orElseThrow();
        if (job.status() != RagIngestJobStatus.CANCEL_REQUESTED || job.lease() == null
                || !job.lease().owner().equals(leaseOwner) || job.fencingToken() != fence
                || job.lease().expiredAt(now)) {
            throw new AppException("RAG_INGEST_CANCEL_FENCE_LOST", "取消清理租约或栅栏已失效");
        }
        if (repository.heartbeatClaimedIngestJob(tenantId, jobId, leaseOwner, fence,
                now, now.plus(leaseDuration())) != 1) {
            throw new AppException("RAG_INGEST_CANCEL_FENCE_LOST", "取消清理续租失败");
        }
        return repository.findIngestJob(tenantId, jobId).orElseThrow();
    }

    private RagIngestJobEntity advance(RagIngestJobEntity job, String leaseOwner,
                                       RagIngestCheckpoint checkpoint) {
        Instant now = clock.instant();
        RagIngestJobEntity target = job.advance(leaseOwner, job.fencingToken(), now, checkpoint);
        if (repository.updateClaimedIngestJob(job.tenantId(), target, job.revision(), leaseOwner,
                job.fencingToken(), now) != 1) {
            throw new AppException("RAG_INGEST_CHECKPOINT_CONFLICT", "摄取检查点已被其他 Worker 修改");
        }
        return repository.findIngestJob(job.tenantId(), job.jobId()).orElseThrow();
    }

    private RagIngestJobEntity advanceDeletion(RagIngestJobEntity job, String leaseOwner,
                                                RagIngestStage stage) {
        Instant now = clock.instant();
        RagIngestJobEntity target = job.advanceDeletion(leaseOwner, job.fencingToken(), now, stage);
        if (repository.updateClaimedIngestJob(job.tenantId(), target, job.revision(), leaseOwner,
                job.fencingToken(), now) != 1) {
            throw new AppException("RAG_DELETE_CHECKPOINT_CONFLICT", "删除检查点已被其他Worker修改");
        }
        return repository.findIngestJob(job.tenantId(), job.jobId()).orElseThrow();
    }

    private void verifyDownload(RagDocumentVersionEntity version, ObjectStorageDownloadResultEntity download) {
        if (download == null || download.getTargetPath() == null
                || download.getSizeBytes() != version.sizeBytes()
                || !version.sha256().equalsIgnoreCase(download.getSha256())) {
            throw new AppException("RAG_INGEST_SOURCE_INTEGRITY_FAILED", "下载文档的长度或摘要与不可变版本不一致");
        }
    }

    private List<RagChunkEntity> toChunkEntities(Scope scope,
                                                  List<StructuredRagChunker.StructuredChunk> chunks) {
        List<RagChunkEntity> result = new ArrayList<>(chunks.size());
        for (StructuredRagChunker.StructuredChunk chunk : chunks) {
            boolean child = chunk.level() == StructuredRagChunker.Level.CHILD;
            Map<String, String> metadata = new LinkedHashMap<>(chunk.metadata());
            result.add(new RagChunkEntity(scope.version().tenantId(), scope.document().ownerUserId(),
                    scope.document().visibility(), scope.version().knowledgeBaseId(), scope.version().documentId(),
                    scope.version().versionId(), scope.version().versionNumber(), scope.version().generation(),
                    chunk.chunkId(), chunk.chunkIndex(), chunk.parentChunkId(), chunk.previousChunkId(),
                    chunk.nextChunkId(), chunk.content(), chunk.tokenCount(), chunk.pageNumber(),
                    chunk.headingPath(), chunk.contentHash(), child ? chunk.chunkId() : null, metadata));
        }
        return List.copyOf(result);
    }

    private boolean isChild(RagChunkEntity chunk) {
        return "child".equals(chunk.metadata().get("chunk_level"));
    }

    private StructuredRagChunker.Config chunkConfig() {
        RagProperties.Worker worker = properties.getWorker();
        return new StructuredRagChunker.Config(worker.getChildMaxChars(), worker.getChildMaxTokens(),
                worker.getParentMaxChars(), worker.getParentMaxTokens(), worker.getOverlapChars());
    }

    private RagIngestCheckpoint checkpoint(RagIngestStage stage, int processed, int total,
                                            int embeddingBatch, int vectorIndex) {
        return new RagIngestCheckpoint(stage, processed, total, embeddingBatch, vectorIndex);
    }

    private RagIngestCheckpoint carry(RagIngestCheckpoint source, RagIngestStage stage,
                                       int processed, int total, int embeddingBatch, int vectorIndex) {
        return new RagIngestCheckpoint(stage, processed, total, embeddingBatch, vectorIndex,
                source.pageCount(), source.characterCount(), source.parsedObjectBucket(),
                source.parsedObjectKey(), source.parsedContentHash(), source.parsedSizeBytes());
    }

    private ObjectStorageResultEntity persistParsedArtifact(RagIngestJobEntity job,
                                                             RagDocumentParserPort.ParsedDocument parsed,
                                                             RagIngestWorkspace workspace) {
        try {
            Path path = workspace.parsedMarkdownPath();
            Files.writeString(path, parsed.normalizedMarkdown(), StandardCharsets.UTF_8);
            return objectStorageService.putFile(ObjectStorageFileCommandEntity.builder()
                    .bucket(objectStorageService.ragBucket())
                    .objectKey(RagObjectStorageScope.parsedObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()))
                    .sourcePath(path).sizeBytes(Files.size(path))
                    .contentType("text/markdown; charset=utf-8").build());
        } catch (java.io.IOException error) {
            throw new AppException("RAG_PARSED_ARTIFACT_WRITE_FAILED", "规范化解析产物暂存失败", error);
        }
    }

    private RagIngestJobEntity deleteParsedArtifactWithBarrier(RagIngestJobEntity job, String leaseOwner,
                                                                 LeaseHeartbeat heartbeat) {
        String key = RagObjectStorageScope.parsedObjectKey(job.tenantId(), job.knowledgeBaseId(),
                job.documentId(), job.versionId());
        RagIngestJobEntity current = cancellationBarrier(job.tenantId(), job.jobId(), leaseOwner,
                job.fencingToken(), heartbeat);
        objectStorageService.deleteObject(objectStorageService.ragBucket(), key);
        current = cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat);
        if (objectStorageService.objectExists(objectStorageService.ragBucket(), key)) {
            throw new AppException("RAG_DELETE_OBJECT_REMAINS", "解析产物删除后仍然存在");
        }
        return cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat);
    }

    private Scope loadScopeForClosing(RagIngestJobEntity job) {
        return new Scope(repository.findDocumentVersion(job.tenantId(), job.versionId()).orElseThrow(),
                repository.findDocument(job.tenantId(), job.documentId()).orElseThrow(),
                repository.findKnowledgeBase(job.tenantId(), job.knowledgeBaseId()).orElseThrow());
    }

    private Duration leaseDuration() {
        return Duration.ofMillis(properties.getWorker().getLeaseDurationMs());
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 10);
        return Duration.ofMillis(Math.min(properties.getWorker().getRetryMaxDelayMs(),
                properties.getWorker().getRetryBaseDelayMs() * multiplier));
    }

    private LeaseHeartbeat startHeartbeat(RagIngestJobEntity job, String leaseOwner) {
        LeaseHeartbeat heartbeat = new LeaseHeartbeat();
        long interval = properties.getWorker().getHeartbeatIntervalMs();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                Instant now = clock.instant();
                int changed = repository.heartbeatClaimedIngestJob(job.tenantId(), job.jobId(), leaseOwner,
                        job.fencingToken(), now, now.plus(leaseDuration()));
                if (changed != 1) heartbeat.markLost();
            } catch (Exception e) {
                heartbeat.markLost();
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        heartbeat.attach(future);
        return heartbeat;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private record Scope(RagDocumentVersionEntity version, RagDocumentEntity document,
                         RagKnowledgeBaseEntity knowledgeBase) {
    }

    private record DeleteScope(RagDocumentEntity document, List<RagDocumentVersionEntity> versions) {
        private DeleteScope {
            versions = List.copyOf(versions);
        }
    }

    static final class LeaseHeartbeat implements AutoCloseable {
        private final AtomicBoolean lost = new AtomicBoolean();
        private ScheduledFuture<?> future;

        static LeaseHeartbeat passive() {
            return new LeaseHeartbeat();
        }

        void attach(ScheduledFuture<?> future) {
            this.future = future;
        }

        void markLost() {
            lost.set(true);
        }

        boolean lost() {
            return lost.get();
        }

        @Override
        public void close() {
            if (future != null) future.cancel(false);
        }
    }
}
