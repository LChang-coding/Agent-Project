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
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport;
import cn.bugstack.ai.domain.rag.model.document.DocumentQualityDisposition;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.model.valobj.RagPreprocessingStrategy;
import cn.bugstack.ai.domain.rag.service.DeterministicSparseEncoder;
import cn.bugstack.ai.domain.rag.service.DocumentPreprocessingStrategyExecutor;
import cn.bugstack.ai.domain.rag.service.DocumentIrChunker;
import cn.bugstack.ai.domain.rag.service.DocumentIrCleaner;
import cn.bugstack.ai.domain.rag.service.DocumentParseQualityEvaluator;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以 MySQL 任务记录为准执行单个 RAG 文档入库任务。
 *
 * <p>任务会暂时交给一个 Worker 处理，超时后允许其他 Worker 接手；每次接手都会增加执行编号，
 * 旧 Worker 在远程调用前后核对编号，一旦过期就停止继续写入。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.worker", name = "enabled", havingValue = "true")
public class RagIngestWorker {

    /** 单个待处理文档最大允许 50 MiB。 */
    private static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;

    /** 读取任务最终状态，并提交数据库状态变化。 */
    private final IRagRepository repository;
    /** 下载源文档并保存解析、切块和质量产物。 */
    private final ObjectStorageService objectStorageService;
    /** 将 Markdown、PDF 或 DOCX 转换为统一 Document IR。 */
    private final RagDocumentParserPort parser;
    /** 为子块正文生成稠密向量。 */
    private final EmbeddingPort embedding;
    /** 为子块正文生成确定性稀疏向量。 */
    private final SparseEncoderPort sparseEncoder;
    /** 写入、计数和删除 Qdrant 向量点。 */
    private final VectorStorePort vectorStore;
    /** 提供批次、任务处理时限、重试和预处理配置。 */
    private final RagProperties properties;
    /** 按标题、长度和父子关系生成检索块。 */
    private final DocumentIrChunker chunker = new DocumentIrChunker();
    /** 清除解析噪声并保留可追溯块结构。 */
    private final DocumentIrCleaner cleaner = DocumentIrCleaner.standard();
    /** 根据配置选择完整或评测用预处理策略。 */
    private final DocumentPreprocessingStrategyExecutor preprocessing =
            new DocumentPreprocessingStrategyExecutor(cleaner);
    /** 计算解析覆盖率、文本质量和结构保真指标。 */
    private final DocumentParseQualityEvaluator qualityEvaluator = DocumentParseQualityEvaluator.standard();
    /** 序列化检查点和处理产物。 */
    private final ObjectMapper objectMapper;
    /** 将异常分类为可重试或不可重试的稳定错误。 */
    private final RagIngestErrorClassifier errorClassifier = new RagIngestErrorClassifier();
    /** 统一生成任务处理时限、检查点和结束时间。 */
    private final Clock clock;
    /** 独立续租长时间运行的解析、向量化和索引任务。 */
    private final ScheduledExecutorService heartbeatExecutor;

    @Autowired
    /** 注入完整入库流水线依赖，并创建独立的任务续期调度器。 */
    public RagIngestWorker(IRagRepository repository, ObjectStorageService objectStorageService,
                           RagDocumentParserPort parser, EmbeddingPort embedding,
                           SparseEncoderPort sparseEncoder, VectorStorePort vectorStore,
                           RagProperties properties, ObjectMapper objectMapper) {
        this(repository, objectStorageService, parser, embedding, sparseEncoder, vectorStore,
                properties, objectMapper, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "rag-ingest-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    RagIngestWorker(IRagRepository repository, ObjectStorageService objectStorageService,
                    RagDocumentParserPort parser, EmbeddingPort embedding,
                    SparseEncoderPort sparseEncoder, VectorStorePort vectorStore,
                    RagProperties properties, Clock clock, ScheduledExecutorService heartbeatExecutor) {
        this(repository, objectStorageService, parser, embedding, sparseEncoder, vectorStore,
                properties, new ObjectMapper().findAndRegisterModules(), clock, heartbeatExecutor);
    }

    RagIngestWorker(IRagRepository repository, ObjectStorageService objectStorageService,
                    RagDocumentParserPort parser, EmbeddingPort embedding,
                    SparseEncoderPort sparseEncoder, VectorStorePort vectorStore,
                    RagProperties properties, ObjectMapper objectMapper, Clock clock,
                    ScheduledExecutorService heartbeatExecutor) {
        this.repository = repository;
        this.objectStorageService = objectStorageService;
        this.parser = parser;
        this.embedding = embedding;
        this.sparseEncoder = sparseEncoder;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    /** 尝试领取并执行一个任务；重复唤醒领取失败时安全返回 false。 */
    /** 先从数据库取得任务处理权，再按操作类型执行；未取得时不调用外部系统。 */
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
        String previousTraceId = TraceContext.getTraceId();
        TraceContext.setTraceId(job.traceId() == null || job.traceId().isBlank()
                ? TraceContext.newTraceId() : job.traceId());
        long startedAt = System.currentTimeMillis();
        AiLog.info(AiLog.rag().ingestStarted(job.tenantId(), job.jobId(), job.documentId(),
                job.versionId(), job.attemptCount()));
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
            RagIngestErrorClassifier.Failure failure = errorClassifier.classify(error);
            AiLog.error(AiLog.rag().ingestFailed(job.tenantId(), job.jobId(), job.documentId(),
                    job.versionId(), job.checkpoint().stage().name().toLowerCase(java.util.Locale.ROOT),
                    failure.code(), System.currentTimeMillis() - startedAt, error));
            handleFailure(tenantId, jobId, leaseOwner, job.fencingToken(), error);
            return true;
        } finally {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                TraceContext.clear();
            } else {
                TraceContext.setTraceId(previousTraceId);
            }
        }
    }

    /** 下载、预处理、分块、向量化、核验、激活的摄取主链。 */
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
        if (job.checkpoint().stage() == RagIngestStage.INDEXING) {
            job = indexBatches(job, children, leaseOwner, heartbeat);
        } else if (job.checkpoint().stage() != RagIngestStage.VERIFYING) {
            throw new AppException("RAG_INGEST_STAGE_INVALID", "摄取任务不在可恢复的索引阶段");
        }
        verifyAndActivate(job, children, leaseOwner, heartbeat);
    }

    /**
     * 精确核验索引快照并执行可重入激活。
     * <p>任务已经推进到VERIFYING后发生进程退出时，重试只重复校验和CAS激活，不重新解析、分块或写向量。</p>
     */
    /** 向量数量与数据库分块一致后，才允许切换当前生效的索引批次。 */
    private void verifyAndActivate(RagIngestJobEntity initial, List<RagChunkEntity> children,
                                   String leaseOwner, LeaseHeartbeat heartbeat) {
        RagIngestJobEntity job = barrier(initial.tenantId(), initial.jobId(), leaseOwner,
                initial.fencingToken(), heartbeat, true);
        long verifyStarted = System.nanoTime();
        stageStarted(job, "index_verify", "开始核对向量索引与数据库分块数量", children.size());
        long vectorCount = vectorStore.countVersion(job.tenantId(), job.versionId());
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        long databaseCount = repository.listChunks(job.tenantId(), job.versionId()).stream()
                .filter(this::isChild).count();
        if (vectorCount != children.size() || databaseCount != children.size()) {
            throw new AppException("RAG_INGEST_INDEX_COUNT_MISMATCH", "向量索引与分块快照数量不一致");
        }
        List<VectorStorePort.VectorPointSnapshot> actualPoints =
                vectorStore.listVersionPointSnapshots(job.tenantId(), job.versionId());
        Set<VectorStorePort.VectorPointSnapshot> expectedPoints = children.stream()
                .map(chunk -> new VectorStorePort.VectorPointSnapshot(
                        chunk.vectorPointId(), chunk.chunkId(), chunk.contentHash()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (expectedPoints.size() != children.size() || actualPoints.size() != children.size()
                || !expectedPoints.equals(new LinkedHashSet<>(actualPoints))) {
            throw new AppException("RAG_INGEST_INDEX_SNAPSHOT_MISMATCH",
                    "向量索引point_id、chunk_id或content_hash与数据库分块不一致");
        }
        stageCompleted(job, "index_verify", "向量索引与数据库分块数量核对完成",
                elapsedNanos(verifyStarted), children.size(), (int) databaseCount);
        if (job.checkpoint().stage() != RagIngestStage.VERIFYING) {
            job = advance(job, leaseOwner, carry(job.checkpoint(), RagIngestStage.VERIFYING,
                    children.size(), children.size(), job.checkpoint().embeddingBatchIndex(), children.size()));
        }
        activate(job, leaseOwner, heartbeat);
    }

    /** 删除按向量、分块、解析产物、原文件和墓碑顺序推进 checkpoint。 */
    private void deleteDocument(RagIngestJobEntity claimed, String leaseOwner, LeaseHeartbeat heartbeat) {
        DeleteScope scope = loadDeleteScope(claimed);
        RagIngestJobEntity job = barrier(claimed.tenantId(), claimed.jobId(), leaseOwner,
                claimed.fencingToken(), heartbeat, true);
        if (job.checkpoint().stage() == RagIngestStage.RECEIVED) {
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_VECTORS);
        }
        if (job.checkpoint().stage() == RagIngestStage.DELETING_VECTORS) {
            long vectorsStarted = System.nanoTime();
            stageStarted(job, "delete_vectors", "开始删除文档全部版本的向量索引", scope.versions().size());
            for (RagDocumentVersionEntity version : scope.versions()) {
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                vectorStore.deleteVersion(job.tenantId(), version.versionId());
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                if (vectorStore.countVersion(job.tenantId(), version.versionId()) != 0L) {
                    throw new AppException("RAG_DELETE_VECTOR_REMAINS", "文档版本仍存在向量索引");
                }
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            }
            stageCompleted(job, "delete_vectors", "文档全部版本的向量索引已删除并核验",
                    elapsedNanos(vectorsStarted), scope.versions().size(), scope.versions().size());
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_CHUNKS);
        }
        if (job.checkpoint().stage() == RagIngestStage.DELETING_CHUNKS) {
            long chunksStarted = System.nanoTime();
            stageStarted(job, "delete_chunks", "开始删除文档全部版本的数据库分块", scope.versions().size());
            for (RagDocumentVersionEntity version : scope.versions()) {
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                repository.purgeChunks(job.tenantId(), version.versionId());
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                if (repository.countAllChunks(job.tenantId(), version.versionId()) != 0L) {
                    throw new AppException("RAG_DELETE_CHUNK_REMAINS", "文档版本仍存在业务分块");
                }
            }
            stageCompleted(job, "delete_chunks", "文档全部版本的数据库分块已删除并核验",
                    elapsedNanos(chunksStarted), scope.versions().size(), scope.versions().size());
            job = advanceDeletion(job, leaseOwner, RagIngestStage.DELETING_SOURCE);
        }
        if (job.checkpoint().stage() != RagIngestStage.DELETING_SOURCE) {
            throw new AppException("RAG_DELETE_STAGE_INVALID", "删除任务不在可恢复的原件清理阶段");
        }
        for (RagDocumentVersionEntity version : scope.versions()) {
            long sourceStarted = System.nanoTime();
            stageStarted(job, "delete_objects", "开始删除当前版本原件与解析产物", 1);
            validateDeleteObjectLocation(job, version, version.objectBucket(), version.objectKey(), "source");
            job = deleteObjectWithBarrier(job, leaseOwner, heartbeat,
                    version.objectBucket(), version.objectKey());
            if (hasText(version.parsedObjectBucket()) && hasText(version.parsedObjectKey())) {
                validateDeleteObjectLocation(job, version, version.parsedObjectBucket(),
                        version.parsedObjectKey(), "parsed");
                job = deleteObjectWithBarrier(job, leaseOwner, heartbeat,
                        version.parsedObjectBucket(), version.parsedObjectKey());
            }
            Set<String> artifacts = new LinkedHashSet<>(
                    RagObjectStorageScope.preprocessingArtifactObjectKeys(job.tenantId(),
                            job.knowledgeBaseId(), job.documentId(), version.versionId()));
            artifacts.remove(version.parsedObjectKey());
            for (String artifact : artifacts) {
                validateDeleteObjectLocation(job, version, objectStorageService.ragBucket(),
                        artifact, "preprocessing");
                job = deleteObjectWithBarrier(job, leaseOwner, heartbeat,
                        objectStorageService.ragBucket(), artifact);
            }
            stageCompleted(job, "delete_objects", "当前版本原件与解析产物删除完成",
                    elapsedNanos(sourceStarted), 1, 1);
        }
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        completeDeletion(job, leaseOwner);
    }

    /** 删除对象存储文件前后确认任务仍由当前 Worker 负责，防止旧 Worker 继续删除。 */
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

    /** 校验待删除对象仍属于当前租户、知识库、文档和版本目录。 */
    private void validateDeleteObjectLocation(RagIngestJobEntity job, RagDocumentVersionEntity version,
                                              String bucket, String objectKey, String kind) {
        if (!objectStorageService.ragBucket().equals(bucket)
                || !RagObjectStorageScope.containsVersionObject(objectKey, job.tenantId(),
                job.knowledgeBaseId(), job.documentId(), version.versionId())) {
            throw new AppException("RAG_DELETE_OBJECT_SCOPE_INVALID",
                    "待删除" + kind + "对象超出文档存储范围");
        }
    }

    /** 加载删除任务对应的文档及全部版本，并校验知识库归属。 */
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

    /** 验证文档所有版本已清理后提交文档删除终态。 */
    private void completeDeletion(RagIngestJobEntity job, String leaseOwner) {
        DeleteScope current = loadDeleteScope(job);
        long expectedTaskRevision = job.revision();
        RagIngestJobEntity completed = job.completeDeletion(leaseOwner, job.fencingToken(), clock.instant());
        repository.completeClaimedDeleteJob(job.tenantId(), completed, expectedTaskRevision,
                leaseOwner, job.fencingToken(), current.document().deleted(),
                current.versions().stream().map(RagDocumentVersionEntity::deleted).toList(), clock.instant());
    }

    /** 加载入库任务关联的知识库、文档和版本，并拒绝跨范围组合。 */
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

    /** 将文档版本改为处理中，并记录本次任务使用的解析器和模型修订号。 */
    private void startVersionProcessing(RagDocumentVersionEntity version) {
        if (version.status() == RagDocumentVersionStatus.PROCESSING) return;
        RagDocumentVersionEntity processing = version.processing(properties.getDocling().getParserRevision(),
                chunkerRevision(), properties.getEmbedding().getModelRevision());
        if (repository.updateDocumentVersion(version.tenantId(), processing, version.revision()) != 1) {
            throw new AppException("RAG_INGEST_VERSION_CONFLICT", "文档版本处理状态已变更");
        }
    }

    /** 有 checkpoint 时恢复既有分块，否则从 Canonical IR 清洗、质检并重建。 */
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
            PreprocessedDocument preprocessed;
            if (job.checkpoint().stage() == RagIngestStage.PARSING) {
                preprocessed = preprocess(job, scope, workspace, leaseOwner, heartbeat);
                long artifactStarted = System.nanoTime();
                stageStarted(job, "preprocessing_artifact_persist", "开始保存解析、IR、展示和质量产物", 4);
                ArtifactBundle artifacts = persistPreprocessingArtifacts(job, preprocessed, workspace);
                stageCompleted(job, "preprocessing_artifact_persist", "结构化预处理产物保存完成",
                        elapsedNanos(artifactStarted), 4, 4);
                job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
                int characterCount = preprocessed.documentIr().blocks().stream()
                        .filter(DocumentIr.Block::retrievable).map(DocumentIr.Block::normalizedText)
                        .mapToInt(value -> value.codePointCount(0, value.length())).sum();
                job = advance(job, leaseOwner, new RagIngestCheckpoint(RagIngestStage.CHUNKING,
                        0, 0, 0, 0, preprocessed.parsed().pageCount(), characterCount,
                        artifacts.primary().getBucket(), artifacts.primary().getObjectKey(),
                        artifacts.primary().getSha256(), artifacts.primary().getSizeBytes()));
                // 质量不足也必须先留下可审计IR和质量报告，再停止索引副作用。
                enforceQualityGate(preprocessed.quality());
            } else {
                long restoreStarted = System.nanoTime();
                stageStarted(job, "preprocessing_artifact_restore",
                        "开始从不可变IR产物恢复分块输入", 1);
                preprocessed = restorePreprocessedDocument(job);
                stageCompleted(job, "preprocessing_artifact_restore",
                        "不可变IR产物恢复并校验完成", elapsedNanos(restoreStarted), 1, 1);
            }
            long chunkStarted = System.nanoTime();
            stageStarted(job, "document_ir_chunking", "开始按IR标题、表格、页面和父子关系切分文档", 1);
            DocumentIrChunker.ChunkingResult result = chunker.chunk(job.versionId(),
                    preprocessed.documentIr(), chunkConfig());
            List<RagChunkEntity> records = toChunkEntities(scope, result.chunks());
            List<RagChunkEntity> children = records.stream().filter(this::isChild)
                    .sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
            if (children.isEmpty()) {
                throw new AppException("RAG_INGEST_NO_CHILD_CHUNKS", "文档未产生可检索分块");
            }
            persistChunkManifest(job, result, workspace);
            stageCompleted(job, "document_ir_chunking", "Document IR结构感知切分完成",
                    elapsedNanos(chunkStarted), 1, records.size());
            long persistStarted = System.nanoTime();
            stageStarted(job, "chunk_persist", "开始写入父子分块快照", records.size());
            repository.upsertChunks(job.tenantId(), job.versionId(), records);
            stageCompleted(job, "chunk_persist", "父子分块快照写入完成",
                    elapsedNanos(persistStarted), records.size(), records.size());
            return advanceToEmbedding(job, children, leaseOwner);
        }
    }

    /** 保存切块总数并把任务检查点推进到向量生成阶段。 */
    private List<RagChunkEntity> advanceToEmbedding(RagIngestJobEntity job, List<RagChunkEntity> children,
                                                     String leaseOwner) {
        RagIngestJobEntity advanced = advance(job, leaseOwner,
                carry(job.checkpoint(), RagIngestStage.EMBEDDING, 0, children.size(), 0, 0));
        return childSnapshot(advanced, children.size());
    }

    /** 回读子块快照并验证数量，避免使用未完整提交的切块集合。 */
    private List<RagChunkEntity> childSnapshot(RagIngestJobEntity job, int expected) {
        List<RagChunkEntity> children = repository.listChunks(job.tenantId(), job.versionId()).stream()
                .filter(this::isChild).sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
        if (expected < 1 || children.size() != expected) {
            throw new AppException("RAG_INGEST_CHUNK_SNAPSHOT_MISMATCH", "分块快照与摄取检查点不一致");
        }
        return children;
    }

    /** 每批向量写入后保存进度，任务被其他 Worker 接手时可以从已完成位置继续。 */
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
            long denseStarted = System.nanoTime();
            stageStarted(job, "dense_embedding", "开始生成Dense向量", batch.size());
            EmbeddingPort.EmbeddingResult dense = embedding.embed(new EmbeddingPort.EmbeddingCommand(
                    job.tenantId(), job.jobId(), EmbeddingPort.EmbeddingInputType.PASSAGE,
                    batch.stream().map(this::embeddingText).toList()));
            stageCompleted(job, "dense_embedding", "Dense向量生成完成",
                    elapsedNanos(denseStarted), batch.size(), dense.vectors().size());
            long sparseStarted = System.nanoTime();
            stageStarted(job, "sparse_encoding", "开始生成Sparse向量", batch.size());
            SparseEncoderPort.SparseEncodingResult sparse = sparseEncoder.encode(
                    new SparseEncoderPort.SparseEncodingCommand(job.tenantId(), job.jobId(),
                            batch.stream().map(this::embeddingText).toList(),
                            DeterministicSparseEncoder.VOCABULARY_REVISION));
            stageCompleted(job, "sparse_encoding", "Sparse向量生成完成",
                    elapsedNanos(sparseStarted), batch.size(), sparse.vectors().size());
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
            long upsertStarted = System.nanoTime();
            stageStarted(job, "vector_upsert", "开始将当前批次写入向量索引", points.size());
            vectorStore.upsert(job.tenantId(), job.versionId(), points);
            stageCompleted(job, "vector_upsert", "当前批次向量索引写入完成",
                    elapsedNanos(upsertStarted), points.size(), points.size());
            job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
            job = advance(job, leaseOwner, carry(job.checkpoint(), RagIngestStage.INDEXING, to, children.size(),
                    ++batchIndex, to));
        }
        return job;
    }

    /** 验证索引数量和预处理产物后原子激活文档版本。 */
    private void activate(RagIngestJobEntity job, String leaseOwner, LeaseHeartbeat heartbeat) {
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        long metadataStarted = System.nanoTime();
        stageStarted(job, "activation_metadata_restore",
                "开始恢复并校验激活所需的IR质量元数据", 1);
        PreprocessedDocument preprocessed = restorePreprocessedDocument(job);
        stageCompleted(job, "activation_metadata_restore",
                "激活所需的IR质量元数据恢复完成", elapsedNanos(metadataStarted), 1, 1);
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        long activationStarted = System.nanoTime();
        stageStarted(job, "generation_activate", "开始原子激活文档版本与知识库Generation", 1);
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
                        job.checkpoint().parsedSizeBytes(),
                        preprocessed.documentIr().parserName(),
                        preprocessed.documentIr().parserRevision(),
                        preprocessed.documentIr().schemaVersion(),
                        preprocessed.quality().disposition().name(),
                        preprocessed.quality().overall(),
                        RagObjectStorageScope.qualityReportObjectKey(job.tenantId(),
                                job.knowledgeBaseId(), job.documentId(), job.versionId()),
                        RagObjectStorageScope.chunkManifestObjectKey(job.tenantId(),
                                job.knowledgeBaseId(), job.documentId(), job.versionId()),
                        DocumentIrChunker.TOKENIZER_VERSION), clock.instant());
        stageCompleted(job, "generation_activate", "文档版本与知识库Generation已原子激活",
                elapsedNanos(activationStarted), 1, 1);
        AiLog.info(AiLog.rag().ingestCompleted(job.tenantId(), job.jobId(), job.documentId(),
                job.versionId(), job.checkpoint().totalChunks(), elapsedFromLease(job)));
    }

    /** 确认取消状态后只清理本次索引批次的产物，不触碰正在使用的版本。 */
    private void cleanupCancelled(RagIngestJobEntity job, String leaseOwner, LeaseHeartbeat heartbeat) {
        RagIngestJobEntity current = cancellationBarrier(job.tenantId(), job.jobId(), leaseOwner,
                job.fencingToken(), heartbeat);
        long cleanupStarted = System.nanoTime();
        stageStarted(current, "cancel_cleanup", "开始清理已取消任务的向量、分块和解析产物", 1);
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
            repository.failAfterCleanupClaimedIngestJob(current.tenantId(), failed, expectedTaskRevision,
                    scope.version().revision(), scope.document().revision(), leaseOwner,
                    current.fencingToken(), clock.instant());
            AiLog.warn(AiLog.rag().ingestFailed(current.tenantId(), current.jobId(), current.documentId(),
                    current.versionId(), "cancel_cleanup", "RAG_INGEST_FAILURE_CLEANUP_COMPLETED",
                    elapsedNanos(cleanupStarted), null));
            return;
        }
        RagIngestJobEntity cancelled = current.markCancelled(leaseOwner, current.fencingToken(), clock.instant());
        repository.cancelClaimedIngestJob(current.tenantId(), cancelled, expectedTaskRevision,
                scope.version().revision(), scope.document().revision(), leaseOwner,
                current.fencingToken(), clock.instant());
        stageCompleted(current, "cancel_cleanup", "已取消任务的派生数据清理完成",
                elapsedNanos(cleanupStarted), 1, 1);
    }

    /** 区分可恢复和确定性错误；最终失败前清理本次产物并完整保存失败状态。 */
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
                log.warn("RAG取消清理失败，等待处理权过期后由其他Worker接手 tenantId:{} jobId:{} code:{}",
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
            log.warn("RAG失败结果或清理未提交，等待其他Worker接手 tenantId:{} jobId:{} code:{}",
                    tenantId, jobId, errorClassifier.classify(commitError).code());
        }
    }

    /** 仅允许当前负责该任务的 Worker 登记最终失败前的清理要求。 */
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

    /** 清理失败任务已经写入的索引和处理中间产物。 */
    private void cleanupForTerminalFailure(RagIngestJobEntity job, String leaseOwner, long fence) {
        RagIngestJobEntity current = barrier(job.tenantId(), job.jobId(), leaseOwner, fence,
                LeaseHeartbeat.passive(), true);
        vectorStore.deleteVersion(job.tenantId(), job.versionId());
        barrier(job.tenantId(), job.jobId(), leaseOwner, fence, LeaseHeartbeat.passive(), true);
        repository.deleteChunks(job.tenantId(), job.versionId());
    }

    /** 每次写外部系统前重读任务，确认处理权、执行编号和取消状态仍有效。 */
    private RagIngestJobEntity barrier(String tenantId, String jobId, String leaseOwner, long fence,
                                       LeaseHeartbeat heartbeat, boolean renew) {
        if (heartbeat.lost()) throw new AppException("RAG_INGEST_LEASE_LOST", "摄取任务处理权已失效");
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

    /** 续租取消清理任务并确认状态仍允许清理。 */
    private RagIngestJobEntity cancellationBarrier(String tenantId, String jobId, String leaseOwner,
                                                    long fence, LeaseHeartbeat heartbeat) {
        if (heartbeat.lost()) throw new AppException("RAG_INGEST_LEASE_LOST", "取消清理任务处理权已失效");
        Instant now = clock.instant();
        RagIngestJobEntity job = repository.findIngestJob(tenantId, jobId).orElseThrow();
        if (job.status() != RagIngestJobStatus.CANCEL_REQUESTED || job.lease() == null
                || !job.lease().owner().equals(leaseOwner) || job.fencingToken() != fence
                || job.lease().expiredAt(now)) {
            throw new AppException("RAG_INGEST_CANCEL_FENCE_LOST", "取消清理任务已由其他Worker接手");
        }
        if (repository.heartbeatClaimedIngestJob(tenantId, jobId, leaseOwner, fence,
                now, now.plus(leaseDuration())) != 1) {
            throw new AppException("RAG_INGEST_CANCEL_FENCE_LOST", "取消清理续租失败");
        }
        return repository.findIngestJob(tenantId, jobId).orElseThrow();
    }

    /** 使用数据库版本、当前处理者和执行编号提交下一阶段进度。 */
    private RagIngestJobEntity advance(RagIngestJobEntity job, String leaseOwner,
                                       RagIngestCheckpoint checkpoint) {
        Instant now = clock.instant();
        RagIngestJobEntity target = job.advance(leaseOwner, job.fencingToken(), now, checkpoint);
        if (repository.updateClaimedIngestJob(job.tenantId(), target, job.revision(), leaseOwner,
                job.fencingToken(), now) != 1) {
            throw new AppException("RAG_INGEST_CHECKPOINT_CONFLICT", "摄取检查点已被其他 Worker 修改");
        }
        RagIngestJobEntity updated = repository.findIngestJob(job.tenantId(), job.jobId()).orElseThrow();
        AiLog.info(AiLog.rag().ingestStageCompleted(updated.tenantId(), updated.jobId(),
                updated.documentId(), updated.versionId(),
                checkpoint.stage().name().toLowerCase(java.util.Locale.ROOT),
                checkpoint.processedChunks(), checkpoint.totalChunks()));
        return updated;
    }

    /** 计算从本次任务续期开始到当前时刻的近似耗时。 */
    private long elapsedFromLease(RagIngestJobEntity job) {
        if (job == null || job.lease() == null) return 0L;
        return Math.max(0L, Duration.between(job.lease().expiresAt().minus(leaseDuration()),
                clock.instant()).toMillis());
    }

    /** 记录一个可按任务和阶段检索的开始事件。 */
    private void stageStarted(RagIngestJobEntity job, String stage, String message, Integer inputCount) {
        AiLog.info(AiLog.rag().ingestStageStarted(job.tenantId(), job.jobId(), job.documentId(),
                job.versionId(), stage, message, inputCount));
    }

    /** 记录阶段耗时及输入输出数量。 */
    private void stageCompleted(RagIngestJobEntity job, String stage, String message, Long costMs,
                                Integer inputCount, Integer outputCount) {
        AiLog.info(AiLog.rag().ingestStageCompleted(job.tenantId(), job.jobId(), job.documentId(),
                job.versionId(), stage, message, costMs, inputCount, outputCount));
    }

    /** 将单调时钟差值转换为非负毫秒数。 */
    private long elapsedNanos(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    /** 提交删除流程的下一阶段，并回读最新任务快照。 */
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

    /** 校验下载文件的长度和摘要与文档版本登记值一致。 */
    private void verifyDownload(RagDocumentVersionEntity version, ObjectStorageDownloadResultEntity download) {
        if (download == null || download.getTargetPath() == null
                || download.getSizeBytes() != version.sizeBytes()
                || !version.sha256().equalsIgnoreCase(download.getSha256())) {
            throw new AppException("RAG_INGEST_SOURCE_INTEGRITY_FAILED", "下载文档的长度或摘要与不可变版本不一致");
        }
    }

    /** 下载并校验源文件，执行结构化解析、质量评估及必要的强制 OCR 兜底。 */
    private PreprocessedDocument preprocess(RagIngestJobEntity initial, Scope scope,
                                            RagIngestWorkspace workspace, String leaseOwner,
                                            LeaseHeartbeat heartbeat) {
        RagIngestJobEntity job = barrier(initial.tenantId(), initial.jobId(), leaseOwner,
                initial.fencingToken(), heartbeat, true);
        long downloadStarted = System.nanoTime();
        stageStarted(job, "source_download", "开始从对象存储下载不可变文档版本", 1);
        ObjectStorageDownloadResultEntity download = objectStorageService.downloadToFile(
                ObjectStorageDownloadCommandEntity.builder()
                        .bucket(scope.version().objectBucket())
                        .objectKey(scope.version().objectKey())
                        .targetRoot(workspace.root())
                        .relativeTargetPath(workspace.sourceRelativePath())
                        .maxBytes(Math.min(MAX_DOCUMENT_BYTES, scope.version().sizeBytes()))
                        .build());
        verifyDownload(scope.version(), download);
        stageCompleted(job, "source_download", "文档下载及摘要完整性校验完成",
                elapsedNanos(downloadStarted), 1, 1);
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        RagDocumentParserPort.ParseCommand command = new RagDocumentParserPort.ParseCommand(
                job.tenantId(), job.jobId(), job.versionId(), scope.version().fileName(),
                scope.version().mimeType(), workspace.root(), download.getTargetPath(),
                download.getSizeBytes(), RagDocumentParserPort.OcrMode.AUTO);
        long parseStarted = System.nanoTime();
        stageStarted(job, "document_parse", "开始执行格式专用结构化解析", 1);
        RagDocumentParserPort.ParsedDocument parsed = parser.parse(command);
        stageCompleted(job, "document_parse", "格式专用结构化解析完成",
                elapsedNanos(parseStarted), 1, parsed.documentIr().blocks().size());
        job = barrier(job.tenantId(), job.jobId(), leaseOwner, job.fencingToken(), heartbeat, true);
        PreprocessedDocument primary = cleanAndEvaluate(job, parsed);
        if (requiresForcedOcr(scope.version().mimeType(), primary)
                && !parsed.ocrApplied()) {
            long ocrStarted = System.nanoTime();
            stageStarted(job, "document_ocr_fallback", "主解析质量不足，开始强制OCR兜底解析", 1);
            RagDocumentParserPort.ParsedDocument ocrParsed = parser.parse(
                    new RagDocumentParserPort.ParseCommand(command.tenantId(), command.jobId(),
                            command.versionId(), command.fileName(), command.mimeType(),
                            command.workspaceRoot(), command.contentPath(), command.contentLength(),
                            RagDocumentParserPort.OcrMode.FORCED));
            PreprocessedDocument fallback = cleanAndEvaluate(job, ocrParsed);
            stageCompleted(job, "document_ocr_fallback", "强制OCR兜底解析与质量比较完成",
                    elapsedNanos(ocrStarted), 1, fallback.documentIr().blocks().size());
            if (fallback.quality().overall() > primary.quality().overall()) primary = fallback;
        }
        return primary;
    }

    /** 清洗后执行结构质量门禁；低质量 PDF 可触发强制 OCR 重跑。 */
    /** 按本次任务记录的策略清洗统一文档结构，并生成质量检查报告。 */
    private PreprocessedDocument cleanAndEvaluate(RagIngestJobEntity job,
                                                  RagDocumentParserPort.ParsedDocument parsed) {
        RagPreprocessingStrategy strategy = preprocessingStrategy();
        long cleanStarted = System.nanoTime();
        stageStarted(job, "document_preprocessing_strategy",
                "开始执行预处理策略：" + strategy.name(), parsed.documentIr().blocks().size());
        DocumentPreprocessingStrategyExecutor.Result selected = preprocessing.execute(parsed, strategy);
        for (DocumentIrCleaner.CleaningAudit audit : selected.cleaningAudits()) {
            stageCompleted(job, "clean_" + audit.cleanerName(),
                    "清洗步骤完成：" + audit.cleanerName(), audit.costMs(),
                    audit.inputBlocks(), audit.outputBlocks());
        }
        stageCompleted(job, "document_preprocessing_strategy",
                "预处理策略执行完成：" + strategy.name(),
                elapsedNanos(cleanStarted), parsed.documentIr().blocks().size(),
                selected.document().blocks().size());
        long qualityStarted = System.nanoTime();
        stageStarted(job, "parse_quality_evaluate", "开始计算解析质量报告", selected.document().blocks().size());
        DocumentParseQualityReport quality = qualityEvaluator.evaluate(selected.document());
        stageCompleted(job, "parse_quality_evaluate",
                "解析质量评估完成，处置=" + quality.disposition().name(),
                elapsedNanos(qualityStarted), selected.document().blocks().size(),
                quality.findings().size());
        return new PreprocessedDocument(parsed, selected.document(), selected.cleaningAudits(), quality);
    }

    /** 判断 PDF 首次解析质量是否要求再执行一次强制 OCR。 */
    private boolean requiresForcedOcr(String mimeType, PreprocessedDocument value) {
        return mimeType != null && mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("application/pdf")
                && (value.quality().disposition() == DocumentQualityDisposition.NEEDS_REVIEW
                || value.quality().disposition() == DocumentQualityDisposition.REJECTED
                || value.quality().coverage() < 0.70);
    }

    /** 拒绝未达到文本覆盖和结构质量下限的解析结果。 */
    private void enforceQualityGate(DocumentParseQualityReport quality) {
        if (properties.getWorker().isBenchmarkPreprocessingEnabled()
                && preprocessingStrategy() != RagPreprocessingStrategy.IR_FULL) {
            return;
        }
        if (quality.disposition() == DocumentQualityDisposition.NEEDS_REVIEW) {
            throw new AppException("RAG_PARSE_NEEDS_REVIEW", "文档解析质量不足，需要人工复核");
        }
        if (quality.disposition() == DocumentQualityDisposition.REJECTED) {
            throw new AppException("RAG_PARSE_REJECTED", "文档解析质量未达到索引门禁");
        }
    }

    /** 从对象存储恢复 Document IR，并重新验证策略修订号和质量门禁。 */
    private PreprocessedDocument restorePreprocessedDocument(RagIngestJobEntity job) {
        String expectedKey = RagObjectStorageScope.documentIrObjectKey(job.tenantId(), job.knowledgeBaseId(),
                job.documentId(), job.versionId());
        if (!expectedKey.equals(job.checkpoint().parsedObjectKey())) {
            throw new AppException("RAG_IR_ARTIFACT_SCOPE_MISMATCH", "检查点中的IR产物位置与版本范围不一致");
        }
        byte[] bytes = objectStorageService.getObject(job.checkpoint().parsedObjectBucket(),
                expectedKey, Math.max(1L, properties.getDocling().getMaxResponseBytes()));
        if (bytes.length != job.checkpoint().parsedSizeBytes()
                || !sha256(bytes).equals(job.checkpoint().parsedContentHash())) {
            throw new AppException("RAG_IR_ARTIFACT_INTEGRITY_FAILED", "IR产物长度或摘要与检查点不一致");
        }
        try {
            DocumentIr ir = objectMapper.readValue(bytes, DocumentIr.class);
            verifyPreprocessingStrategy(ir);
            DocumentParseQualityReport quality = qualityEvaluator.evaluate(ir);
            enforceQualityGate(quality);
            String display = renderDisplay(ir);
            RagDocumentParserPort.ParsedDocument parsed = new RagDocumentParserPort.ParsedDocument(
                    display, List.of(), job.checkpoint().pageCount(), ir.parserRevision(),
                    ir.metadata(), ir, "", ir.warnings(), ir.flags().contains(DocumentIr.Flag.OCR_TEXT));
            return new PreprocessedDocument(parsed, ir, List.of(), quality);
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            throw new AppException("RAG_IR_ARTIFACT_INVALID", "IR产物无法反序列化", error);
        }
    }

    /** 将切块结果转换为带版本、代次和检索元数据的持久化实体。 */
    private List<RagChunkEntity> toChunkEntities(Scope scope,
                                                  List<DocumentIrChunker.StructuredChunk> chunks) {
        List<RagChunkEntity> result = new ArrayList<>(chunks.size());
        for (DocumentIrChunker.StructuredChunk chunk : chunks) {
            boolean child = chunk.level() == DocumentIrChunker.Level.CHILD;
            Map<String, String> metadata = new LinkedHashMap<>(chunk.metadata());
            metadata.put("embedding_text", chunk.embeddingText());
            metadata.put("page_to", Integer.toString(chunk.pageTo()));
            metadata.put("heading_path", String.join(" / ", chunk.headingPath()));
            metadata.put("block_ids", json(chunk.blockIds()));
            metadata.put("source_spans", json(chunk.sourceSpans()));
            metadata.put("quality_flags", json(chunk.qualityFlags()));
            result.add(new RagChunkEntity(scope.version().tenantId(), scope.document().ownerUserId(),
                    scope.document().visibility(), scope.version().knowledgeBaseId(), scope.version().documentId(),
                    scope.version().versionId(), scope.version().versionNumber(), scope.version().generation(),
                    chunk.chunkId(), chunk.chunkIndex(), chunk.parentChunkId(), chunk.previousChunkId(),
                    chunk.nextChunkId(), chunk.displayText(), chunk.tokenCount(), chunk.pageFrom(),
                    String.join(" / ", chunk.headingPath()), chunk.contentHash(),
                    child ? chunk.chunkId() : null, metadata));
        }
        return List.copyOf(result);
    }

    /** 判断块是否为实际向量化和召回使用的子块。 */
    private boolean isChild(RagChunkEntity chunk) {
        return "child".equals(chunk.metadata().get("chunk_level"));
    }

    /** 读取清洗阶段保存的向量化文本，缺失时回退到块正文。 */
    private String embeddingText(RagChunkEntity chunk) {
        String value = chunk.metadata().get("embedding_text");
        return value == null || value.isBlank() ? chunk.content() : value;
    }

    /** 从 Worker 配置生成当前切块大小、重叠和父子块限制。 */
    private DocumentIrChunker.Config chunkConfig() {
        RagProperties.Worker worker = properties.getWorker();
        return new DocumentIrChunker.Config(worker.getChildMaxChars(), worker.getChildMaxTokens(),
                worker.getParentMaxChars(), worker.getParentMaxTokens(), worker.getOverlapChars());
    }

    /** 构造包含处理进度和产物对象键的入库检查点。 */
    private RagIngestCheckpoint checkpoint(RagIngestStage stage, int processed, int total,
                                            int embeddingBatch, int vectorIndex) {
        return new RagIngestCheckpoint(stage, processed, total, embeddingBatch, vectorIndex);
    }

    /** 保留已有产物位置，仅更新阶段和处理进度。 */
    private RagIngestCheckpoint carry(RagIngestCheckpoint source, RagIngestStage stage,
                                       int processed, int total, int embeddingBatch, int vectorIndex) {
        return new RagIngestCheckpoint(stage, processed, total, embeddingBatch, vectorIndex,
                source.pageCount(), source.characterCount(), source.parsedObjectBucket(),
                source.parsedObjectKey(), source.parsedContentHash(), source.parsedSizeBytes());
    }

    /** 保存 Canonical IR、normalized.md、parser output 和质量报告以便追责复现。 */
    /** 保存解析原始输出、Document IR、规范化 Markdown 和质量报告。 */
    private ArtifactBundle persistPreprocessingArtifacts(RagIngestJobEntity job,
                                                         PreprocessedDocument value,
                                                         RagIngestWorkspace workspace) {
        try {
            String parserOutput = value.parsed().parserOutputJson().isBlank()
                    ? json(value.parsed().documentIr()) : value.parsed().parserOutputJson();
            Files.writeString(workspace.parserOutputPath(), parserOutput, StandardCharsets.UTF_8);
            Files.writeString(workspace.documentIrPath(), json(value.documentIr()), StandardCharsets.UTF_8);
            Files.writeString(workspace.normalizedMarkdownPath(), renderNormalizedMarkdown(value.documentIr()),
                    StandardCharsets.UTF_8);
            Files.writeString(workspace.qualityReportPath(), json(Map.of(
                    "report", value.quality(), "cleaningAudits", value.cleaningAudits(),
                    "parserWarnings", value.parsed().warnings(),
                    "preprocessingStrategy", preprocessingStrategy().name(),
                    "preprocessingStrategyRevision", preprocessingStrategy().revision())),
                    StandardCharsets.UTF_8);
            ObjectStorageResultEntity parserOutputObject = putArtifact(workspace.parserOutputPath(),
                    RagObjectStorageScope.parserOutputObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()), "application/json");
            ObjectStorageResultEntity irObject = putArtifact(workspace.documentIrPath(),
                    RagObjectStorageScope.documentIrObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()), "application/json");
            ObjectStorageResultEntity markdownObject = putArtifact(workspace.normalizedMarkdownPath(),
                    RagObjectStorageScope.normalizedMarkdownObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()), "text/markdown; charset=utf-8");
            ObjectStorageResultEntity qualityObject = putArtifact(workspace.qualityReportPath(),
                    RagObjectStorageScope.qualityReportObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()), "application/json");
            return new ArtifactBundle(irObject,
                    List.of(parserOutputObject, irObject, markdownObject, qualityObject));
        } catch (Exception error) {
            if (error instanceof AppException appException) throw appException;
            throw new AppException("RAG_PREPROCESSING_ARTIFACT_WRITE_FAILED",
                    "结构化预处理产物暂存失败", error);
        }
    }

    /** 保存父子块关系和切块修订号，供任务接手和问题定位使用。 */
    private void persistChunkManifest(RagIngestJobEntity job, DocumentIrChunker.ChunkingResult result,
                                      RagIngestWorkspace workspace) {
        try {
            Files.writeString(workspace.chunkManifestPath(), json(Map.of(
                    "schemaVersion", "1.0", "chunkerVersion", DocumentIrChunker.CHUNKER_VERSION,
                    "tokenizerVersion", DocumentIrChunker.TOKENIZER_VERSION,
                    "preprocessingStrategy", preprocessingStrategy().name(),
                    "preprocessingStrategyRevision", preprocessingStrategy().revision(),
                    "warnings", result.warnings(), "chunks", result.chunks())), StandardCharsets.UTF_8);
            putArtifact(workspace.chunkManifestPath(),
                    RagObjectStorageScope.chunkManifestObjectKey(job.tenantId(), job.knowledgeBaseId(),
                            job.documentId(), job.versionId()), "application/json");
        } catch (Exception error) {
            if (error instanceof AppException appException) throw appException;
            throw new AppException("RAG_CHUNK_MANIFEST_WRITE_FAILED", "分块清单保存失败", error);
        }
    }

    /** 将本地工作区产物写入 RAG 对象存储路径。 */
    private ObjectStorageResultEntity putArtifact(Path path, String objectKey, String contentType)
            throws java.io.IOException {
        return objectStorageService.putFile(ObjectStorageFileCommandEntity.builder()
                .bucket(objectStorageService.ragBucket()).objectKey(objectKey)
                .sourcePath(path).sizeBytes(Files.size(path)).contentType(contentType).build());
    }

    /** 将处理产物序列化为 JSON，失败时转换为稳定错误。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new AppException("RAG_PREPROCESSING_JSON_FAILED", "预处理结构化产物序列化失败", error);
        }
    }

    /** 生成供管理端查看的文档文本。 */
    private String renderDisplay(DocumentIr ir) {
        return ir.blocks().stream().filter(DocumentIr.Block::retrievable)
                .map(DocumentIr.Block::normalizedText).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /** 按块类型生成用于保存和评测的规范化 Markdown。 */
    private String renderNormalizedMarkdown(DocumentIr ir) {
        StringBuilder result = new StringBuilder();
        for (DocumentIr.Block block : ir.blocks()) {
            if (!block.retrievable() || block.normalizedText().isBlank()) continue;
            if (!result.isEmpty()) result.append("\n\n");
            if (block.type() == DocumentIr.BlockType.TITLE) {
                result.append("# ");
            } else if (block.type() == DocumentIr.BlockType.HEADING) {
                result.append("## ");
            } else if (block.type() == DocumentIr.BlockType.LIST_ITEM) {
                result.append("- ");
            } else if (block.type() == DocumentIr.BlockType.CODE) {
                result.append("```\n");
            }
            result.append(block.normalizedText());
            if (block.type() == DocumentIr.BlockType.CODE) result.append("\n```");
        }
        return result.toString().strip();
    }

    /** 计算产物内容的 SHA-256 十六进制摘要。 */
    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }

    /** 返回当前进程使用的文档预处理策略。 */
    private RagPreprocessingStrategy preprocessingStrategy() {
        return properties.getWorker().getPreprocessingStrategy();
    }

    /** 返回切块器与预处理策略组合后的修订号。 */
    private String chunkerRevision() {
        return DocumentIrChunker.CHUNKER_VERSION + "+" + preprocessingStrategy().revision();
    }

    /** 验证已保存的统一文档结构与当前任务记录的策略及修订号一致。 */
    private void verifyPreprocessingStrategy(DocumentIr ir) {
        String actual = ir.metadata().get("preprocessing_strategy");
        String revision = ir.metadata().get("preprocessing_strategy_revision");
        RagPreprocessingStrategy expected = preprocessingStrategy();
        // 策略字段上线前的 IR 只能来自当时唯一存在的完整生产链路，允许 IR_FULL 兼容恢复。
        if (expected == RagPreprocessingStrategy.IR_FULL
                && (actual == null || actual.isBlank()) && (revision == null || revision.isBlank())) {
            return;
        }
        if (!expected.name().equals(actual) || !expected.revision().equals(revision)) {
            throw new AppException("RAG_PREPROCESSING_STRATEGY_DRIFT",
                    "检查点预处理策略与当前进程配置不一致");
        }
    }

    /** 每次确认任务仍由当前 Worker 负责后，删除解析和切块产物并保存清理进度。 */
    private RagIngestJobEntity deleteParsedArtifactWithBarrier(RagIngestJobEntity job, String leaseOwner,
                                                                 LeaseHeartbeat heartbeat) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(RagObjectStorageScope.parsedObjectKey(job.tenantId(), job.knowledgeBaseId(),
                job.documentId(), job.versionId()));
        keys.addAll(RagObjectStorageScope.preprocessingArtifactObjectKeys(job.tenantId(),
                job.knowledgeBaseId(), job.documentId(), job.versionId()));
        RagIngestJobEntity current = job;
        for (String key : keys) {
            current = cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                    current.fencingToken(), heartbeat);
            objectStorageService.deleteObject(objectStorageService.ragBucket(), key);
            current = cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                    current.fencingToken(), heartbeat);
            if (objectStorageService.objectExists(objectStorageService.ragBucket(), key)) {
                throw new AppException("RAG_DELETE_OBJECT_REMAINS", "预处理产物删除后仍然存在");
            }
        }
        return cancellationBarrier(current.tenantId(), current.jobId(), leaseOwner,
                current.fencingToken(), heartbeat);
    }

    /** 在失败或取消收口阶段重新加载任务所属文档范围。 */
    private Scope loadScopeForClosing(RagIngestJobEntity job) {
        return new Scope(repository.findDocumentVersion(job.tenantId(), job.versionId()).orElseThrow(),
                repository.findDocument(job.tenantId(), job.documentId()).orElseThrow(),
                repository.findKnowledgeBase(job.tenantId(), job.knowledgeBaseId()).orElseThrow());
    }

    /** 读取单个 Worker 持有任务处理权的时长。 */
    private Duration leaseDuration() {
        return Duration.ofMillis(properties.getWorker().getLeaseDurationMs());
    }

    /** 按尝试次数计算有上限的指数退避时间。 */
    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 10);
        return Duration.ofMillis(Math.min(properties.getWorker().getRetryMaxDelayMs(),
                properties.getWorker().getRetryBaseDelayMs() * multiplier));
    }

    /** 独立调度心跳；续租失败后标记 lost，使后续屏障立即停止。 */
    /** 定时续租当前任务，续租失败后通过共享标志阻止后续写入。 */
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

    /** 将空值规范化为空字符串，供对象键比较使用。 */
    private String value(String value) {
        return value == null ? "" : value;
    }

    /** 判断字符串是否包含非空白内容。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    /** 应用关闭时停止任务续期线程。 */
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /** 入库任务经过归属校验后的版本、文档和知识库。 */
    private record Scope(RagDocumentVersionEntity version, RagDocumentEntity document,
                         RagKnowledgeBaseEntity knowledgeBase) {
    }

    /** 删除任务对应的文档及全部版本。 */
    private record DeleteScope(RagDocumentEntity document, List<RagDocumentVersionEntity> versions) {
        private DeleteScope {
            versions = List.copyOf(versions);
        }
    }

    /** 解析、清洗和质量评估完成后的文档。 */
    private record PreprocessedDocument(RagDocumentParserPort.ParsedDocument parsed,
                                        DocumentIr documentIr,
                                        List<DocumentIrCleaner.CleaningAudit> cleaningAudits,
                                        DocumentParseQualityReport quality) {
        private PreprocessedDocument {
            cleaningAudits = List.copyOf(cleaningAudits);
        }
    }

    /** 预处理阶段写入对象存储的主要产物位置。 */
    private record ArtifactBundle(ObjectStorageResultEntity primary,
                                  List<ObjectStorageResultEntity> artifacts) {
        private ArtifactBundle {
            artifacts = List.copyOf(artifacts);
        }
    }

    /** 长时间调用外部服务时定期延长任务处理期限，并告知主流程处理权是否已经失效。 */
    static final class LeaseHeartbeat implements AutoCloseable {
        /** 续期线程发现任务处理权失效后置为 true。 */
        private final AtomicBoolean lost = new AtomicBoolean();
        /** 后台续租任务句柄，用于任务结束时停止心跳。 */
        private ScheduledFuture<?> future;

        /** 为不需要后台续租的清理路径创建被动检查器。 */
        static LeaseHeartbeat passive() {
            return new LeaseHeartbeat();
        }

        /** 绑定当前任务的后台续租句柄。 */
        void attach(ScheduledFuture<?> future) {
            this.future = future;
        }

        /** 续期失败时标记任务处理权已经失效。 */
        void markLost() {
            lost.set(true);
        }

        /** 返回后台心跳是否已经确认当前 Worker 失去执行权。 */
        boolean lost() {
            return lost.get();
        }

        @Override
        /** 结束任务时取消后台心跳。 */
        public void close() {
            if (future != null) future.cancel(false);
        }
    }
}
