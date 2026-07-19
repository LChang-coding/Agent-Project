package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.rag.RagRetrievalDebugRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagRetrievalDebugResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.RagRetrievalDebugService;
import cn.bugstack.ai.trigger.http.RagRetrievalDebugController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;

/** RAG 调试 Controller 的可信上下文、稳定错误和对外响应测试。 */
public class RagRetrievalDebugControllerTest {

    @After
    public void clear() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldUseTrustedTenantContextAndDefaultBudget() {
        RagRetrievalDebugService service = mock(RagRetrievalDebugService.class);
        RagRetrievalDebugController controller = new RagRetrievalDebugController(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());
        doReturn(result()).when(service).debug(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("admin-a"), org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq(RagBindingTargetType.AGENT),
                org.mockito.ArgumentMatchers.eq("agent-a"), org.mockito.ArgumentMatchers.eq("如何退货？"),
                org.mockito.ArgumentMatchers.eq(4096), org.mockito.ArgumentMatchers.anyString());

        Response<RagRetrievalDebugResponseDTO> response = controller.debug(request("agent"));

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("retrieval-a", response.getData().getRetrievalId());
        Assert.assertEquals(Integer.valueOf(8), response.getData().getEstimatedTokenCount());
        Assert.assertEquals("chunk-a", response.getData().getCitations().get(0).getChunkId());
        Assert.assertEquals(Long.valueOf(6), response.getData().getMetrics().getAssemblyMs());
        Assert.assertEquals(Long.valueOf(9), response.getData().getMetrics().getAuditMs());
        Assert.assertEquals(Long.valueOf(24), response.getData().getMetrics().getServiceMs());
        Assert.assertTrue(response.getData().getDiagnostics().getEnabled());
        Assert.assertEquals(Integer.valueOf(1), response.getData().getDiagnostics().getCapturedCount());
        Assert.assertEquals("dense_raw", response.getData().getDiagnostics().getCandidates().get(0).getStage());
    }

    @Test
    public void shouldReturnStableFailureForUnsupportedTargetType() {
        RagRetrievalDebugService service = mock(RagRetrievalDebugService.class);
        RagRetrievalDebugController controller = new RagRetrievalDebugController(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());

        Response<RagRetrievalDebugResponseDTO> response = controller.debug(request("database"));

        Assert.assertEquals("RAG_DEBUG_TARGET_INVALID", response.getCode());
        Assert.assertNull(response.getData());
    }

    @Test
    public void shouldNotExposeInternalStorageOrVectorFieldsInResponseContract() {
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.Citation.class.getDeclaredField("objectKey"));
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.Citation.class.getDeclaredField("vector"));
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.class.getDeclaredField("query"));
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.class.getDeclaredField("errorBody"));
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.Candidate.class.getDeclaredField("vector"));
        Assert.assertThrows(NoSuchFieldException.class,
                () -> RagRetrievalDebugResponseDTO.Candidate.class.getDeclaredField("objectKey"));
    }

    private RagRetrievalDebugRequestDTO request(String type) {
        RagRetrievalDebugRequestDTO value = new RagRetrievalDebugRequestDTO();
        value.setTargetType(type);
        value.setTargetId("agent-a");
        value.setQuery("如何退货？");
        return value;
    }

    private RagRetrievalResult result() {
        RagRetrievalResult.Citation citation = new RagRetrievalResult.Citation("citation-a", 1,
                "kb-a", "document-a", "退货政策.md", "version-a", 1, 3,
                "chunk-a", "7 天内可退货", 1, "退货", "hash-a",
                0.8, 0.6, 0.7, 0.9, Map.of("kind", "child"));
        RagRetrievalResult.CandidateTrace candidate = new RagRetrievalResult.CandidateTrace(
                "binding-a", "profile-a", "dense_raw", 1, "kb-a", "document-a", "version-a", 3,
                "chunk-a", "DOCID::doc-a — title", 0.8, null, null, null, "returned_by_vector_store");
        return new RagRetrievalResult("retrieval-a", List.of(citation), 8, false, List.of(),
                new RagRetrievalResult.Metrics(10, 10, 8, 5, 2, 3, 4, 1, 5, 15,
                        2, 3, 6, 9, 24),
                new RagRetrievalResult.Diagnostics(true, false, 1, 2048, List.of(candidate)));
    }
}
