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
 * 强租户 RAG 检索编排，保留 Dense/Sparse/融合/Rerank 独立指标以支持消融评测。
 */
@Service
public class RagRetrievalService {

    private static final int MAX_QUERY_CHARS = 4096;
    private static final int RRF_K = 60;
    private static final int MAX_BINDINGS = 32;
    private static final int DOCUMENT_LOAD_BATCH_SIZE = 500;

    private final IRagRepository repository;
    private final EmbeddingPort embeddingPort;
    private final SparseEncoderPort sparseEncoderPort;
    private final VectorStorePort vectorStorePort;
    private final RerankerPort rerankerPort;
    private final RagRetrievalAuditPort auditPort;
    private final TokenCounter tokenCounter;

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

    /** 执行检索；请求身份必须来自运行时可信状态，不接受浏览器自报 tenant。 */
    public RagRetrievalResult retrieve(RagRetrievalRequest request) {
        long started = System.nanoTime();
        String retrievalId = "ret_" + UUID.randomUUID().toString().replace("-", "");
        String query = normalizeQuery(request.query());
        AuditState audit = new AuditState();
        AiLog.info(AiLog.rag().retrieveStarted(request.tenantId(), request.userId(), request.sessionId(),
                request.runId(), retrievalId, request.targetType().name(), request.targetId(), query.length())
                .field(AiLogFields.TRACE_ID, request.traceId()));
        try {
            RagRetrievalResult result = retrieveInternal(request, retrievalId, query, started, audit);
            long auditStarted = System.nanoTime();
            recordAudit(request, query, result, result.citations().isEmpty() ? "empty" : "success", null, audit);
            RagRetrievalResult completed = result.withCompletionTimings(elapsedMs(auditStarted), elapsedMs(started));
            AiLog.info(AiLog.rag().retrieveCompleted(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), retrievalId, request.targetType().name(), request.targetId(), null,
                    completed.metrics().fusionCandidateCount(), completed.citations().size(),
                    completed.estimatedTokenCount(), completed.degraded(), completed.metrics().totalMs())
                    .field(AiLogFields.TRACE_ID, request.traceId()));
            if (completed.degraded()) {
                AiLog.warn(AiLog.rag().retrieveDegraded(request.tenantId(), request.userId(),
                        request.sessionId(), request.runId(), retrievalId, request.targetType().name(),
                        request.targetId(), String.join(",", completed.degradationReasons()),
                        completed.metrics().totalMs(), null).field(AiLogFields.TRACE_ID, request.traceId()));
            }
            return completed;
        } catch (RuntimeException exception) {
            RagRetrievalResult failed = RagRetrievalResult.empty(retrievalId, elapsedMs(started));
            recordAudit(request, query, failed, "failed", exception, audit);
            String errorCode = exception instanceof AppException appException
                    ? appException.getCode() : "RAG_RETRIEVAL_FAILED";
            AiLog.error(AiLog.rag().retrieveFailed(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), retrievalId, request.targetType().name(), request.targetId(), errorCode,
                    elapsedMs(started), exception).field(AiLogFields.TRACE_ID, request.traceId()));
            throw exception;
        }
    }

    private RagRetrievalResult retrieveInternal(RagRetrievalRequest request, String retrievalId, String query,
                                                long started, AuditState audit) {
        Aggregate aggregate = new Aggregate(request.diagnosticsEnabled());
        long configurationStarted = System.nanoTime();
        List<RagAgentBindingEntity> bindings = repository.listBindings(request.tenantId(), request.targetType(),
                request.targetId());
        if (bindings.isEmpty()) {
            aggregate.configurationMs = elapsedMs(configurationStarted);
            return emptyResult(retrievalId, started, aggregate);
        }
        if (bindings.size() > MAX_BINDINGS) {
            throw new AppException("RAG_BINDING_LIMIT_EXCEEDED", "单个运行目标的知识库绑定不能超过32个");
        }

        List<ResolvedBinding> resolved = resolveBindings(request, bindings);
        aggregate.configurationMs = elapsedMs(configurationStarted);
        audit.capture(resolved);
        if (resolved.isEmpty()) {
            return emptyResult(retrievalId, started, aggregate);
        }

        resolved.stream().filter(value -> value.profile().queryRewriteEnabled())
                .map(value -> "query_rewrite_unavailable:" + value.profile().profileId()).distinct()
                .forEach(aggregate.degradationReasons::add);
        List<ResolvedBinding> active = new ArrayList<>(resolved);
        Timed<List<Float>> dense = Timed.empty(List.of());
        if (active.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.SPARSE)) {
            try {
                dense = timed(() -> embeddingPort.embed(new EmbeddingPort.EmbeddingCommand(request.tenantId(),
                        request.traceId(), EmbeddingPort.EmbeddingInputType.QUERY, List.of(query))).vectors().get(0));
            } catch (RuntimeException exception) {
                failIfRequired(active, RagRetrievalMode.SPARSE, exception);
                aggregate.degradationReasons.add("dense_unavailable");
                active.removeIf(value -> value.profile().mode() != RagRetrievalMode.SPARSE);
            }
        }
        Timed<SparseEncoderPort.SparseVector> sparse = Timed.empty(null);
        if (active.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.DENSE)) {
            try {
                sparse = timed(() -> sparseEncoderPort.encode(new SparseEncoderPort.SparseEncodingCommand(
                        request.tenantId(), request.traceId(), List.of(query),
                        DeterministicSparseEncoder.VOCABULARY_REVISION)).vectors().get(0));
            } catch (RuntimeException exception) {
                failIfRequired(active, RagRetrievalMode.DENSE, exception);
                aggregate.degradationReasons.add("sparse_unavailable");
                active.removeIf(value -> value.profile().mode() != RagRetrievalMode.DENSE);
            }
        }
        if (active.isEmpty()) {
            return new RagRetrievalResult(retrievalId, List.of(), 0, true, aggregate.degradationReasons,
                    new RagRetrievalResult.Metrics(0, 0, 0, 0, dense.elapsedMs(), 0, 0, 0, 0,
                            elapsedMs(started), aggregate.configurationMs, aggregate.hydrationMs,
                            aggregate.assemblyMs, 0, 0), aggregate.diagnostics.result());
        }

        List<RankedChunk> ranked = new ArrayList<>();
        for (ResolvedBinding value : active) {
            try {
                ranked.addAll(retrieveBinding(request, query, value, dense.value(), sparse.value(), aggregate));
            } catch (RuntimeException exception) {
                if (value.binding().required() || isScopeViolation(exception)) {
                    throw exception;
                }
                aggregate.degradationReasons.add("optional_binding_failed:" + value.binding().bindingId());
            }
        }

        List<RankedChunk> merged = ranked.stream()
                .collect(Collectors.toMap(value -> value.chunk().chunkId(), value -> value,
                        this::better, LinkedHashMap::new)).values().stream()
                .sorted(RANKING).toList();
        long assemblyStarted = System.nanoTime();
        long hydrationBeforeAssembly = aggregate.hydrationMs;
        List<RagRetrievalResult.Citation> citations = assembleCitations(
                request, retrievalId, merged, active, aggregate);
        aggregate.assemblyMs += Math.max(0L, elapsedMs(assemblyStarted)
                - (aggregate.hydrationMs - hydrationBeforeAssembly));
        int tokens = citations.stream().mapToInt(value -> tokenCounter.estimate(value.context())).sum();
        long totalMs = elapsedMs(started);
        RagRetrievalResult.Metrics metrics = new RagRetrievalResult.Metrics(aggregate.denseCandidates,
                aggregate.sparseCandidates, aggregate.fusionCandidates, aggregate.rerankCandidates,
                dense.elapsedMs(), aggregate.denseMs, aggregate.sparseMs, aggregate.fusionMs,
                aggregate.rerankMs, totalMs, aggregate.configurationMs, aggregate.hydrationMs,
                aggregate.assemblyMs, 0, 0);
        return new RagRetrievalResult(retrievalId, citations, tokens, !aggregate.degradationReasons.isEmpty(),
                aggregate.degradationReasons, metrics, aggregate.diagnostics.result());
    }

    private void recordAudit(RagRetrievalRequest request, String query, RagRetrievalResult result,
                             String status, RuntimeException exception, AuditState state) {
        try {
            String errorCode = exception instanceof AppException appException
                    ? appException.getCode() : (exception == null ? null : "RAG_RETRIEVAL_FAILED");
            auditPort.record(new RagRetrievalAuditCommand(result.retrievalId(), request.tenantId(), request.userId(),
                    request.sessionId(), request.runId(), request.targetId(), state.profileId(),
                    state.profileRevision, query, state.denseEnabled, state.sparseEnabled, state.rerankEnabled,
                    result, status, errorCode, exception == null ? null : exception.getClass().getSimpleName(),
                    request.traceId(), state.snapshot(request)));
        } catch (RuntimeException auditException) {
            AiLog.error(AiLog.rag().retrieveFailed(request.tenantId(), request.userId(), request.sessionId(),
                    request.runId(), result.retrievalId(), request.targetType().name(), request.targetId(),
                    "RAG_AUDIT_WRITE_FAILED", null, auditException)
                    .field(AiLogFields.TRACE_ID, request.traceId()).field(AiLogFields.STAGE, "audit"));
        }
    }

    private RagRetrievalResult emptyResult(String retrievalId, long started, Aggregate aggregate) {
        return new RagRetrievalResult(retrievalId, List.of(), 0, false, List.of(),
                new RagRetrievalResult.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0,
                        elapsedMs(started), aggregate.configurationMs, aggregate.hydrationMs,
                        aggregate.assemblyMs, 0, 0), aggregate.diagnostics.result());
    }

    private void failIfRequired(List<ResolvedBinding> bindings, RagRetrievalMode unaffectedMode,
                                RuntimeException exception) {
        boolean requiredAffected = bindings.stream().anyMatch(value -> value.binding().required()
                && value.profile().mode() != unaffectedMode);
        if (requiredAffected) throw exception;
    }

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

    private List<RankedChunk> retrieveBinding(RagRetrievalRequest request,
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
        }
        if (profile.mode() != RagRetrievalMode.DENSE) {
            Timed<List<VectorStorePort.VectorSearchHit>> call = timed(() -> vectorStorePort.search(request.tenantId(),
                    new VectorStorePort.VectorSearchCommand(scope, List.of(), sparseVector, profile.sparseTopK())));
            sparseHits = call.value();
            aggregate.sparseMs += call.elapsedMs();
            aggregate.sparseCandidates += sparseHits.size();
            aggregate.diagnostics.captureRaw(resolved, "sparse_raw", sparseHits, false);
        }
        long fusionStarted = System.nanoTime();
        List<ScoredHit> fused = fuse(profile, denseHits, sparseHits);
        aggregate.fusionMs += elapsedMs(fusionStarted);
        aggregate.fusionCandidates += fused.size();
        aggregate.diagnostics.captureFused(resolved, "fusion", fused);
        if (fused.isEmpty()) return List.of();

        Map<String, RagChunkEntity> chunks = loadChunks(request.tenantId(),
                        fused.stream().map(value -> value.hit().chunkId()).toList(), aggregate).stream()
                .collect(Collectors.toMap(RagChunkEntity::chunkId, value -> value));
        Map<String, Optional<RagDocumentEntity>> missingChunkDocuments = new LinkedHashMap<>();
        List<RankedChunk> candidates = new ArrayList<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        int filteredRank = 0;
        for (int fusedIndex = 0; fusedIndex < fused.size(); fusedIndex++) {
            ScoredHit value = fused.get(fusedIndex);
            RagChunkEntity chunk = chunks.get(value.hit().chunkId());
            if (chunk == null && isLegitimateDeletingHit(request.tenantId(), resolved, value.hit(),
                    missingChunkDocuments)) {
                aggregate.diagnostics.capture(resolved, "candidate_filter", fusedIndex + 1, value,
                        null, "discarded_tombstone");
                continue;
            }
            validateChunkScope(resolved, value.hit(), chunk);
            if (profile.deduplicateEnabled() && !contentHashes.add(chunk.contentHash())) {
                aggregate.diagnostics.capture(resolved, "candidate_filter", fusedIndex + 1, value,
                        null, "discarded_duplicate_content_hash");
                continue;
            }
            RankedChunk candidate = new RankedChunk(resolved, chunk, value.denseScore(), value.sparseScore(),
                    value.fusionScore(), null);
            candidates.add(candidate);
            aggregate.diagnostics.capture(candidate, "candidate_filter", ++filteredRank, "kept");
        }
        if (!profile.rerankEnabled() || candidates.isEmpty()) {
            List<RankedChunk> output = candidates.stream().limit(profile.finalTopK()).toList();
            aggregate.diagnostics.captureRanked("pre_assembly", output, "kept_without_rerank");
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
            return rerankOutput;
        } catch (RuntimeException exception) {
            aggregate.degradationReasons.add("rerank_fallback:" + profile.profileId());
            List<RankedChunk> fallback = candidates.stream().limit(profile.finalTopK()).toList();
            aggregate.diagnostics.captureRanked("rerank_output", fallback, "fallback_without_rerank_score");
            return fallback;
        }
    }

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
        Map<String, RagDocumentEntity> documents = loadDocuments(request.tenantId(), ranked, aggregate);
        Set<String> emittedChunks = new LinkedHashSet<>();
        List<RagRetrievalResult.Citation> citations = new ArrayList<>();
        int used = 0;
        for (int rankedIndex = 0; rankedIndex < ranked.size(); rankedIndex++) {
            RankedChunk value = ranked.get(rankedIndex);
            if (citations.size() >= value.resolved().profile().finalTopK()) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_final_topk");
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
            contextChunks = contextChunks.stream().filter(chunk -> !emittedChunks.contains(chunk.chunkId())).toList();
            String context = contextChunks.stream().map(RagChunkEntity::content).collect(Collectors.joining("\n\n"));
            int tokens = tokenCounter.estimate(context);
            String bindingId = value.resolved().binding().bindingId();
            int localUsed = bindingUsed.getOrDefault(bindingId, 0);
            if (tokens < 1) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_empty_context");
                continue;
            }
            if (used + tokens > globalBudget) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_global_token_budget");
                continue;
            }
            if (localUsed + tokens > bindingBudget.get(bindingId)) {
                aggregate.diagnostics.capture(value, "context_budget", rankedIndex + 1, "discarded_binding_token_budget");
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
            aggregate.diagnostics.capture(value, "context_budget", rank, "accepted_citation");
        }
        return List.copyOf(citations);
    }

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

    private List<RagChunkEntity> loadChunks(String tenantId, List<String> chunkIds, Aggregate aggregate) {
        Timed<List<RagChunkEntity>> loaded = timed(() -> repository.listChunksByIds(tenantId, chunkIds));
        aggregate.hydrationMs += loaded.elapsedMs();
        return loaded.value();
    }

    private Set<String> neighborIds(RagChunkEntity chunk) {
        Set<String> ids = new LinkedHashSet<>();
        if (hasText(chunk.previousChunkId())) ids.add(chunk.previousChunkId());
        if (hasText(chunk.nextChunkId())) ids.add(chunk.nextChunkId());
        return ids;
    }

    private void validateChunkScope(ResolvedBinding resolved, VectorStorePort.VectorSearchHit hit,
                                    RagChunkEntity chunk) {
        if (chunk == null || !resolved.knowledgeBase().knowledgeBaseId().equals(chunk.knowledgeBaseId())
                || resolved.knowledgeBase().currentGeneration() != chunk.generation()
                || !hit.documentId().equals(chunk.documentId()) || !hit.versionId().equals(chunk.versionId())
                || !hit.chunkId().equals(chunk.chunkId())) {
            throw new AppException("RAG_CHUNK_SCOPE_VIOLATION", "向量命中与业务分块范围不一致");
        }
    }

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

    private void validateRelatedScope(RagChunkEntity main, RagChunkEntity related) {
        if (!main.tenantId().equals(related.tenantId()) || !main.knowledgeBaseId().equals(related.knowledgeBaseId())
                || !main.documentId().equals(related.documentId()) || !main.versionId().equals(related.versionId())
                || main.generation() != related.generation()) {
            throw new AppException("RAG_NEIGHBOR_SCOPE_VIOLATION", "相邻分块超出主命中范围");
        }
    }

    private void validateDocumentScope(RankedChunk value, RagDocumentEntity document) {
        RagChunkEntity chunk = value.chunk();
        if (!document.status().searchable() || !chunk.knowledgeBaseId().equals(document.knowledgeBaseId())
                || !chunk.versionId().equals(document.activeVersionId())
                || chunk.generation() != document.activeGeneration()) {
            throw new AppException("RAG_DOCUMENT_SCOPE_VIOLATION", "引用文档已不属于当前活动索引快照");
        }
    }

    private void validateDeletingDocumentScope(RankedChunk value, RagDocumentEntity document) {
        RagChunkEntity chunk = value.chunk();
        if (!chunk.knowledgeBaseId().equals(document.knowledgeBaseId())
                || document.activeVersionId() != null && !chunk.versionId().equals(document.activeVersionId())
                || document.activeGeneration() > 0 && chunk.generation() != document.activeGeneration()
                || !isTombstonedVersion(chunk.tenantId(), document, chunk.versionId(), chunk.generation())) {
            throw new AppException("RAG_DOCUMENT_SCOPE_VIOLATION", "删除态文档命中超出原活动索引范围");
        }
    }

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

    private void validateSameHitScope(VectorStorePort.VectorSearchHit left,
                                      VectorStorePort.VectorSearchHit right) {
        if (!left.knowledgeBaseId().equals(right.knowledgeBaseId())
                || !left.documentId().equals(right.documentId()) || !left.versionId().equals(right.versionId())
                || left.generation() != right.generation()) {
            throw new AppException("RAG_FUSION_SCOPE_VIOLATION", "Dense与Sparse命中范围不一致");
        }
    }

    private RankedChunk better(RankedChunk left, RankedChunk right) {
        return RANKING.compare(left, right) <= 0 ? left : right;
    }

    private String normalizeQuery(String query) {
        String normalized = query.strip().replaceAll("[\\p{Z}\\s]+", " ");
        if (normalized.isBlank() || normalized.length() > MAX_QUERY_CHARS) {
            throw new AppException("RAG_QUERY_INVALID", "检索问题为空或超过4096字符");
        }
        return normalized;
    }

    private String citationId(String retrievalId, RagChunkEntity chunk, int rank) {
        return "cite_" + sha256(retrievalId + "|" + chunk.tenantId() + "|" + chunk.versionId()
                + "|" + chunk.chunkId() + "|" + rank)
                .substring(0, 24);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    private boolean isScopeViolation(RuntimeException exception) {
        return exception instanceof AppException appException && appException.getCode() != null
                && appException.getCode().contains("SCOPE");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static <T> Timed<T> timed(Supplier<T> action) {
        long started = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, elapsedMs(started));
    }

    private static final Comparator<ScoredHit> SCORED_HIT_ORDER = Comparator
            .comparingDouble(ScoredHit::fusionScore).reversed()
            .thenComparing(value -> value.hit().chunkId());
    private static final Comparator<RankedChunk> RANKING = Comparator
            .comparingDouble((RankedChunk value) -> value.rerankScore() == null
                    ? value.fusionScore() : value.rerankScore()).reversed()
            .thenComparingInt(value -> value.resolved().binding().priority())
            .thenComparing(value -> value.chunk().chunkId());

    private record ResolvedBinding(RagAgentBindingEntity binding, RagKnowledgeBaseEntity knowledgeBase,
                                   RagRetrievalProfileEntity profile) { }

    private record ScoredHit(VectorStorePort.VectorSearchHit hit, Double denseScore, Double sparseScore,
                             double fusionScore) { }

    private record RankedChunk(ResolvedBinding resolved, RagChunkEntity chunk, Double denseScore,
                               Double sparseScore, double fusionScore, Double rerankScore) {
        private RankedChunk withRerank(double score) {
            return new RankedChunk(resolved, chunk, denseScore, sparseScore, fusionScore, score);
        }
    }

    private record Timed<T>(T value, long elapsedMs) {
        private static <T> Timed<T> empty(T value) { return new Timed<>(value, 0); }
    }

    private static final class Aggregate {
        private int denseCandidates;
        private int sparseCandidates;
        private int fusionCandidates;
        private int rerankCandidates;
        private long denseMs;
        private long sparseMs;
        private long fusionMs;
        private long rerankMs;
        private long configurationMs;
        private long hydrationMs;
        private long assemblyMs;
        private final List<String> degradationReasons = new ArrayList<>();
        private final DiagnosticsCollector diagnostics;

        private Aggregate(boolean diagnosticsEnabled) {
            this.diagnostics = new DiagnosticsCollector(diagnosticsEnabled);
        }
    }

    private static final class DiagnosticsCollector {
        private static final int MAX_CAPTURED = 2_048;
        private static final int MAX_HEADING_PATH_CHARS = 1_024;
        private final boolean enabled;
        private final List<RagRetrievalResult.CandidateTrace> candidates = new ArrayList<>();
        private boolean truncated;

        private DiagnosticsCollector(boolean enabled) { this.enabled = enabled; }

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

        private void captureFused(ResolvedBinding resolved, String stage, List<ScoredHit> values) {
            if (!enabled) return;
            for (int index = 0; index < values.size(); index++) {
                capture(resolved, stage, index + 1, values.get(index), null, "kept_after_fusion_threshold_topk");
            }
        }

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

        private void captureRanked(String stage, List<RankedChunk> values, String outcome) {
            if (!enabled) return;
            for (int index = 0; index < values.size(); index++) capture(values.get(index), stage, index + 1, outcome);
        }

        private void capture(RankedChunk value, String stage, int rank, String outcome) {
            if (!enabled) return;
            RagChunkEntity chunk = value.chunk();
            add(new RagRetrievalResult.CandidateTrace(value.resolved().binding().bindingId(),
                    value.resolved().profile().profileId(), stage, rank, chunk.knowledgeBaseId(), chunk.documentId(),
                    chunk.versionId(), chunk.generation(), chunk.chunkId(), safeHeadingPath(chunk.headingPath()),
                    value.denseScore(), value.sparseScore(),
                    value.fusionScore(), value.rerankScore(), outcome));
        }

        private void add(RagRetrievalResult.CandidateTrace value) {
            if (candidates.size() >= MAX_CAPTURED) {
                truncated = true;
                return;
            }
            candidates.add(value);
        }

        private String safeHeadingPath(String value) {
            if (value == null || value.length() <= MAX_HEADING_PATH_CHARS) return value;
            return value.substring(0, MAX_HEADING_PATH_CHARS);
        }

        private RagRetrievalResult.Diagnostics result() {
            if (!enabled) return RagRetrievalResult.Diagnostics.empty();
            return new RagRetrievalResult.Diagnostics(true, truncated, candidates.size(), MAX_CAPTURED,
                    List.copyOf(candidates));
        }
    }

    private final class AuditState {
        private List<String> profileIds = List.of();
        private long profileRevision;
        private boolean denseEnabled;
        private boolean sparseEnabled;
        private boolean rerankEnabled;

        private void capture(List<ResolvedBinding> bindings) {
            profileIds = bindings.stream().map(value -> value.profile().profileId()).distinct().sorted().toList();
            profileRevision = bindings.stream().mapToLong(value -> value.profile().revision()).max().orElse(0L);
            denseEnabled = bindings.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.SPARSE);
            sparseEnabled = bindings.stream().anyMatch(value -> value.profile().mode() != RagRetrievalMode.DENSE);
            rerankEnabled = bindings.stream().anyMatch(value -> value.profile().rerankEnabled());
        }

        private String profileId() {
            if (profileIds.isEmpty()) return "none";
            if (profileIds.size() == 1) return profileIds.get(0);
            return "multi_" + sha256(String.join("|", profileIds)).substring(0, 24);
        }

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

    private static final class MutableScore {
        private final VectorStorePort.VectorSearchHit hit;
        private Double denseScore;
        private Double sparseScore;
        private int denseRank;
        private int sparseRank;

        private MutableScore(VectorStorePort.VectorSearchHit hit) { this.hit = hit; }
        private MutableScore dense(double score, int rank) { denseScore = score; denseRank = rank; return this; }
        private MutableScore sparse(double score, int rank) { sparseScore = score; sparseRank = rank; return this; }

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

        private static double decimal(BigDecimal value) { return value == null ? 0D : value.doubleValue(); }
        private static double normalizeDense(Double value) {
            return value == null ? 0D : Math.max(0D, Math.min(1D, (value + 1D) / 2D));
        }
        private static double normalizePositive(Double value) {
            if (value == null || value <= 0D) return 0D;
            return value / (1D + value);
        }
    }
}
