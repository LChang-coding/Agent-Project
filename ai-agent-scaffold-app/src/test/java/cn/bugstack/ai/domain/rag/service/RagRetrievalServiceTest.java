package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import cn.bugstack.ai.domain.rag.adapter.port.EmbeddingPort;
import cn.bugstack.ai.domain.rag.adapter.port.RerankerPort;
import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RAG 检索领域编排、范围和消融路径测试。 */
public class RagRetrievalServiceTest {

    private IRagRepository repository;
    private EmbeddingPort embedding;
    private SparseEncoderPort sparse;
    private VectorStorePort vectorStore;
    private RerankerPort reranker;
    private RagRetrievalService service;

    @Before
    public void setUp() {
        repository = Mockito.mock(IRagRepository.class);
        embedding = Mockito.mock(EmbeddingPort.class);
        sparse = Mockito.mock(SparseEncoderPort.class);
        vectorStore = Mockito.mock(VectorStorePort.class);
        reranker = Mockito.mock(RerankerPort.class);
        service = new RagRetrievalService(repository, embedding, sparse, vectorStore, reranker,
                new CharacterTokenCounter());
    }

    @Test
    public void shouldReturnEmptyWithoutBindingAndAvoidModelCalls() {
        when(repository.listBindings("tenant-a", RagBindingTargetType.AGENT, "agent-a")).thenReturn(List.of());

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertTrue(result.citations().isEmpty());
        verify(embedding, never()).embed(any());
        verify(sparse, never()).encode(any());
        verify(vectorStore, never()).search(anyString(), any());
    }

    @Test
    public void shouldRunHybridRrfRerankAndPreserveActiveScope() {
        fixtures(profile(true, 0, 1000), false);
        when(embedding.embed(any())).thenReturn(new EmbeddingPort.EmbeddingResult(
                List.of(List.of(1F, 0F)), 2, "dense-r1"));
        when(sparse.encode(any())).thenReturn(new SparseEncoderPort.SparseEncodingResult(
                List.of(new SparseEncoderPort.SparseVector(Map.of(11, 1F))), "sparse-r1"));
        when(vectorStore.search(anyString(), any())).thenAnswer(invocation -> {
            VectorStorePort.VectorSearchCommand command = invocation.getArgument(1);
            return command.denseVector().isEmpty()
                    ? List.of(hit("chunk-b", "doc-b", "ver-b", 0.9), hit("chunk-a", "doc-a", "ver-a", 0.7))
                    : List.of(hit("chunk-a", "doc-a", "ver-a", 0.8), hit("chunk-b", "doc-b", "ver-b", 0.6));
        });
        when(reranker.rerank(any())).thenReturn(new RerankerPort.RerankResult(List.of(
                new RerankerPort.ScoredCandidate("chunk-b", 0.95, 1),
                new RerankerPort.ScoredCandidate("chunk-a", 0.75, 2)), "rerank-r1"));

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertEquals(2, result.citations().size());
        Assert.assertEquals("chunk-b", result.citations().get(0).chunkId());
        Assert.assertEquals("Beta.md", result.citations().get(0).documentName());
        Assert.assertFalse(result.degraded());
        Assert.assertEquals(2, result.metrics().denseCandidateCount());
        Assert.assertEquals(2, result.metrics().sparseCandidateCount());
        verify(embedding, times(1)).embed(any());
        verify(sparse, times(1)).encode(any());
        verify(vectorStore, times(2)).search(anyString(), any());
        verify(reranker, times(1)).rerank(any());
    }

    @Test
    public void shouldFallbackToFusionWhenRerankerFails() {
        fixtures(profile(true, 0, 1000), false);
        modelFixturesOneHit();
        when(reranker.rerank(any())).thenThrow(new AppException("RAG_RERANK_HTTP_ERROR", "远程失败"));

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertEquals(1, result.citations().size());
        Assert.assertTrue(result.degraded());
        Assert.assertTrue(result.degradationReasons().get(0).startsWith("rerank_fallback:"));
        Assert.assertNull(result.citations().get(0).rerankScore());
    }

    @Test
    public void shouldRejectRequiredUnavailableKnowledgeBaseBeforeModels() {
        RagAgentBindingEntity binding = binding(true);
        when(repository.listBindings("tenant-a", RagBindingTargetType.AGENT, "agent-a"))
                .thenReturn(List.of(binding));
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.empty());
        when(repository.findRetrievalProfile("tenant-a", "profile-a")).thenReturn(Optional.of(profile(false, 0, 1000)));

        AppException error = Assert.assertThrows(AppException.class, () -> service.retrieve(request(1000)));

        Assert.assertEquals("RAG_REQUIRED_BINDING_UNAVAILABLE", error.getCode());
        verify(embedding, never()).embed(any());
    }

    @Test
    public void shouldFailClosedOnChunkScopeViolationEvenForOptionalBinding() {
        fixtures(profile(false, 0, 1000), false);
        modelFixturesOneHit();
        RagChunkEntity wrongTenantScope = chunk("chunk-a", "doc-a", "ver-a", "kb-other", "正文");
        Mockito.doReturn(List.of(wrongTenantScope)).when(repository).listChunksByIds(anyString(), any());

        AppException error = Assert.assertThrows(AppException.class, () -> service.retrieve(request(1000)));

        Assert.assertEquals("RAG_CHUNK_SCOPE_VIOLATION", error.getCode());
    }

    @Test
    public void shouldRespectGlobalTokenBudgetWithoutPartialCitation() {
        fixtures(profile(false, 0, 1000), false);
        modelFixturesOneHit();

        RagRetrievalResult result = service.retrieve(request(1));

        Assert.assertTrue(result.citations().isEmpty());
        Assert.assertEquals(0, result.estimatedTokenCount());
    }

    @Test
    public void shouldDegradeOptionalDenseBindingWhenEmbeddingIsUnavailable() {
        fixtures(profile(false, 0, 1000), false);
        when(embedding.embed(any())).thenThrow(new AppException("RAG_EMBEDDING_HTTP_ERROR", "远程失败"));

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertTrue(result.citations().isEmpty());
        Assert.assertTrue(result.degraded());
        Assert.assertTrue(result.degradationReasons().contains("dense_unavailable"));
        verify(vectorStore, never()).search(anyString(), any());
    }

    @Test
    public void shouldExecuteDenseOnlyAblationWithoutSparseEncoding() {
        fixtures(singleModeProfile(RagRetrievalMode.DENSE), false);
        when(embedding.embed(any())).thenReturn(new EmbeddingPort.EmbeddingResult(
                List.of(List.of(1F, 0F)), 2, "dense-r1"));
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(hit("chunk-a", "doc-a", "ver-a", 0.8)));

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertEquals(1, result.citations().size());
        Assert.assertEquals(1, result.metrics().denseCandidateCount());
        Assert.assertEquals(0, result.metrics().sparseCandidateCount());
        verify(sparse, never()).encode(any());
        verify(vectorStore, times(1)).search(anyString(), any());
    }

    @Test
    public void shouldExecuteSparseOnlyAblationWithoutEmbedding() {
        fixtures(singleModeProfile(RagRetrievalMode.SPARSE), false);
        when(sparse.encode(any())).thenReturn(new SparseEncoderPort.SparseEncodingResult(
                List.of(new SparseEncoderPort.SparseVector(Map.of(11, 1F))), "sparse-r1"));
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(hit("chunk-a", "doc-a", "ver-a", 0.8)));

        RagRetrievalResult result = service.retrieve(request(1000));

        Assert.assertEquals(1, result.citations().size());
        Assert.assertEquals(0, result.metrics().denseCandidateCount());
        Assert.assertEquals(1, result.metrics().sparseCandidateCount());
        verify(embedding, never()).embed(any());
        verify(vectorStore, times(1)).search(anyString(), any());
    }

    private void fixtures(RagRetrievalProfileEntity profile, boolean required) {
        when(repository.listBindings("tenant-a", RagBindingTargetType.AGENT, "agent-a"))
                .thenReturn(List.of(binding(required)));
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(knowledgeBase()));
        when(repository.findRetrievalProfile("tenant-a", "profile-a")).thenReturn(Optional.of(profile));
        Map<String, RagChunkEntity> chunks = new LinkedHashMap<>();
        chunks.put("chunk-a", chunk("chunk-a", "doc-a", "ver-a", "kb-a", "Alpha 正文"));
        chunks.put("chunk-b", chunk("chunk-b", "doc-b", "ver-b", "kb-a", "Beta 正文"));
        when(repository.listChunksByIds(anyString(), any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            return ids.stream().filter(chunks::containsKey).map(chunks::get).toList();
        });
        when(repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(document("doc-a", "ver-a", "Alpha.md")));
        when(repository.findDocument("tenant-a", "doc-b")).thenReturn(Optional.of(document("doc-b", "ver-b", "Beta.md")));
    }

    private void modelFixturesOneHit() {
        when(embedding.embed(any())).thenReturn(new EmbeddingPort.EmbeddingResult(
                List.of(List.of(1F, 0F)), 2, "dense-r1"));
        when(sparse.encode(any())).thenReturn(new SparseEncoderPort.SparseEncodingResult(
                List.of(new SparseEncoderPort.SparseVector(Map.of(11, 1F))), "sparse-r1"));
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(hit("chunk-a", "doc-a", "ver-a", 0.8)));
    }

    private RagRetrievalRequest request(int maxTokens) {
        return new RagRetrievalRequest("tenant-a", "user-a", "session-a", "run-a",
                RagBindingTargetType.AGENT, "agent-a", "  如何 使用 Alpha？  ", "trace-a", maxTokens);
    }

    private RagAgentBindingEntity binding(boolean required) {
        return new RagAgentBindingEntity("tenant-a", "binding-a", RagBindingTargetType.AGENT, "agent-a",
                "kb-a", "profile-a", required, 1000, 0, 1);
    }

    private RagRetrievalProfileEntity profile(boolean rerank, int neighborWindow, int maxTokens) {
        return new RagRetrievalProfileEntity("tenant-a", "profile-a", "hybrid", RagRetrievalMode.HYBRID,
                RagFusionStrategy.RRF, BigDecimal.ONE, BigDecimal.ONE, 10, 10, 10,
                rerank, rerank ? 10 : 0, 2, neighborWindow, maxTokens, null,
                false, true, 1);
    }

    private RagRetrievalProfileEntity singleModeProfile(RagRetrievalMode mode) {
        return new RagRetrievalProfileEntity("tenant-a", "profile-a", mode.name().toLowerCase(), mode,
                RagFusionStrategy.NONE, BigDecimal.ONE, BigDecimal.ONE,
                mode == RagRetrievalMode.SPARSE ? 0 : 10, mode == RagRetrievalMode.DENSE ? 0 : 10,
                10, false, 0, 1, 0, 1000, null, false, true, 1);
    }

    private RagKnowledgeBaseEntity knowledgeBase() {
        return new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "知识库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, "profile-a", 768,
                "alias-a", 3, 1);
    }

    private VectorStorePort.VectorSearchHit hit(String chunkId, String documentId, String versionId, double score) {
        return new VectorStorePort.VectorSearchHit("point-" + chunkId, "kb-a", documentId, versionId,
                3, chunkId, score, Map.of());
    }

    private RagChunkEntity chunk(String chunkId, String documentId, String versionId, String kbId, String content) {
        return new RagChunkEntity("tenant-a", "owner-a", RagVisibility.TENANT, kbId, documentId,
                versionId, 1, 3, chunkId, chunkId.endsWith("a") ? 1 : 2,
                null, null, null, content, 10, 1, "章节", sha(chunkId), "point-" + chunkId, Map.of());
    }

    private RagDocumentEntity document(String documentId, String versionId, String name) {
        return new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", documentId,
                name, versionId, 3, null, RagDocumentStatus.READY, 1);
    }

    private String sha(String value) {
        return String.format("%064d", Math.abs(value.hashCode()));
    }
}
