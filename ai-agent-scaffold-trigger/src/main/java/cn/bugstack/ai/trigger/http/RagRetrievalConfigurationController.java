package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagBindingCreateRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagBindingResponseDTO;
import cn.bugstack.ai.api.dto.rag.RagRetrievalProfileRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagRetrievalProfileResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.service.RagRetrievalConfigurationService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** 租户管理员 RAG 检索策略和运行目标绑定接口。 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagRetrievalConfigurationController {

    private final RagRetrievalConfigurationService service;

    public RagRetrievalConfigurationController(RagRetrievalConfigurationService service) {
        this.service = service;
    }

    @PostMapping("/retrieval-profiles")
    public Response<RagRetrievalProfileResponseDTO> createProfile(
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        try {
            return success(toProfile(service.createProfile(tenant(), user(), role(), values(request))));
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    @PutMapping("/retrieval-profiles/{profileId}")
    public Response<RagRetrievalProfileResponseDTO> updateProfile(@PathVariable String profileId,
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        try {
            if (request == null || request.getExpectedRevision() == null) {
                throw new AppException("RAG_PROFILE_REVISION_REQUIRED", "更新检索策略必须携带expectedRevision");
            }
            return success(toProfile(service.updateProfile(tenant(), user(), role(), profileId,
                    request.getExpectedRevision(), values(request))));
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    @GetMapping("/retrieval-profiles")
    public Response<List<RagRetrievalProfileResponseDTO>> listProfiles() {
        try {
            return success(service.listProfiles(tenant(), user(), role()).stream().map(this::toProfile).toList());
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    @PostMapping("/bindings")
    public Response<RagBindingResponseDTO> createBinding(
            @RequestBody(required = false) RagBindingCreateRequestDTO request) {
        try {
            if (request == null) throw new AppException("RAG_BINDING_INVALID", "绑定参数不能为空");
            RagRetrievalConfigurationService.BindingValues values = new RagRetrievalConfigurationService.BindingValues(
                    enumValue(RagBindingTargetType.class, request.getTargetType(), "RAG_BINDING_TARGET_INVALID"),
                    request.getTargetId(), request.getKnowledgeBaseId(), request.getProfileId(),
                    Boolean.TRUE.equals(request.getRequired()), integer(request.getMaxTokens()),
                    integer(request.getPriority()));
            return success(toBinding(service.createBinding(tenant(), user(), role(), values)));
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    @GetMapping("/bindings")
    public Response<List<RagBindingResponseDTO>> listBindings() {
        try {
            return success(service.listBindings(tenant(), user(), role()).stream().map(this::toBinding).toList());
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    @DeleteMapping("/bindings/{bindingId}")
    public Response<Boolean> deleteBinding(@PathVariable String bindingId,
                                           @RequestParam(value = "expectedRevision", required = false)
                                           Long expectedRevision) {
        try {
            if (expectedRevision == null) {
                throw new AppException("RAG_BINDING_REVISION_REQUIRED", "删除绑定必须携带expectedRevision");
            }
            service.deleteBinding(tenant(), user(), role(), bindingId, expectedRevision);
            return success(true);
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    private RagRetrievalConfigurationService.ProfileValues values(RagRetrievalProfileRequestDTO request) {
        if (request == null) throw new AppException("RAG_PROFILE_INVALID", "检索策略参数不能为空");
        return new RagRetrievalConfigurationService.ProfileValues(request.getName(),
                enumValue(RagRetrievalMode.class, request.getMode(), "RAG_PROFILE_INVALID"),
                enumValue(RagFusionStrategy.class, request.getFusionStrategy(), "RAG_PROFILE_INVALID"),
                decimal(request.getDenseWeight()), decimal(request.getSparseWeight()),
                integer(request.getDenseTopK()), integer(request.getSparseTopK()),
                integer(request.getFusionTopK()), Boolean.TRUE.equals(request.getRerankEnabled()),
                integer(request.getRerankTopK()), integer(request.getFinalTopK()),
                integer(request.getNeighborWindow()), integer(request.getMaxContextTokens()),
                request.getScoreThreshold(), Boolean.TRUE.equals(request.getQueryRewriteEnabled()),
                !Boolean.FALSE.equals(request.getDeduplicateEnabled()));
    }

    private RagRetrievalProfileResponseDTO toProfile(RagRetrievalProfileEntity value) {
        return RagRetrievalProfileResponseDTO.builder().profileId(value.profileId()).name(value.name())
                .mode(value.mode().name().toLowerCase()).fusionStrategy(value.fusionStrategy().name().toLowerCase())
                .denseWeight(value.denseWeight()).sparseWeight(value.sparseWeight())
                .denseTopK(value.denseTopK()).sparseTopK(value.sparseTopK()).fusionTopK(value.fusionTopK())
                .rerankEnabled(value.rerankEnabled()).rerankTopK(value.rerankTopK()).finalTopK(value.finalTopK())
                .neighborWindow(value.neighborWindow()).maxContextTokens(value.maxContextTokens())
                .scoreThreshold(value.scoreThreshold()).queryRewriteEnabled(value.queryRewriteEnabled())
                .deduplicateEnabled(value.deduplicateEnabled()).revision(value.revision()).build();
    }

    private RagBindingResponseDTO toBinding(RagAgentBindingEntity value) {
        return RagBindingResponseDTO.builder().bindingId(value.bindingId())
                .targetType(value.targetType().name().toLowerCase()).targetId(value.targetId())
                .knowledgeBaseId(value.knowledgeBaseId()).profileId(value.retrievalProfileId())
                .required(value.required()).maxTokens(value.maxTokens()).priority(value.priority())
                .revision(value.revision()).build();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        if (value == null || value.isBlank()) throw new AppException(code, "枚举参数不能为空");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException(code, "枚举参数不受支持");
        }
    }

    private int integer(Integer value) { return value == null ? 0 : value; }
    private BigDecimal decimal(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private String tenant() { return TenantContextHolder.getTenantId(); }
    private String user() { return TenantContextHolder.getUserId(); }
    private String role() { return TenantContextHolder.getRoleCode(); }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    private <T> Response<T> failure(AppException exception) {
        return Response.<T>builder().code(exception.getCode()).info(exception.getInfo()).build();
    }
}
