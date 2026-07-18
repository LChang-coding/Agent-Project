package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagRetrievalDebugRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagRetrievalDebugResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.RagRetrievalDebugService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** 租户管理员 RAG 调试接口。 */
@RestController
@RequestMapping("/api/v1/rag/retrieval-debug")
public class RagRetrievalDebugController {

    private final RagRetrievalDebugService service;

    public RagRetrievalDebugController(RagRetrievalDebugService service) {
        this.service = service;
    }

    @PostMapping
    public Response<RagRetrievalDebugResponseDTO> debug(
            @RequestBody(required = false) RagRetrievalDebugRequestDTO request) {
        try {
            if (request == null) throw new AppException("RAG_DEBUG_REQUEST_INVALID", "调试请求不能为空");
            RagRetrievalResult result = service.debug(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(),
                    targetType(request.getTargetType()), request.getTargetId(), request.getQuery(),
                    request.getMaxContextTokens() == null ? 4096 : request.getMaxContextTokens(),
                    TraceContext.currentOrNewTraceId());
            return success(toResponse(result));
        } catch (AppException exception) {
            return Response.<RagRetrievalDebugResponseDTO>builder().code(exception.getCode())
                    .info(exception.getInfo()).build();
        }
    }

    private RagBindingTargetType targetType(String value) {
        if (value == null || value.isBlank()) throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不能为空");
        try {
            return RagBindingTargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不受支持");
        }
    }

    private RagRetrievalDebugResponseDTO toResponse(RagRetrievalResult result) {
        RagRetrievalResult.Metrics metrics = result.metrics();
        return RagRetrievalDebugResponseDTO.builder().retrievalId(result.retrievalId())
                .estimatedTokenCount(result.estimatedTokenCount()).degraded(result.degraded())
                .degradationReasons(result.degradationReasons())
                .metrics(RagRetrievalDebugResponseDTO.Metrics.builder()
                        .denseCandidateCount(metrics.denseCandidateCount())
                        .sparseCandidateCount(metrics.sparseCandidateCount())
                        .fusionCandidateCount(metrics.fusionCandidateCount())
                        .rerankCandidateCount(metrics.rerankCandidateCount())
                        .embeddingMs(metrics.embeddingMs()).denseMs(metrics.denseMs())
                        .sparseMs(metrics.sparseMs()).fusionMs(metrics.fusionMs())
                        .rerankMs(metrics.rerankMs()).totalMs(metrics.totalMs()).build())
                .citations(result.citations().stream().map(this::toCitation).toList()).build();
    }

    private RagRetrievalDebugResponseDTO.Citation toCitation(RagRetrievalResult.Citation value) {
        return RagRetrievalDebugResponseDTO.Citation.builder().citationId(value.citationId()).rank(value.rank())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .documentName(value.documentName()).documentVersion(value.documentVersion())
                .generation(value.generation()).chunkId(value.chunkId()).context(value.context())
                .pageNumber(value.pageNumber()).headingPath(value.headingPath())
                .denseScore(value.denseScore()).sparseScore(value.sparseScore())
                .fusionScore(value.fusionScore()).rerankScore(value.rerankScore())
                .metadata(value.metadata()).build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }
}
