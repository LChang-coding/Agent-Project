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

/**
 * 租户级 RAG 检索策略与运行目标绑定入口。
 * <p>控制器只负责协议归一化；管理员权限、知识库归属和版本冲突由领域服务校验。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagRetrievalConfigurationController {

    private final RagRetrievalConfigurationService service;

    /** @param service RAG 检索配置领域服务 */
    public RagRetrievalConfigurationController(RagRetrievalConfigurationService service) {
        this.service = service;
    }

    /**
     * 创建一套可复用的检索参数组合。
     *
     * @param request Dense/Sparse、融合、重排和上下文预算参数
     * @return 已持久化的检索策略及版本
     */
    @PostMapping("/retrieval-profiles")
    public Response<RagRetrievalProfileResponseDTO> createProfile(
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        try {
            return success(toProfile(service.createProfile(tenant(), user(), role(), values(request))));
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    /**
     * 以乐观锁更新检索策略。
     *
     * @param profileId 检索策略ID
     * @param request 新参数和期望版本
     * @return 更新后的策略
     */
    @PutMapping("/retrieval-profiles/{profileId}")
    public Response<RagRetrievalProfileResponseDTO> updateProfile(@PathVariable String profileId,
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        try {
            // 检索策略可能被多个管理员同时编辑，缺少版本时禁止覆盖。
            if (request == null || request.getExpectedRevision() == null) {
                throw new AppException("RAG_PROFILE_REVISION_REQUIRED", "更新检索策略必须携带expectedRevision");
            }
            return success(toProfile(service.updateProfile(tenant(), user(), role(), profileId,
                    request.getExpectedRevision(), values(request))));
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    /** 查询当前租户可管理的检索策略。 */
    @GetMapping("/retrieval-profiles")
    public Response<List<RagRetrievalProfileResponseDTO>> listProfiles() {
        try {
            return success(service.listProfiles(tenant(), user(), role()).stream().map(this::toProfile).toList());
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    /**
     * 将知识库和检索策略绑定到 Agent、工作流或工作流节点。
     *
     * @param request 运行目标、知识库、策略、优先级及上下文预算
     * @return 新绑定及版本
     */
    @PostMapping("/bindings")
    public Response<RagBindingResponseDTO> createBinding(
            @RequestBody(required = false) RagBindingCreateRequestDTO request) {
        try {
            if (request == null) throw new AppException("RAG_BINDING_INVALID", "绑定参数不能为空");
            // 在触发器边界完成枚举和可空数值归一化，领域层接收强类型参数。
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

    /** 查询当前租户的全部运行目标绑定。 */
    @GetMapping("/bindings")
    public Response<List<RagBindingResponseDTO>> listBindings() {
        try {
            return success(service.listBindings(tenant(), user(), role()).stream().map(this::toBinding).toList());
        } catch (AppException exception) {
            return failure(exception);
        }
    }

    /**
     * 以乐观锁删除运行目标绑定。
     *
     * @param bindingId 绑定ID
     * @param expectedRevision 客户端读取到的绑定版本
     * @return 删除成功标志
     */
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

    /** 将 Profile 请求归一化为领域值对象，并为可选能力填充稳定默认值。 */
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

    /** 将检索策略领域实体转换为公开响应。 */
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

    /** 将运行目标绑定转换为公开响应。 */
    private RagBindingResponseDTO toBinding(RagAgentBindingEntity value) {
        return RagBindingResponseDTO.builder().bindingId(value.bindingId())
                .targetType(value.targetType().name().toLowerCase()).targetId(value.targetId())
                .knowledgeBaseId(value.knowledgeBaseId()).profileId(value.retrievalProfileId())
                .required(value.required()).maxTokens(value.maxTokens()).priority(value.priority())
                .revision(value.revision()).build();
    }

    /** 严格解析外部枚举，拒绝空值和未知值进入领域层。 */
    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        if (value == null || value.isBlank()) throw new AppException(code, "枚举参数不能为空");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException(code, "枚举参数不受支持");
        }
    }

    /** 可选整数未提供时归一化为零，由领域服务判断零值是否有效。 */
    private int integer(Integer value) { return value == null ? 0 : value; }

    /** 可选权重未提供时归一化为零，避免领域值对象携带 null。 */
    private BigDecimal decimal(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    /** 读取可信租户身份。 */
    private String tenant() { return TenantContextHolder.getTenantId(); }

    /** 读取可信用户身份。 */
    private String user() { return TenantContextHolder.getUserId(); }

    /** 读取可信租户角色。 */
    private String role() { return TenantContextHolder.getRoleCode(); }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    /** 保留领域业务错误码。 */
    private <T> Response<T> failure(AppException exception) {
        return Response.<T>builder().code(exception.getCode()).info(exception.getInfo()).build();
    }
}
