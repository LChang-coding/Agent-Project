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

/**
 * RAG 检索链路调试入口。
 * <p>面向租户管理员返回候选、阶段耗时和降级原因；实际检索策略和权限过滤由领域服务执行。</p>
 */
@RestController
@RequestMapping("/api/v1/rag/retrieval-debug")
public class RagRetrievalDebugController {

    private final RagRetrievalDebugService service;

    /**
     * @param service RAG 调试检索领域服务
     */
    public RagRetrievalDebugController(RagRetrievalDebugService service) {
        this.service = service;
    }

    /**
     * 使用指定 Agent 或工作流绑定执行一次可观测检索。
     *
     * @param request 运行目标、测试问题和上下文 Token 预算
     * @return 引用、候选轨迹、降级信息和逐阶段耗时
     */
    @PostMapping
    public Response<RagRetrievalDebugResponseDTO> debug(
            @RequestBody(required = false) RagRetrievalDebugRequestDTO request) {
        try {
            // 空请求不能确定租户内运行目标，必须在进入昂贵的模型调用前拒绝。
            if (request == null) throw new AppException("RAG_DEBUG_REQUEST_INVALID", "调试请求不能为空");
            // traceId 与 HTTP 链路保持一致，便于从调试响应反查完整检索日志。
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

    /**
     * 将外部字符串严格转换为受支持的绑定目标枚举。
     */
    private RagBindingTargetType targetType(String value) {
        if (value == null || value.isBlank()) throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不能为空");
        try {
            return RagBindingTargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不受支持");
        }
    }

    /** 将完整领域检索结果转换为调试响应。 */
    private RagRetrievalDebugResponseDTO toResponse(RagRetrievalResult result) {
        // 指标单独展开，避免把领域对象直接暴露为外部协议。
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
                        .rerankMs(metrics.rerankMs()).totalMs(metrics.totalMs())
                        .configurationMs(metrics.configurationMs()).hydrationMs(metrics.hydrationMs())
                        .assemblyMs(metrics.assemblyMs()).auditMs(metrics.auditMs())
                        .serviceMs(metrics.serviceMs()).build())
                .citations(result.citations().stream().map(this::toCitation).toList())
                .diagnostics(toDiagnostics(result.diagnostics())).build();
    }

    /** 转换最终进入上下文的引用，并保留各检索阶段得分。 */
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

    /** 转换候选诊断汇总，供前端展示截断和采样边界。 */
    private RagRetrievalDebugResponseDTO.Diagnostics toDiagnostics(RagRetrievalResult.Diagnostics value) {
        return RagRetrievalDebugResponseDTO.Diagnostics.builder().enabled(value.enabled())
                .truncated(value.truncated()).capturedCount(value.capturedCount())
                .maxCapturedCount(value.maxCapturedCount())
                .candidates(value.candidates().stream().map(this::toCandidate).toList()).build();
    }

    /** 转换单个候选在融合、重排和淘汰阶段的轨迹。 */
    private RagRetrievalDebugResponseDTO.Candidate toCandidate(RagRetrievalResult.CandidateTrace value) {
        return RagRetrievalDebugResponseDTO.Candidate.builder().bindingId(value.bindingId())
                .profileId(value.profileId()).stage(value.stage()).rank(value.rank())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .versionId(value.versionId()).generation(value.generation()).chunkId(value.chunkId())
                .headingPath(value.headingPath())
                .denseScore(value.denseScore()).sparseScore(value.sparseScore())
                .fusionScore(value.fusionScore()).rerankScore(value.rerankScore())
                .outcome(value.outcome()).build();
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }
}
