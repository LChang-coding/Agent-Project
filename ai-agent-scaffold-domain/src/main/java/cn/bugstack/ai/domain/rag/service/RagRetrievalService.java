package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import cn.bugstack.ai.domain.context.service.TokenCounter;
import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort;
import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort;
import cn.bugstack.ai.domain.rag.adapter.port.RagRetrievalAuditPort;
import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalAuditCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 租户隔离的 RAG 在线检索编排服务。
 * <p>服务按运行快照解析绑定和检索配置，执行稠密与稀疏召回、候选融合、
 * 可选重排、业务回表、资源范围复核和 Token 预算组装。结果保留各阶段候选数与耗时，
 * 供运行审计和检索质量评测使用。</p>
 */
@Service
public class RagRetrievalService {

    /** 查询长度、RRF 常数、绑定数量和数据库批量水位的硬边界。 */
    private static final int MAX_QUERY_CHARS = 4096;
    /** RRF 融合时用于降低头部排名差异的固定常数。 */
    private static final int RRF_K = 60;
    /** 单次运行允许解析的最大知识库绑定数。 */
    private static final int MAX_BINDINGS = 32;
    /** 文档业务回表的单批最大标识数。 */
    private static final int DOCUMENT_LOAD_BATCH_SIZE = 500;

    /** 读取绑定、策略、知识库、文档和分块的业务事实。 */
    private final IRagRepository repository;
    /** 生成 Dense 查询向量。 */
    private final EmbeddingPort embeddingPort;
    /** 生成确定性 Sparse 查询特征。 */
    private final SparseEncoderPort sparseEncoderPort;
    /** 在知识库代际范围内执行 Dense/Sparse 召回。 */
    private final VectorStorePort vectorStorePort;
    /** 对融合候选执行语义重排。 */
    private final RerankerPort rerankerPort;
    /** 持久化不含正文的检索审计快照。 */
    private final RagRetrievalAuditPort auditPort;
    /** 统一估算最终上下文 Token 占用。 */
    private final TokenCounter tokenCounter;

    /**
     * 使用平台默认字符 Token 估算器创建生产检索服务。
     * @param repository RAG 业务仓储端口
     * @param embeddingPort 稠密查询向量生成端口
     * @param sparseEncoderPort 稀疏词项编码端口
     * @param vectorStorePort 向量候选检索端口
     * @param rerankerPort 候选重排端口
     * @param auditPort 检索结果与引用审计端口
     */
    @Autowired
    public RagRetrievalService(IRagRepository repository,
                               EmbeddingPort embeddingPort,
                               SparseEncoderPort sparseEncoderPort,
                               VectorStorePort vectorStorePort,
                               RerankerPort rerankerPort,
                               RagRetrievalAuditPort auditPort) {
        this(repository, embeddingPort, sparseEncoderPort, vectorStorePort, rerankerPort, auditPort,
                new CharacterTokenCounter());
    }

    /**
     * 使用指定 Token 计数器创建检索服务，用于确定性预算测试。
     * @param repository RAG 业务仓储端口
     * @param embeddingPort 稠密查询向量生成端口
     * @param sparseEncoderPort 稀疏词项编码端口
     * @param vectorStorePort 向量候选检索端口
     * @param rerankerPort 候选重排端口
     * @param auditPort 检索结果与引用审计端口
     * @param tokenCounter 上下文 Token 计数器
     */
    RagRetrievalService(IRagRepository repository,
                        EmbeddingPort embeddingPort,
                        SparseEncoderPort sparseEncoderPort,
                        VectorStorePort vectorStorePort,
                        RerankerPort rerankerPort,
                        RagRetrievalAuditPort auditPort,
                        TokenCounter tokenCounter) {
        this.repository = repository;
        this.embeddingPort = embeddingPort;
        this.sparseEncoderPort = sparseEncoderPort;
        this.vectorStorePort = vectorStorePort;
        this.rerankerPort = rerankerPort;
        this.auditPort = auditPort;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 使用运行时可信身份执行一次同步检索并写入审计。
     * <p>检索审计写入失败只会标记结果降级，不会丢弃已完成的引用。
     * 业务失败会在可用查询存在时尝试记录失败审计，随后保留原异常向上抛出。</p>
     *
     * @param request 包含可信身份、运行目标、查询、Token 预算和绑定快照的请求
     * @return 经资源范围复核和 Token 预算裁剪的引用结果
     */
    public RagRetrievalResult retrieve(RagRetrievalRequest request) {
        long started = System.nanoTime();
        String retrievalId = "ret_" + UUID.randomUUID().toString().replace("-", "");
        String query = null;
        AuditState audit = new AuditState();
        AiLog.info(AiLog.rag().retrieveStarted(request.tenantId(), request.userId(), request.sessionId(),
                request.runId(), retrievalId, request.targetType().name(), request.targetId(),
                request.query() == null ? 0 : request.query().length())
                .field(AiLogFields.TRACE_ID, request.traceId()));
        try {
            long normalizeStarted = System.nanoTime();
            query = normalizeQuery(request.query());
            logStage(request, retrievalId, "query_normalize", "检索问题规范化完成",
                    "completed", elapsedMs(normalizeStarted), request.query().length(), query.length());
            RagRetrievalResult result = retrieveInternal(request, retrievalId, query, started, audit);
            long auditStarted = System.nanoTime();
            boolean auditSaved = recordAudit(request, query, result,
                    result.citations().isEmpty() ? "empty" : "success", null, audit);
            long auditMs = elapsedMs(auditStarted);
            logStage(request, retrievalId, "audit_persist",
                    auditSaved ? "检索审计记录已保存" : "检索审计写入失败，主检索结果继续返回",
                    auditSaved ? "completed" : "degraded", auditMs, 1, auditSaved ? 1 : 0);
            RagRetrievalResult completed = (auditSaved ? result : result.withDegradation("audit_write_failed"))
                    .withCompletionTimings(auditMs, elapsedMs(started));
            AiLog.info(AiLog.rag().retrieveCompleted(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), retrievalId, request.targetType().name(), request.targetId(),
                    audit.executedBindingCount(), audit.mergedCandidateCount(), completed.citations().size(),
                    completed.estimatedTokenCount(), completed.degraded(), completed.metrics().serviceMs())
                    .field(AiLogFields.TRACE_ID, request.traceId())
                    .field("candidateBindings", audit.candidateBindingCount())
                    .field("resolvedBindings", audit.resolvedBindingCount())
                    .field("pipelineMs", result.metrics().totalMs())
                    .field("auditMs", auditMs)
                    .field("serviceMs", completed.metrics().totalMs()));
            if (completed.degraded()) {
                AiLog.warn(AiLog.rag().retrieveDegraded(request.tenantId(), request.userId(),
                        request.sessionId(), request.runId(), retrievalId, request.targetType().name(),
                        request.targetId(), String.join(",", completed.degradationReasons()),
                        completed.metrics().serviceMs(), null).field(AiLogFields.TRACE_ID, request.traceId()));
            }
            return completed;
        } catch (RuntimeException exception) {
            RagRetrievalResult failed = RagRetrievalResult.empty(retrievalId, elapsedMs(started));
            if (query != null) {
                long auditStarted = System.nanoTime();
                boolean auditSaved = recordAudit(request, query, failed, "failed", exception, audit);
                logStage(request, retrievalId, "audit_persist",
                        auditSaved ? "失败检索审计记录已保存" : "失败检索审计写入失败",
                        auditSaved ? "completed" : "degraded", elapsedMs(auditStarted), 1, auditSaved ? 1 : 0);
            }
            String errorCode = exception instanceof AppException appException
                    ? appException.getCode() : "RAG_RETRIEVAL_FAILED";
            AiLog.error(AiLog.rag().retrieveFailed(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), retrievalId, request.targetType().name(), request.targetId(), errorCode,
                    elapsedMs(started), exception).field(AiLogFields.TRACE_ID, request.traceId()));
            throw exception;
        }
    }

    /**
     * 串联绑定解析、双路召回、融合、重排、扩展和引用预算。
     * @param request 已通过构造校验的检索请求
     * @param retrievalId 本次检索的唯一标识
     * @param query 规范化后的检索问题
     * @param started 用于计算完整耗时的单调时钟起点
     * @param audit 不含正文的配置与执行审计状态
     * @return 尚未补充审计耗时的检索结果
     */
    private RagRetrievalResult retrieveInternal(RagRetrievalRequest request, String retrievalId, String query,
                                                long started, AuditState audit) {
        Aggregate aggregate = new Aggregate(request.diagnosticsEnabled());
        long configurationStarted = System.nanoTime();
        List<RagAgentBindingEntity> targetBindings = repository.listBindings(request.tenantId(), request.targetType(),
                request.targetId());
        audit.candidateBindingCount = targetBindings.size();
        List<RagAgentBindingEntity> bindings = selectRunBindings(request, targetBindings);
        logStage(request, retrievalId, "binding_lookup", "运行目标绑定查询完成", "completed",
                elapsedMs(configurationStarted), request.bindingIds().isEmpty()
                        ? targetBindings.size() : request.bindingIds().size(), bindings.size());
        if (bindings.isEmpty()) {
            aggregate.configurationMs = elapsedMs(configurationStarted);
            logStage(request, retrievalId, "binding_resolve", "没有可执行的知识库绑定，检索返回空结果",
                    "skipped", aggregate.configurationMs, 0, 0);
            return emptyResult(retrievalId, started, aggregate);
        }
        if (bindings.size() > MAX_BINDINGS) {
            throw new AppException("RAG_BINDING_LIMIT_EXCEEDED", "单个运行目标的知识库绑定不能超过32个");
        }

        List<ResolvedBinding> resolved = resolveBindings(request, bindings);
        aggregate.configurationMs = elapsedMs(configurationStarted);
        audit.capture(resolved);
        logStage(request, retrievalId, "binding_resolve", "知识库、检索策略与访问权限解析完成",
                resolved.isEmpty() ? "skipped" : "completed", aggregate.configurationMs,
                bindings.size(), resolved.size());
        if (resolved.isEmpty()) {
            return emptyResult(retrievalId, started, aggregate);
        }

        long rewriteStarted = System.nanoTime();
        long rewriteRequested = resolved.stream().filter(value -> value.profile().queryRewriteEnabled()).count();
        resolved.stream().filter(value -> value.profile().queryRewriteEnabled())
                .map(value -> "query_rewrite_unavailable:" + value.profile().profileId()).distinct()
                .forEach(aggregate.degradationReasons::add);
        logStage(request, retrievalId, "query_rewrite", rewriteRequested == 0
                        ? "检索问题改写未启用，使用规范化后的原问题"
                        : "检索问题改写配置已开启但当前无改写器，按原问题降级执行",
                rewriteRequested == 0 ? "skipped" : "degraded", elapsedMs(rewriteStarted),
                resolved.size(), 0);
        List<ResolvedBinding> active = new ArrayList<>(resolved);
        Timed<List<Float>> dense = Timed.empty(List.of());
        if (active.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.SPARSE)) {
            long denseStarted = System.nanoTime();
            try {
                dense = timed(() -> embeddingPort.embed(new EmbeddingPort.EmbeddingCommand(request.tenantId(),
                        request.traceId(), EmbeddingPort.EmbeddingInputType.QUERY, List.of(query))).vectors().get(0));
                logStage(request, retrievalId, "dense_embedding", "Dense查询向量生成完成",
                        "completed", dense.elapsedMs(), 1, dense.value().size());
            } catch (RuntimeException exception) {
                failIfRequired(active, RagRetrievalMode.SPARSE, exception);
                aggregate.degradationReasons.add("dense_unavailable");
                active.removeIf(value -> value.profile().mode() != RagRetrievalMode.SPARSE);
                logStageWarn(request, retrievalId, "dense_embedding", "Dense查询向量生成失败，已移除依赖Dense的可选绑定",
                        elapsedMs(denseStarted), 1, 0, errorCode(exception), exception);
            }
        } else {
            logStage(request, retrievalId, "dense_embedding", "当前检索策略不需要Dense向量",
                    "skipped", 0L, 0, 0);
        }
        Timed<SparseEncoderPort.SparseVector> sparse = Timed.empty(null);
        if (active.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.DENSE)) {
            long sparseStarted = System.nanoTime();
            try {
                sparse = timed(() -> sparseEncoderPort.encode(new SparseEncoderPort.SparseEncodingCommand(
                        request.tenantId(), request.traceId(), List.of(query),
                        DeterministicSparseEncoder.VOCABULARY_REVISION)).vectors().get(0));
                logStage(request, retrievalId, "sparse_encode", "Sparse查询特征生成完成",
                        "completed", sparse.elapsedMs(), 1,
                        sparse.value() == null ? 0 : sparse.value().weights().size());
            } catch (RuntimeException exception) {
                failIfRequired(active, RagRetrievalMode.DENSE, exception);
                aggregate.degradationReasons.add("sparse_unavailable");
                active.removeIf(value -> value.profile().mode() != RagRetrievalMode.DENSE);
                logStageWarn(request, retrievalId, "sparse_encode", "Sparse查询特征生成失败，已移除依赖Sparse的可选绑定",
                        elapsedMs(sparseStarted), 1, 0, errorCode(exception), exception);
            }
        } else {
            logStage(request, retrievalId, "sparse_encode", "当前检索策略不需要Sparse特征",
                    "skipped", 0L, 0, 0);
        }
        if (active.isEmpty()) {
            return new RagRetrievalResult(retrievalId, List.of(), 0, true, aggregate.degradationReasons,
                    new RagRetrievalResult.Metrics(0, 0, 0, 0, dense.elapsedMs(), 0, 0, 0, 0,
                            elapsedMs(started), aggregate.configurationMs, aggregate.hydrationMs,
                            aggregate.assemblyMs, 0, 0), aggregate.diagnostics.result());
        }

        List<RankedChunk> ranked = new ArrayList<>();
        audit.executedBindingCount = active.size();
        for (ResolvedBinding value : active) {
            try {
                ranked.addAll(retrieveBinding(request, retrievalId, query, value,
                        dense.value(), sparse.value(), aggregate));
            } catch (RuntimeException exception) {
                if (value.binding().required() || isScopeViolation(exception)) {
                    throw exception;
                }
                aggregate.degradationReasons.add("optional_binding_failed:" + value.binding().bindingId());
                logBindingStageWarn(request, retrievalId, value, "binding_retrieval",
                        "可选知识库绑定检索失败，继续执行其他绑定", null, 1, 0,
                        errorCode(exception), exception);
            }
        }

        long mergeStarted = System.nanoTime();
        List<RankedChunk> merged = ranked.stream()
                .collect(Collectors.toMap(value -> value.chunk().chunkId(), value -> value,
                        this::better, LinkedHashMap::new)).values().stream()
                .sorted(RANKING).toList();
        audit.mergedCandidateCount = merged.size();
        logStage(request, retrievalId, "cross_binding_merge", "跨知识库候选去重与合并完成",
                "completed", elapsedMs(mergeStarted), ranked.size(), merged.size());
        long assemblyStarted = System.nanoTime();
        long hydrationBeforeAssembly = aggregate.hydrationMs;
        List<RagRetrievalResult.Citation> citations = assembleCitations(
                request, retrievalId, merged, active, aggregate);
        aggregate.assemblyMs += Math.max(0L, elapsedMs(assemblyStarted)
                - (aggregate.hydrationMs - hydrationBeforeAssembly));
        int tokens = citations.stream().mapToInt(value -> tokenCounter.estimate(value.context())).sum();
        AiLog.info(stageRecord(request, retrievalId, "citation_assembly", "引用封装与上下文Token预算完成",
                citations.isEmpty() ? "empty" : "completed", elapsedMs(assemblyStarted),
                merged.size(), citations.size()).field("tokens", tokens)
                .field("globalTokenBudget", request.maxContextTokens())
                .field("expandedChunkCount", aggregate.expandedChunkCount)
                .field("finalTopKRejected", aggregate.finalTopKRejected)
                .field("tokenBudgetRejected", aggregate.tokenBudgetRejected));
        long totalMs = elapsedMs(started);
        RagRetrievalResult.Metrics metrics = new RagRetrievalResult.Metrics(aggregate.denseCandidates,
                aggregate.sparseCandidates, aggregate.fusionCandidates, aggregate.rerankCandidates,
                dense.elapsedMs(), aggregate.denseMs, aggregate.sparseMs, aggregate.fusionMs,
                aggregate.rerankMs, totalMs, aggregate.configurationMs, aggregate.hydrationMs,
                aggregate.assemblyMs, 0, 0);
        return new RagRetrievalResult(retrievalId, citations, tokens, !aggregate.degradationReasons.isEmpty(),
                aggregate.degradationReasons, metrics, aggregate.diagnostics.result());
    }

    /**
     * 写入检索审计；审计失败只返回 false，不反向掩盖成功检索。
     * @param request 检索请求中的可信身份与运行范围
     * @param query 规范化后的查询
     * @param result 待审计的检索结果
     * @param status success、empty、failed 或 cancelled
     * @param exception 失败审计对应的异常，成功或空结果时为空
     * @param state 实际生效的检索配置与执行计数
     * @return 审计写入成功时返回 {@code true}
     */
    private boolean recordAudit(RagRetrievalRequest request, String query, RagRetrievalResult result,
                                String status, RuntimeException exception, AuditState state) {
        try {
            String errorCode = exception instanceof AppException appException
                    ? appException.getCode() : (exception == null ? null : "RAG_RETRIEVAL_FAILED");
            auditPort.record(new RagRetrievalAuditCommand(result.retrievalId(), request.tenantId(), request.userId(),
                    request.sessionId(), request.runId(), request.targetId(), state.profileId(),
                    state.profileRevision, query, state.denseEnabled, state.sparseEnabled, state.rerankEnabled,
                    result, status, errorCode, exception == null ? null : exception.getClass().getSimpleName(),
                    request.traceId(), state.snapshot(request)));
            return true;
        } catch (RuntimeException auditException) {
            AiLog.error(AiLog.rag().retrieveFailed(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), result.retrievalId(), request.targetType().name(), request.targetId(),
                    "RAG_AUDIT_WRITE_FAILED", null, auditException)
                    .field(AiLogFields.TRACE_ID, request.traceId()).field(AiLogFields.STAGE, "audit"));
            return false;
        }
    }

    /** 返回包含真实阶段耗时和诊断信息的非降级空结果。 */
    private RagRetrievalResult emptyResult(String retrievalId, long started, Aggregate aggregate) {
        return new RagRetrievalResult(retrievalId, List.of(), 0, false, List.of(),
                new RagRetrievalResult.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0,
                        elapsedMs(started), aggregate.configurationMs, aggregate.hydrationMs,
                        aggregate.assemblyMs, 0, 0), aggregate.diagnostics.result());
    }

    /** 外部能力失败若影响必需绑定则立即失败，否则允许移除可选绑定。 */
    private void failIfRequired(List<ResolvedBinding> bindings, RagRetrievalMode unaffectedMode,
                                RuntimeException exception) {
        boolean requiredAffected = bindings.stream().anyMatch(value -> value.binding().required()
                && value.profile().mode() != unaffectedMode);
        if (requiredAffected) throw exception;
    }

    /**
     * 将绑定解析为可检索、索引代际有效、配置存在且当前用户可见的执行单元。
     * @param request 包含租户、用户与目标范围的检索请求
     * @param bindings 已按运行快照收窄的绑定
     * @return 可进入召回阶段的绑定、知识库和检索配置组合
     */
    private List<ResolvedBinding> resolveBindings(RagRetrievalRequest request,
                                                   List<RagAgentBindingEntity> bindings) {
        List<ResolvedBinding> result = new ArrayList<>();
        for (RagAgentBindingEntity binding : bindings) {
            Optional<RagKnowledgeBaseEntity> knowledgeBase = repository.findKnowledgeBase(request.tenantId(),
                    binding.knowledgeBaseId());
            Optional<RagRetrievalProfileEntity> profile = repository.findRetrievalProfile(request.tenantId(),
                    binding.retrievalProfileId());
            boolean usable = knowledgeBase.isPresent() && knowledgeBase.get().status().searchable()
                    && knowledgeBase.get().currentGeneration() > 0 && profile.isPresent()
                    && (knowledgeBase.get().visibility() != RagVisibility.PRIVATE
                    || knowledgeBase.get().ownerUserId().equals(request.userId()));
            if (!usable) {
                if (binding.required()) {
                    throw new AppException("RAG_REQUIRED_BINDING_UNAVAILABLE", "必需知识库或检索配置当前不可用");
                }
                continue;
            }
            result.add(new ResolvedBinding(binding, knowledgeBase.get(), profile.get()));
        }
        return List.copyOf(result);
    }

    /**
     * 按运行创建时冻结的绑定集合收窄目标绑定；快照中任一绑定失效时明确失败。
     * @param request 可能携带运行绑定快照的检索请求
     * @param targetBindings 当前数据库中目标的全部启用绑定
     * @return 运行允许使用的有序绑定
     */
    private List<RagAgentBindingEntity> selectRunBindings(RagRetrievalRequest request,
                                                           List<RagAgentBindingEntity> targetBindings) {
        if (request.bindingIds().isEmpty()) {
            return targetBindings;
        }
        Set<String> selected = new LinkedHashSet<>(request.bindingIds());
        List<RagAgentBindingEntity> result = targetBindings.stream()
                .filter(binding -> selected.contains(binding.bindingId()))
                .toList();
        if (result.size() != selected.size()) {
            throw new AppException("RAG_RUN_BINDING_SNAPSHOT_STALE",
                    "本轮固化的知识库绑定已失效，不能静默改用其他绑定");
        }
        return result;
    }

    /**
     * 在单个绑定范围内执行召回、融合、业务回表、去重和可选重排。
     * @param request 检索请求的可信身份与链路信息
     * @param retrievalId 本次检索标识
     * @param query 规范化后的检索问题
     * @param resolved 已通过权限、状态和索引代际校验的绑定
     * @param denseVector 本请求共用的稠密查询向量
     * @param sparseVector 本请求共用的稀疏查询向量
     * @param aggregate 累计候选数、耗时、降级原因与诊断的容器
     * @return 已回表校验并按最终分数排序的业务候选
     */
    private List<RankedChunk> retrieveBinding(RagRetrievalRequest request,
                                              String retrievalId,
                                              String query,
                                              ResolvedBinding resolved,
                                              List<Float> denseVector,
                                              SparseEncoderPort.SparseVector sparseVector,
                                              Aggregate aggregate) {
        RagRetrievalProfileEntity profile = resolved.profile();
        Set<VectorStorePort.KnowledgeBaseScope> scope = Set.of(new VectorStorePort.KnowledgeBaseScope(
                resolved.knowledgeBase().knowledgeBaseId(), resolved.knowledgeBase().currentGeneration()));
        List<VectorStorePort.VectorSearchHit> denseHits = List.of();
        List<VectorStorePort.VectorSearchHit> sparseHits = List.of();
        if (profile.mode() != RagRetrievalMode.SPARSE) {
            Timed<List<VectorStorePort.VectorSearchHit>> call = timed(() -> vectorStorePort.search(request.tenantId(),
                    new VectorStorePort.VectorSearchCommand(scope, denseVector, null, profile.denseTopK())));
            denseHits = call.value();
            aggregate.denseMs += call.elapsedMs();
            aggregate.denseCandidates += denseHits.size();
            aggregate.diagnostics.captureRaw(resolved, "dense_raw", denseHits, true);
            logBindingStage(request, retrievalId, resolved, "dense_recall",
                    "Dense向量召回完成", "completed", call.elapsedMs(), profile.denseTopK(), denseHits.size());
        } else {
            logBindingStage(request, retrievalId, resolved, "dense_recall",
                    "当前Binding为Sparse模式，跳过Dense召回", "skipped", 0L, 0, 0);
        }
        if (profile.mode() != RagRetrievalMode.DENSE) {
            Timed<List<VectorStorePort.VectorSearchHit>> call = timed(() -> vectorStorePort.search(request.tenantId(),
                    new VectorStorePort.VectorSearchCommand(scope, List.of(), sparseVector, profile.sparseTopK())));
            sparseHits = call.value();
            aggregate.sparseMs += call.elapsedMs();
            aggregate.sparseCandidates += sparseHits.size();
            aggregate.diagnostics.captureRaw(resolved, "sparse_raw", sparseHits, false);
            logBindingStage(request, retrievalId, resolved, "sparse_recall",
                    "Sparse关键词召回完成", "completed", call.elapsedMs(), profile.sparseTopK(), sparseHits.size());
        } else {
            logBindingStage(request, retrievalId, resolved, "sparse_recall",
                    "当前Binding为Dense模式，跳过Sparse召回", "skipped", 0L, 0, 0);
        }
        long fusionStarted = System.nanoTime();
        List<ScoredHit> fused = fuse(profile, denseHits, sparseHits);
        long fusionMs = elapsedMs(fusionStarted);
        aggregate.fusionMs += fusionMs;
        aggregate.fusionCandidates += fused.size();
        aggregate.diagnostics.captureFused(resolved, "fusion", fused);
        AiLog.info(bindingStageRecord(request, retrievalId, resolved, "fusion",
                "Dense与Sparse候选融合、阈值过滤和TopK裁剪完成",
                fused.isEmpty() ? "empty" : "completed", fusionMs,
                denseHits.size() + sparseHits.size(), fused.size())
                .field("fusionStrategy", profile.fusionStrategy().name())
                .field("fusionTopK", profile.fusionTopK()));
        if (fused.isEmpty()) return List.of();

        long hydrationStarted = System.nanoTime();
        Map<String, RagChunkEntity> chunks = loadChunks(request.tenantId(),
                        fused.stream().map(value -> value.hit().chunkId()).toList(), aggregate).stream()
                .collect(Collectors.toMap(RagChunkEntity::chunkId, value -> value));
        logBindingStage(request, retrievalId, resolved, "chunk_hydration",
                "候选分块从业务数据库加载完成", "completed", elapsedMs(hydrationStarted),
                fused.size(), chunks.size());
        Map<String, Optional<RagDocumentEntity>> missingChunkDocuments = new LinkedHashMap<>();
        List<RankedChunk> candidates = new ArrayList<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        int filteredRank = 0;
        int tombstoneCount = 0;
        int duplicateCount = 0;
        long filterStarted = System.nanoTime();
        for (int fusedIndex = 0; fusedIndex < fused.size(); fusedIndex++) {
            ScoredHit value = fused.get(fusedIndex);
            RagChunkEntity chunk = chunks.get(value.hit().chunkId());
            if (chunk == null && isLegitimateDeletingHit(request.tenantId(), resolved, value.hit(),
                    missingChunkDocuments)) {
                aggregate.diagnostics.capture(resolved, "candidate_filter", fusedIndex + 1, value,
                        null, "discarded_tombstone");
                tombstoneCount++;
                continue;
            }
            validateChunkScope(resolved, value.hit(), chunk);
            if (profile.deduplicateEnabled() && !contentHashes.add(chunk.contentHash())) {
                aggregate.diagnostics.capture(resolved, "candidate_filter", fusedIndex + 1, value,
                        null, "discarded_duplicate_content_hash");
                duplicateCount++;
                continue;
            }
            RankedChunk candidate = new RankedChunk(resolved, chunk, value.denseScore(), value.sparseScore(),
                    value.fusionScore(), null);
            candidates.add(candidate);
            aggregate.diagnostics.capture(candidate, "candidate_filter", ++filteredRank, "kept");
        }
        AiLog.info(bindingStageRecord(request, retrievalId, resolved, "candidate_filter",
                "候选租户范围、活动版本、删除态和内容去重过滤完成",
                candidates.isEmpty() ? "empty" : "completed", elapsedMs(filterStarted),
                fused.size(), candidates.size())
                .field("tombstoneRejected", tombstoneCount)
                .field("duplicateRejected", duplicateCount));
        if (!profile.rerankEnabled() || candidates.isEmpty()) {
            List<RankedChunk> output = candidates.stream().limit(profile.finalTopK()).toList();
            aggregate.diagnostics.captureRanked("pre_assembly", output, "kept_without_rerank");
            logBindingStage(request, retrievalId, resolved, "rerank",
                    candidates.isEmpty() ? "没有候选，跳过Rerank" : "当前检索策略未启用Rerank",
                    "skipped", 0L, candidates.size(), output.size());
            return output;
        }
        int rerankInputSize = Math.min(profile.rerankTopK(), candidates.size());
        List<RankedChunk> rerankInput = candidates.subList(0, rerankInputSize);
        aggregate.diagnostics.captureRanked("rerank_input", rerankInput, "sent_to_reranker");
        try {
            Timed<RerankerPort.RerankResult> reranked = timed(() -> rerankerPort.rerank(
                    new RerankerPort.RerankCommand(request.tenantId(), request.traceId(), query,
                            rerankInput.stream().map(value -> new RerankerPort.Candidate(
                                    value.chunk().chunkId(), value.chunk().content())).toList(), rerankInputSize)));
            aggregate.rerankMs += reranked.elapsedMs();
            aggregate.rerankCandidates += rerankInputSize;
            Map<String, RankedChunk> byChunk = rerankInput.stream()
                    .collect(Collectors.toMap(value -> value.chunk().chunkId(), value -> value));
            List<RankedChunk> output = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (RerankerPort.ScoredCandidate score : reranked.value().candidates()) {
                RankedChunk original = byChunk.get(score.chunkId());
                if (original == null || !seen.add(score.chunkId())) {
                    throw new AppException("RAG_RERANK_SCOPE_INVALID", "Rerank返回了未知或重复候选");
                }
                output.add(original.withRerank(score.score()));
            }
            List<RankedChunk> rerankOutput = output.stream().sorted(RANKING).limit(profile.finalTopK()).toList();
            aggregate.diagnostics.captureRanked("rerank_output", rerankOutput, "kept_after_rerank");
            logBindingStage(request, retrievalId, resolved, "rerank",
                    "候选重排和最终TopK裁剪完成", "completed", reranked.elapsedMs(),
                    rerankInputSize, rerankOutput.size());
            return rerankOutput;
        } catch (RuntimeException exception) {
            aggregate.degradationReasons.add("rerank_fallback:" + profile.profileId());
            List<RankedChunk> fallback = candidates.stream().limit(profile.finalTopK()).toList();
            aggregate.diagnostics.captureRanked("rerank_output", fallback, "fallback_without_rerank_score");
            logBindingStageWarn(request, retrievalId, resolved, "rerank",
                    "Rerank失败，已按融合排序降级返回", null, rerankInputSize, fallback.size(),
                    errorCode(exception), exception);
            return fallback;
        }
    }

    /**
     * 按配置合并稠密与稀疏命中，再执行分数阈值和融合 TopK。
     * @param profile 本绑定生效的检索配置
     * @param denseHits 稠密召回原始命中
     * @param sparseHits 稀疏召回原始命中
     * @return 按融合分数排序且已执行阈值与数量限制的候选
     */
    private List<ScoredHit> fuse(RagRetrievalProfileEntity profile,
                                 List<VectorStorePort.VectorSearchHit> denseHits,
                                 List<VectorStorePort.VectorSearchHit> sparseHits) {
        Map<String, MutableScore> values = new LinkedHashMap<>();
        for (int index = 0; index < denseHits.size(); index++) {
            VectorStorePort.VectorSearchHit hit = denseHits.get(index);
            values.computeIfAbsent(hit.chunkId(), ignored -> new MutableScore(hit)).dense(hit.score(), index + 1);
        }
        for (int index = 0; index < sparseHits.size(); index++) {
            VectorStorePort.VectorSearchHit hit = sparseHits.get(index);
            MutableScore score = values.computeIfAbsent(hit.chunkId(), ignored -> new MutableScore(hit));
            validateSameHitScope(score.hit, hit);
            score.sparse(hit.score(), index + 1);
        }
        List<ScoredHit> result = values.values().stream().map(value -> value.freeze(profile))
                .filter(value -> profile.scoreThreshold() == null
                        || value.fusionScore() >= profile.scoreThreshold().doubleValue())
                .sorted(SCORED_HIT_ORDER).limit(profile.fusionTopK()).toList();
        return List.copyOf(result);
    }

    /**
     * 复核文档活动快照，扩展父分块与相邻分块，并同时执行全局与绑定 Token 预算。
     * @param request 提供全局 Token 预算与租户范围的检索请求
     * @param retrievalId 用于生成稳定引用标识的检索标识
     * @param ranked 跨绑定去重后的有序候选
     * @param active 本次实际执行的绑定，用于计算绑定独立预算
     * @param aggregate 累计回表耗时、扩展数和预算拒绝数的容器
     * @return 通过活动版本复核并适合注入模型上下文的引用
     */
    private List<RagRetrievalResult.Citation> assembleCitations(RagRetrievalRequest request,
                                                                 String retrievalId,
                                                                 List<RankedChunk> ranked,
                                                                 List<ResolvedBinding> resolved,
                                                                 Aggregate aggregate) {
        if (ranked.isEmpty()) return List.of();
        int globalBudget = request.maxContextTokens();
        Map<String, Integer> bindingBudget = resolved.stream().collect(Collectors.toMap(
                value -> value.binding().bindingId(), value -> Math.min(value.binding().maxTokens(),
                        value.profile().maxContextTokens())));
        Map<String, Integer> bindingUsed = new LinkedHashMap<>();
        Map<String, Integer> bindingCitationCount = new LinkedHashMap<>();
        Map<String, RagDocumentEntity> documents = loadDocuments(request.tenantId(), ranked, aggregate);
        Set<String> emittedChunks = new LinkedHashSet<>();
        List<RagRetrievalResult.Citation> citations = new ArrayList<>();
        int used = 0;
        for (int rankedIndex = 0; rankedIndex < ranked.size(); rankedIndex++) {
            RankedChunk value = ranked.get(rankedIndex);
            String bindingId = value.resolved().binding().bindingId();
            if (bindingCitationCount.getOrDefault(bindingId, 0)
                    >= value.resolved().profile().finalTopK()) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_final_topk");
                aggregate.finalTopKRejected++;
                continue;
            }
            RagDocumentEntity document = Optional.ofNullable(documents.get(value.chunk().documentId()))
                    .orElseThrow(() -> new AppException("RAG_DOCUMENT_MISSING", "引用文档不存在"));
            if (document.status() == RagDocumentStatus.DELETING
                    || document.status() == RagDocumentStatus.DELETED) {
                validateDeletingDocumentScope(value, document);
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_document_tombstone");
                continue;
            }
            validateDocumentScope(value, document);
            List<RagChunkEntity> contextChunks = expandContext(request.tenantId(), value, aggregate);
            aggregate.expandedChunkCount += Math.max(0, contextChunks.size() - 1);
            contextChunks = contextChunks.stream().filter(chunk -> !emittedChunks.contains(chunk.chunkId())).toList();
            String context = contextChunks.stream().map(RagChunkEntity::content).collect(Collectors.joining("\n\n"));
            int tokens = tokenCounter.estimate(context);
            int localUsed = bindingUsed.getOrDefault(bindingId, 0);
            if (tokens < 1) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_empty_context");
                continue;
            }
            if (used + tokens > globalBudget) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_global_token_budget");
                aggregate.tokenBudgetRejected++;
                continue;
            }
            if (localUsed + tokens > bindingBudget.get(bindingId)) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_binding_token_budget");
                aggregate.tokenBudgetRejected++;
                continue;
            }
            used += tokens;
            bindingUsed.put(bindingId, localUsed + tokens);
            contextChunks.forEach(chunk -> emittedChunks.add(chunk.chunkId()));
            int rank = citations.size() + 1;
            citations.add(new RagRetrievalResult.Citation(citationId(retrievalId, value.chunk(), rank), rank,
                    value.chunk().knowledgeBaseId(), value.chunk().documentId(), document.displayName(),
                    value.chunk().versionId(), value.chunk().versionNumber(), value.chunk().generation(),
                    value.chunk().chunkId(), context, value.chunk().pageNumber(), value.chunk().headingPath(),
                    value.chunk().contentHash(), value.denseScore(), value.sparseScore(), value.fusionScore(),
                    value.rerankScore(), Map.of("binding_id", bindingId,
                            "profile_id", value.resolved().profile().profileId(),
                            "profile_revision", Long.toString(value.resolved().profile().revision()))));
            bindingCitationCount.merge(bindingId, 1, Integer::sum);
            aggregate.diagnostics.capture(value, "context_budget", rank, "accepted_citation");
        }
        return List.copyOf(citations);
    }

    /** 分批回表加载引用文档，并拒绝仓储返回重复业务 ID。 */
    private Map<String, RagDocumentEntity> loadDocuments(String tenantId, List<RankedChunk> ranked,
                                                          Aggregate aggregate) {
        List<String> documentIds = ranked.stream().map(value -> value.chunk().documentId()).distinct().toList();
        Map<String, RagDocumentEntity> documents = new LinkedHashMap<>();
        for (int offset = 0; offset < documentIds.size(); offset += DOCUMENT_LOAD_BATCH_SIZE) {
            List<String> batch = documentIds.subList(offset,
                    Math.min(offset + DOCUMENT_LOAD_BATCH_SIZE, documentIds.size()));
            Timed<List<RagDocumentEntity>> loaded = timed(() -> repository.listDocumentsByIds(tenantId, batch));
            aggregate.hydrationMs += loaded.elapsedMs();
            for (RagDocumentEntity document : loaded.value()) {
                RagDocumentEntity previous = documents.putIfAbsent(document.documentId(), document);
                if (previous != null) {
                    throw new AppException("RAG_DOCUMENT_SCOPE_VIOLATION", "批量文档查询返回重复业务ID");
                }
            }
        }
        return documents;
    }

    /**
     * 从主命中扩展父分块和指定深度的相邻分块，并按原文顺序排列。
     * @param tenantId 候选所属租户
     * @param value 已回表校验的主命中候选
     * @param aggregate 累计回表耗时与扩展分块数的容器
     * @return 只包含同租户、知识库、文档、版本和索引代际的上下文分块
     */
    private List<RagChunkEntity> expandContext(String tenantId, RankedChunk value, Aggregate aggregate) {
        int window = value.resolved().profile().neighborWindow();
        LinkedHashMap<String, RagChunkEntity> chunks = new LinkedHashMap<>();
        RagChunkEntity main = value.chunk();
        chunks.put(main.chunkId(), main);
        if (hasText(main.parentChunkId())) {
            for (RagChunkEntity parent : loadChunks(tenantId, List.of(main.parentChunkId()), aggregate)) {
                validateRelatedScope(main, parent);
                chunks.putIfAbsent(parent.chunkId(), parent);
            }
        }
        Set<String> frontier = neighborIds(main);
        for (int depth = 0; depth < window && !frontier.isEmpty(); depth++) {
            List<RagChunkEntity> loaded = loadChunks(tenantId, List.copyOf(frontier), aggregate);
            frontier = new LinkedHashSet<>();
            for (RagChunkEntity chunk : loaded) {
                validateRelatedScope(main, chunk);
                if (chunks.putIfAbsent(chunk.chunkId(), chunk) == null && depth + 1 < window) {
                    frontier.addAll(neighborIds(chunk));
                    frontier.removeAll(chunks.keySet());
                }
            }
        }
        return chunks.values().stream().sorted(Comparator.comparingInt(RagChunkEntity::chunkIndex)).toList();
    }

    /** 批量回表分块并累计水合耗时。 */
    private List<RagChunkEntity> loadChunks(String tenantId, List<String> chunkIds, Aggregate aggregate) {
        Timed<List<RagChunkEntity>> loaded = timed(() -> repository.listChunksByIds(tenantId, chunkIds));
        aggregate.hydrationMs += loaded.elapsedMs();
        return loaded.value();
    }

    /** 提取非空的前后相邻分块 ID。 */
    private Set<String> neighborIds(RagChunkEntity chunk) {
        Set<String> ids = new LinkedHashSet<>();
        if (hasText(chunk.previousChunkId())) ids.add(chunk.previousChunkId());
        if (hasText(chunk.nextChunkId())) ids.add(chunk.nextChunkId());
        return ids;
    }

    /** 核对向量命中与数据库分块属于同一知识库代际、文档和版本。 */
    private void validateChunkScope(ResolvedBinding resolved, VectorStorePort.VectorSearchHit hit,
                                    RagChunkEntity chunk) {
        if (chunk == null || !resolved.knowledgeBase().knowledgeBaseId().equals(chunk.knowledgeBaseId())
                || resolved.knowledgeBase().currentGeneration() != chunk.generation()
                || !hit.documentId().equals(chunk.documentId()) || !hit.versionId().equals(chunk.versionId())
                || !hit.chunkId().equals(chunk.chunkId())) {
            throw new AppException("RAG_CHUNK_SCOPE_VIOLATION", "向量命中与业务分块范围不一致");
        }
    }

    /**
     * 识别删除并发窗口内尚留向量、但业务分块已清理的合法删除态命中。
     * @param tenantId 命中所属租户
     * @param resolved 命中对应的可信绑定范围
     * @param hit 向量存储返回的候选
     * @param chunk 按命中标识回表的业务分块，已物理清理时为空
     * @return 仅当文档与版本已处于删除流程，且命中仍属于删除前活动快照时返回 {@code true}
     */
    private boolean isLegitimateDeletingHit(String tenantId, ResolvedBinding resolved,
                                             VectorStorePort.VectorSearchHit hit,
                                             Map<String, Optional<RagDocumentEntity>> documents) {
        Optional<RagDocumentEntity> candidate = documents.computeIfAbsent(hit.documentId(),
                documentId -> repository.findDocument(tenantId, documentId));
        if (candidate.isEmpty()) return false;
        RagDocumentEntity document = candidate.get();
        if (document.status() != RagDocumentStatus.DELETING && document.status() != RagDocumentStatus.DELETED) {
            return false;
        }
        if (!resolved.knowledgeBase().knowledgeBaseId().equals(hit.knowledgeBaseId())
                || !document.knowledgeBaseId().equals(hit.knowledgeBaseId())
                || !document.documentId().equals(hit.documentId())
                || resolved.knowledgeBase().currentGeneration() != hit.generation()) {
            return false;
        }
        if (document.status() == RagDocumentStatus.DELETING
                && (document.activeVersionId() != null && !document.activeVersionId().equals(hit.versionId())
                || document.activeGeneration() > 0 && document.activeGeneration() != hit.generation())) {
            return false;
        }
        return isTombstonedVersion(tenantId, document, hit.versionId(), hit.generation());
    }

    /** 父块和邻居不得越出租户、知识库、文档、版本或代际。 */
    private void validateRelatedScope(RagChunkEntity main, RagChunkEntity related) {
        if (!main.tenantId().equals(related.tenantId()) || !main.knowledgeBaseId().equals(related.knowledgeBaseId())
                || !main.documentId().equals(related.documentId()) || !main.versionId().equals(related.versionId())
                || main.generation() != related.generation()) {
            throw new AppException("RAG_NEIGHBOR_SCOPE_VIOLATION", "相邻分块超出主命中范围");
        }
    }

    /** 引用文档必须仍指向命中分块所在的活动版本和代际。 */
    private void validateDocumentScope(RankedChunk value, RagDocumentEntity document) {
        RagChunkEntity chunk = value.chunk();
        if (!document.status().searchable() || !chunk.knowledgeBaseId().equals(document.knowledgeBaseId())
                || !chunk.versionId().equals(document.activeVersionId())
                || chunk.generation() != document.activeGeneration()) {
            throw new AppException("RAG_DOCUMENT_SCOPE_VIOLATION", "引用文档已不属于当前活动索引快照");
        }
    }

    /** 删除态命中只能属于删除前的活动索引快照。 */
    private void validateDeletingDocumentScope(RankedChunk value, RagDocumentEntity document) {
        RagChunkEntity chunk = value.chunk();
        if (!chunk.knowledgeBaseId().equals(document.knowledgeBaseId())
                || document.activeVersionId() != null && !chunk.versionId().equals(document.activeVersionId())
                || document.activeGeneration() > 0 && chunk.generation() != document.activeGeneration()
                || !isTombstonedVersion(chunk.tenantId(), document, chunk.versionId(), chunk.generation())) {
            throw new AppException("RAG_DOCUMENT_SCOPE_VIOLATION", "删除态文档命中超出原活动索引范围");
        }
    }

    /** 确认命中版本已进入删除或已删除墓碑状态。 */
    private boolean isTombstonedVersion(String tenantId, RagDocumentEntity document,
                                         String versionId, long generation) {
        Optional<RagDocumentVersionEntity> version = repository.findDocumentVersion(tenantId, versionId);
        return version.isPresent()
                && version.get().versionId().equals(versionId)
                && version.get().documentId().equals(document.documentId())
                && version.get().knowledgeBaseId().equals(document.knowledgeBaseId())
                && version.get().generation() == generation
                && (version.get().status() == RagDocumentVersionStatus.DELETING
                || version.get().status() == RagDocumentVersionStatus.DELETED);
    }

    /** 同一 chunkId 的 Dense 与 Sparse 命中必须指向完全相同的资源范围。 */
    private void validateSameHitScope(VectorStorePort.VectorSearchHit left,
                                      VectorStorePort.VectorSearchHit right) {
        if (!left.knowledgeBaseId().equals(right.knowledgeBaseId())
                || !left.documentId().equals(right.documentId()) || !left.versionId().equals(right.versionId())
                || left.generation() != right.generation()) {
            throw new AppException("RAG_FUSION_SCOPE_VIOLATION", "Dense与Sparse命中范围不一致");
        }
    }

    /** 跨绑定重复 chunkId 保留最终排序更优的候选。 */
    private RankedChunk better(RankedChunk left, RankedChunk right) {
        return RANKING.compare(left, right) <= 0 ? left : right;
    }

    /** 压缩 Unicode 空白并执行查询长度上限。 */
    private String normalizeQuery(String query) {
        String normalized = query.strip().replaceAll("[\\p{Z}\\s]+", " ");
        if (normalized.isBlank() || normalized.length() > MAX_QUERY_CHARS) {
            throw new AppException("RAG_QUERY_INVALID", "检索问题为空或超过4096字符");
        }
        return normalized;
    }

    /** 引用 ID 绑定本次检索、租户、版本、分块与最终排名。 */
    private String citationId(String retrievalId, RagChunkEntity chunk, int rank) {
        return "cite_" + sha256(retrievalId + "|" + chunk.tenantId() + "|" + chunk.versionId()
                + "|" + chunk.chunkId() + "|" + rank)
                .substring(0, 24);
    }

    /** 使用 JVM 必备 SHA-256 生成稳定摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    /** 范围违规属于安全错误，任何可选绑定都不得吞掉。 */
    private boolean isScopeViolation(RuntimeException exception) {
        return exception instanceof AppException appException && appException.getCode() != null
                && appException.getCode().contains("SCOPE");
    }

    /** 生成一个不含问题正文和候选正文的检索阶段日志。 */
    private cn.bugstack.ai.types.observability.AiLogRecord stageRecord(
            RagRetrievalRequest request, String retrievalId, String stage, String message,
            String outcome, Long costMs, Integer inputCount, Integer outputCount) {
        return AiLog.rag().retrievalStage(request.tenantId(), request.userId(), request.sessionId(),
                        request.runId(), retrievalId, request.targetType().name(), request.targetId(),
                        stage, message, outcome, costMs, inputCount, outputCount)
                .field(AiLogFields.TRACE_ID, request.traceId());
    }

    /** 记录一个检索阶段完成、跳过或空结果事件。 */
    private void logStage(RagRetrievalRequest request, String retrievalId, String stage, String message,
                          String outcome, Long costMs, Integer inputCount, Integer outputCount) {
        AiLog.info(stageRecord(request, retrievalId, stage, message, outcome, costMs, inputCount, outputCount));
    }

    /** 生成带Binding、知识库和Profile范围的阶段日志。 */
    private cn.bugstack.ai.types.observability.AiLogRecord bindingStageRecord(
            RagRetrievalRequest request, String retrievalId, ResolvedBinding resolved,
            String stage, String message, String outcome, Long costMs,
            Integer inputCount, Integer outputCount) {
        return stageRecord(request, retrievalId, stage, message, outcome, costMs, inputCount, outputCount)
                .field("bindingId", resolved.binding().bindingId())
                .field("knowledgeBaseId", resolved.knowledgeBase().knowledgeBaseId())
                .field("generation", resolved.knowledgeBase().currentGeneration())
                .field("profileId", resolved.profile().profileId())
                .field("profileRevision", resolved.profile().revision())
                .field("required", resolved.binding().required());
    }

    /** 记录一个Binding范围的阶段事件。 */
    private void logBindingStage(RagRetrievalRequest request, String retrievalId, ResolvedBinding resolved,
                                 String stage, String message, String outcome, Long costMs,
                                 Integer inputCount, Integer outputCount) {
        AiLog.info(bindingStageRecord(request, retrievalId, resolved, stage, message,
                outcome, costMs, inputCount, outputCount));
    }

    /** 记录阶段降级；只保存稳定错误码和异常类型，不保存外部异常正文。 */
    private void logStageWarn(RagRetrievalRequest request, String retrievalId, String stage, String message,
                              Long costMs, Integer inputCount, Integer outputCount,
                              String errorCode, RuntimeException exception) {
        AiLog.warn(stageRecord(request, retrievalId, stage, message, "degraded",
                costMs, inputCount, outputCount)
                .field(AiLogFields.ERROR_CODE, errorCode)
                .field(AiLogFields.ERROR_TYPE, exception.getClass().getSimpleName())
                .field("degraded", true));
    }

    /** 记录Binding阶段降级；只保存稳定错误码和异常类型。 */
    private void logBindingStageWarn(RagRetrievalRequest request, String retrievalId, ResolvedBinding resolved,
                                     String stage, String message, Long costMs,
                                     Integer inputCount, Integer outputCount,
                                     String errorCode, RuntimeException exception) {
        AiLog.warn(bindingStageRecord(request, retrievalId, resolved, stage, message, "degraded",
                costMs, inputCount, outputCount)
                .field(AiLogFields.ERROR_CODE, errorCode)
                .field(AiLogFields.ERROR_TYPE, exception.getClass().getSimpleName())
                .field("degraded", true));
    }

    /** 将阶段异常压缩为稳定业务错误码。 */
    private String errorCode(RuntimeException exception) {
        return exception instanceof AppException appException && appException.getCode() != null
                ? appException.getCode() : "RAG_STAGE_FAILED";
    }

    /** 判断可选标识是否可用。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 使用单调时钟计算非负阶段耗时。 */
    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** 执行动作并携带真实耗时返回。 */
    private static <T> Timed<T> timed(Supplier<T> action) {
        long started = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, elapsedMs(started));
    }

    /** 融合候选先按分数降序，再以 chunkId 保证同分顺序稳定。 */
    private static final Comparator<ScoredHit> SCORED_HIT_ORDER = Comparator
            .comparingDouble(ScoredHit::fusionScore).reversed()
            .thenComparing(value -> value.hit().chunkId());
    /** 最终候选优先重排分，其次绑定优先级和稳定 chunkId。 */
    private static final Comparator<RankedChunk> RANKING = Comparator
            .comparingDouble((RankedChunk value) -> value.rerankScore() == null
                    ? value.fusionScore() : value.rerankScore()).reversed()
            .thenComparingInt(value -> value.resolved().binding().priority())
            .thenComparing(value -> value.chunk().chunkId());

    /** 已通过生命周期、策略和可见性校验的执行绑定。 */
    private record ResolvedBinding(RagAgentBindingEntity binding, RagKnowledgeBaseEntity knowledgeBase,
                                   RagRetrievalProfileEntity profile) { }

    /** 保留双路原始分与统一融合分的向量命中。 */
    private record ScoredHit(VectorStorePort.VectorSearchHit hit, Double denseScore, Double sparseScore,
                             double fusionScore) { }

    /** 回表并通过范围校验的业务候选。 */
    private record RankedChunk(ResolvedBinding resolved, RagChunkEntity chunk, Double denseScore,
                               Double sparseScore, double fusionScore, Double rerankScore) {
        /** 生成携带重排分的新候选，保留召回阶段分数。 */
        private RankedChunk withRerank(double score) {
            return new RankedChunk(resolved, chunk, denseScore, sparseScore, fusionScore, score);
        }
    }

    /** 外部或仓储调用结果及其耗时。 */
    private record Timed<T>(T value, long elapsedMs) {
        /** 表示未执行阶段的零耗时占位。 */
        private static <T> Timed<T> empty(T value) { return new Timed<>(value, 0); }
    }

    /** 汇总阶段指标、降级原因和可选诊断轨迹。 */
    private static final class Aggregate {
        /** Dense 向量召回返回的候选数。 */
        private int denseCandidates;
        /** Sparse 向量召回返回的候选数。 */
        private int sparseCandidates;
        /** 跨召回通道融合后的候选数。 */
        private int fusionCandidates;
        /** 进入重排阶段的候选数。 */
        private int rerankCandidates;
        /** Dense 召回耗时。 */
        private long denseMs;
        /** Sparse 召回耗时。 */
        private long sparseMs;
        /** 候选融合耗时。 */
        private long fusionMs;
        /** 重排耗时。 */
        private long rerankMs;
        /** 解析绑定和检索策略耗时。 */
        private long configurationMs;
        /** 根据向量命中回查分块正文耗时。 */
        private long hydrationMs;
        /** 邻接扩展、去重和上下文组装耗时。 */
        private long assemblyMs;
        /** 邻接窗口额外扩展的分块数。 */
        private int expandedChunkCount;
        /** 因最终 TopK 上限被拒绝的候选数。 */
        private int finalTopKRejected;
        /** 因上下文 Token 预算被拒绝的候选数。 */
        private int tokenBudgetRejected;
        /** 本次检索已发生的稳定降级原因。 */
        private final List<String> degradationReasons = new ArrayList<>();
        /** 按请求开关有界收集候选诊断。 */
        private final DiagnosticsCollector diagnostics;

        /** 按请求开关决定是否采集候选级诊断。 */
        private Aggregate(boolean diagnosticsEnabled) {
            this.diagnostics = new DiagnosticsCollector(diagnosticsEnabled);
        }
    }

    /** 有界采集候选在各阶段的去留轨迹，不保存正文。 */
    private static final class DiagnosticsCollector {
        /** 候选数量与标题长度上限防止调试响应无界增长。 */
        private static final int MAX_CAPTURED = 2_048;
        /** 单条诊断允许保留的最大标题路径字符数。 */
        private static final int MAX_HEADING_PATH_CHARS = 1_024;
        /** 当前请求是否允许收集候选诊断。 */
        private final boolean enabled;
        /** 已收集的不含正文候选轨迹。 */
        private final List<RagRetrievalResult.CandidateTrace> candidates = new ArrayList<>();
        /** 候选轨迹达到上限后标记诊断结果已截断。 */
        private boolean truncated;

        /** 构造启用或空操作诊断器。 */
        private DiagnosticsCollector(boolean enabled) { this.enabled = enabled; }

        /** 记录向量库返回的原始 Dense 或 Sparse 排名。 */
        private void captureRaw(ResolvedBinding resolved, String stage,
                                List<VectorStorePort.VectorSearchHit> hits, boolean dense) {
            if (!enabled) return;
            for (int index = 0; index < hits.size(); index++) {
                VectorStorePort.VectorSearchHit hit = hits.get(index);
                add(new RagRetrievalResult.CandidateTrace(resolved.binding().bindingId(),
                        resolved.profile().profileId(), stage, index + 1, hit.knowledgeBaseId(), hit.documentId(),
                        hit.versionId(), hit.generation(), hit.chunkId(), safeHeadingPath(hit.payload().get("heading_path")),
                        dense ? hit.score() : null,
                        dense ? null : hit.score(), null, null, "returned_by_vector_store"));
            }
        }

        /** 记录通过融合阈值与 TopK 的候选。 */
        private void captureFused(ResolvedBinding resolved, String stage, List<ScoredHit> values) {
            if (!enabled) return;
            for (int index = 0; index < values.size(); index++) {
                capture(resolved, stage, index + 1, values.get(index), null, "kept_after_fusion_threshold_topk");
            }
        }

        /** 将向量候选转换为不含正文的诊断记录。 */
        private void capture(ResolvedBinding resolved, String stage, int rank, ScoredHit value,
                             Double rerankScore, String outcome) {
            if (!enabled) return;
            VectorStorePort.VectorSearchHit hit = value.hit();
            add(new RagRetrievalResult.CandidateTrace(resolved.binding().bindingId(),
                    resolved.profile().profileId(), stage, rank, hit.knowledgeBaseId(), hit.documentId(),
                    hit.versionId(), hit.generation(), hit.chunkId(), safeHeadingPath(hit.payload().get("heading_path")),
                    value.denseScore(), value.sparseScore(),
                    value.fusionScore(), rerankScore, outcome));
        }

        /** 批量记录业务候选的阶段排名。 */
        private void captureRanked(String stage, List<RankedChunk> values, String outcome) {
            if (!enabled) return;
            for (int index = 0; index < values.size(); index++) capture(values.get(index), stage, index + 1, outcome);
        }

        /** 记录一个回表候选的分数与去留原因。 */
        private void capture(RankedChunk value, String stage, int rank, String outcome) {
            if (!enabled) return;
            RagChunkEntity chunk = value.chunk();
            add(new RagRetrievalResult.CandidateTrace(value.resolved().binding().bindingId(),
                    value.resolved().profile().profileId(), stage, rank, chunk.knowledgeBaseId(), chunk.documentId(),
                    chunk.versionId(), chunk.generation(), chunk.chunkId(), safeHeadingPath(chunk.headingPath()),
                    value.denseScore(), value.sparseScore(),
                    value.fusionScore(), value.rerankScore(), outcome));
        }

        /** 超过上限后只标记截断，不再增长内存。 */
        private void add(RagRetrievalResult.CandidateTrace value) {
            if (candidates.size() >= MAX_CAPTURED) {
                truncated = true;
                return;
            }
            candidates.add(value);
        }

        /** 截断异常长标题路径，诊断不承担正文传输。 */
        private String safeHeadingPath(String value) {
            if (value == null || value.length() <= MAX_HEADING_PATH_CHARS) return value;
            return value.substring(0, MAX_HEADING_PATH_CHARS);
        }

        /** 冻结诊断快照；未启用时返回标准空值。 */
        private RagRetrievalResult.Diagnostics result() {
            if (!enabled) return RagRetrievalResult.Diagnostics.empty();
            return new RagRetrievalResult.Diagnostics(true, truncated, candidates.size(), MAX_CAPTURED,
                    List.copyOf(candidates));
        }
    }

    /** 聚合不含正文的配置与执行审计状态。 */
    private final class AuditState {
        /** 本次实际使用的检索策略 ID，按稳定顺序保存。 */
        private List<String> profileIds = List.of();
        /** 实际使用策略中的最大 revision。 */
        private long profileRevision;
        /** 至少一个已解析绑定启用了 Dense 召回。 */
        private boolean denseEnabled;
        /** 至少一个已解析绑定启用了 Sparse 召回。 */
        private boolean sparseEnabled;
        /** 至少一个已解析绑定启用了重排。 */
        private boolean rerankEnabled;
        /** 目标配置声明的候选绑定数。 */
        private int candidateBindingCount;
        /** 通过生命周期、权限和策略校验的绑定数。 */
        private int resolvedBindingCount;
        /** 实际进入召回循环的绑定数。 */
        private int executedBindingCount;
        /** 跨绑定合并并去重后的候选数。 */
        private int mergedCandidateCount;

        /** 从实际解析成功的绑定提取策略版本和能力开关。 */
        private void capture(List<ResolvedBinding> bindings) {
            resolvedBindingCount = bindings.size();
            profileIds = bindings.stream().map(value -> value.profile().profileId()).distinct().sorted().toList();
            profileRevision = bindings.stream().mapToLong(value -> value.profile().revision()).max().orElse(0L);
            denseEnabled = bindings.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.SPARSE);
            sparseEnabled = bindings.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.DENSE);
            rerankEnabled = bindings.stream().anyMatch(value -> value.profile().rerankEnabled());
        }

        /** 返回目标配置的候选绑定数。 */
        private int candidateBindingCount() { return candidateBindingCount; }
        /** 返回通过生命周期和权限解析的绑定数。 */
        private int resolvedBindingCount() { return resolvedBindingCount; }
        /** 返回实际进入召回循环的绑定数。 */
        private int executedBindingCount() { return executedBindingCount; }
        /** 返回跨绑定去重后的候选数。 */
        private int mergedCandidateCount() { return mergedCandidateCount; }

        /** 单策略直接记录 ID，多策略记录排序后摘要。 */
        private String profileId() {
            if (profileIds.isEmpty()) return "none";
            if (profileIds.size() == 1) return profileIds.get(0);
            return "multi_" + sha256(String.join("|", profileIds)).substring(0, 24);
        }

        /** 生成不含查询正文和候选正文的审计配置快照。 */
        private Map<String, Object> snapshot(RagRetrievalRequest request) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("targetType", request.targetType().name().toLowerCase());
            values.put("targetId", request.targetId());
            values.put("profileIds", profileIds);
            values.put("maxContextTokens", request.maxContextTokens());
            values.put("denseEnabled", denseEnabled);
            values.put("sparseEnabled", sparseEnabled);
            values.put("rerankEnabled", rerankEnabled);
            return Map.copyOf(values);
        }
    }

    /** 聚合同一 chunkId 的双路分数和排名，再冻结为统一分数。 */
    private static final class MutableScore {
        /** 同一分块首次命中时确定的资源范围。 */
        private final VectorStorePort.VectorSearchHit hit;
        /** Dense 通道返回的原始相关性分数。 */
        private Double denseScore;
        /** Sparse 通道返回的原始相关性分数。 */
        private Double sparseScore;
        /** 分块在 Dense 结果中的排名，从 1 开始。 */
        private int denseRank;
        /** 分块在 Sparse 结果中的排名，从 1 开始。 */
        private int sparseRank;

        /** 以首次命中的资源范围作为聚合基准。 */
        private MutableScore(VectorStorePort.VectorSearchHit hit) { this.hit = hit; }
        /** 记录 Dense 原始分与排名。 */
        private MutableScore dense(double score, int rank) { denseScore = score; denseRank = rank; return this; }
        /** 记录 Sparse 原始分与排名。 */
        private MutableScore sparse(double score, int rank) { sparseScore = score; sparseRank = rank; return this; }

        /** 按单路、RRF 或加权策略计算零到一融合分。 */
        private ScoredHit freeze(RagRetrievalProfileEntity profile) {
            double denseWeight = decimal(profile.denseWeight());
            double sparseWeight = decimal(profile.sparseWeight());
            double score;
            if (profile.mode() == RagRetrievalMode.DENSE) {
                score = normalizeDense(denseScore);
            } else if (profile.mode() == RagRetrievalMode.SPARSE) {
                score = normalizePositive(sparseScore);
            } else if (profile.fusionStrategy() == RagFusionStrategy.RRF) {
                double raw = (denseRank == 0 ? 0 : denseWeight / (RRF_K + denseRank))
                        + (sparseRank == 0 ? 0 : sparseWeight / (RRF_K + sparseRank));
                double ceiling = (denseWeight + sparseWeight) / (RRF_K + 1D);
                score = ceiling == 0 ? 0 : raw / ceiling;
            } else {
                double denominator = denseWeight + sparseWeight;
                score = denominator == 0 ? 0 : (denseWeight * normalizeDense(denseScore)
                        + sparseWeight * normalizePositive(sparseScore)) / denominator;
            }
            return new ScoredHit(hit, denseScore, sparseScore, Math.max(0D, Math.min(1D, score)));
        }

        /** 空权重按零处理。 */
        private static double decimal(BigDecimal value) { return value == null ? 0D : value.doubleValue(); }
        /** 将余弦相似度从负一到一映射到零到一。 */
        private static double normalizeDense(Double value) {
            return value == null ? 0D : Math.max(0D, Math.min(1D, (value + 1D) / 2D));
        }
        /** 将非负 Sparse 分数压缩到零到一。 */
        private static double normalizePositive(Double value) {
            if (value == null || value <= 0D) return 0D;
            return value / (1D + value);
        }
    }
}
