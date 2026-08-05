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
 * 配置「检索该怎么检」和「谁用哪个知识库」的 HTTP 入口。
 *
 * <p>解决什么问题：RAG 效果好不好，一大半取决于检索参数——向量检索和关键词检索各占多少权重、
 * 各取前几条、要不要重排、最终塞多少 Token 进上下文。这些参数被打包成一份「检索策略」（profile）可复用；
 * 再通过「绑定」（binding）把某个知识库 + 某份策略挂到具体的 Agent、工作流或工作流节点上。
 * 这个控制器就是这两类配置的增删改查入口。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端配置页面。</p>
 *
 * <p>谁会调用它：Web 前端的 RAG 配置页面，通过 /api/v1/rag/retrieval-profiles 和 /api/v1/rag/bindings 调用。</p>
 *
 * <p>它向下调用什么：只调 {@code RagRetrievalConfigurationService}——由它做管理员权限校验、
 * 知识库归属校验、参数取值范围校验和乐观锁更新。</p>
 *
 * <p>它不负责什么：不执行任何检索、不判断参数组合是否合理、不判断谁有权配置、不做版本冲突检测。
 * 这里只做三件事：把外部字符串严格解析成领域枚举（拒绝空值和未知值）、把可空数值归一化成非空，
 * 以及强制要求「改」和「删」必须带版本号。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagRetrievalConfigurationController {

    /**
     * RAG 检索配置领域服务，本控制器唯一的下游依赖。
     *
     * <p>策略和绑定的建、查、改、删都走它。管理员权限、知识库归属、参数范围、乐观锁比对全在它内部完成，
     * 所以这里传进去可信的租户、用户、角色就够了。final 且构造注入，并发请求共享同一实例。</p>
     */
    private final RagRetrievalConfigurationService service;

    /**
     * 启动时由 Spring 注入检索配置领域服务，注入后依赖不再变化。
     *
     * @param service RAG 检索配置领域服务
     */
    public RagRetrievalConfigurationController(RagRetrievalConfigurationService service) {
        // 保存领域服务引用；这是本类唯一的初始化动作。
        this.service = service;
    }

    /**
     * 新建一套可复用的检索参数组合（检索策略）。
     *
     * <p>一份策略可以被多个绑定共用，改一次就对所有用它的 Agent 生效，避免逐个 Agent 重复调参。
     * 返回值里带 revision，前端后续要改这份策略必须原样带回来。</p>
     *
     * <p>会写数据库。主要失败情形：请求体为空、模式或融合策略枚举取值不受支持、角色无权配置、名称重复。</p>
     *
     * @param request Dense/Sparse、融合、重排和上下文预算参数
     * @return 已持久化的检索策略及版本
     */
    @PostMapping("/retrieval-profiles")
    public Response<RagRetrievalProfileResponseDTO> createProfile(
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        // 枚举非法、权限不足、名称冲突都是可预期拒绝，统一接住转成业务错误码。
        try {
            // 先把请求归一化成领域值对象（枚举严格解析、可空数值补零），再带可信身份落库，最后裁剪成对外 DTO。
            return success(toProfile(service.createProfile(tenant(), user(), role(), values(request))));
        } catch (AppException exception) {
            // 原样返回领域层给出的错误码和文案，前端据此提示用户修正参数。
            return failure(exception);
        }
    }

    /**
     * 用乐观锁更新一份检索策略。
     *
     * <p>为什么必须带 expectedRevision：调参页面往往开着很久，几个人同时调同一份策略非常常见。
     * 不带版本号就会出现「后保存的人静默覆盖前一个人」的情况，而且检索效果变差还查不出原因。
     * 带上版本号后，基于过期页面的保存会被明确拒绝。</p>
     *
     * <p>会写数据库并递增 revision。主要失败情形：没带版本号、版本与库里不一致、枚举取值非法、角色无权编辑。</p>
     *
     * @param profileId 检索策略ID
     * @param request 新参数和期望版本
     * @return 更新后的策略
     */
    @PutMapping("/retrieval-profiles/{profileId}")
    public Response<RagRetrievalProfileResponseDTO> updateProfile(@PathVariable String profileId,
            @RequestBody(required = false) RagRetrievalProfileRequestDTO request) {
        // 版本缺失由本方法直接拒绝，其余校验由领域层负责，两类异常都收敛成统一响应。
        try {
            // 检索策略可能被多个管理员同时编辑，缺少版本时禁止覆盖。
            if (request == null || request.getExpectedRevision() == null) {
                // 明确告诉前端缺的是 expectedRevision，让它刷新页面取到最新版本后再提交。
                throw new AppException("RAG_PROFILE_REVISION_REQUIRED", "更新检索策略必须携带expectedRevision");
            }
            // 带上可信身份、策略编号、期望版本和归一化后的新参数做乐观锁更新，成功后返回含新 revision 的结果。
            return success(toProfile(service.updateProfile(tenant(), user(), role(), profileId,
                    request.getExpectedRevision(), values(request))));
        } catch (AppException exception) {
            // 版本冲突或权限不足时原样返回业务错误码，前端应提示刷新页面后重试。
            return failure(exception);
        }
    }

    /**
     * 查询当前租户下可管理的检索策略列表，供配置页面选择和编辑。
     *
     * <p>不写库、不改状态。可见范围由领域层按租户和角色决定，因此不同角色看到的列表可能不同。</p>
     */
    @GetMapping("/retrieval-profiles")
    public Response<List<RagRetrievalProfileResponseDTO>> listProfiles() {
        // 身份缺失或角色不足时领域层会拒绝，统一接住转成错误码。
        try {
            // 用可信身份取列表，再逐份裁剪成对外 DTO，避免把领域实体直接序列化出去。
            return success(service.listProfiles(tenant(), user(), role()).stream().map(this::toProfile).toList());
        } catch (AppException exception) {
            // 原样返回业务错误码，不返回空列表——否则前端会误以为真的没有策略。
            return failure(exception);
        }
    }

    /**
     * 把「某个知识库 + 某份检索策略」绑定到一个运行目标（Agent、工作流或工作流节点）。
     *
     * <p>绑定就是那句「谁在回答时该查哪个知识库、按什么参数查」。required 表示这个知识库是否必须命中，
     * priority 决定多个绑定同时存在时谁先用，maxTokens 限制这个绑定最多能占多少上下文预算。</p>
     *
     * <p>数据流：
     * 绑定请求
     * → 拒绝空请求
     * → 目标类型字符串严格解析成枚举
     * → 可空布尔与数值归一化
     * → 领域层校验知识库归属与权限并落库
     * → 返回绑定编号与版本</p>
     *
     * <p>会写数据库。主要失败情形：请求体为空、目标类型不受支持、知识库不属于本租户、
     * 同一目标上已有重复绑定、角色无权配置。</p>
     *
     * @param request 运行目标、知识库、策略、优先级及上下文预算
     * @return 新绑定及版本
     */
    @PostMapping("/bindings")
    public Response<RagBindingResponseDTO> createBinding(
            @RequestBody(required = false) RagBindingCreateRequestDTO request) {
        // 枚举非法、归属校验失败、重复绑定都是可预期拒绝，统一接住转成错误码。
        try {
            // 没有请求体就无法确定绑定到哪个目标，立刻拒绝。
            if (request == null) throw new AppException("RAG_BINDING_INVALID", "绑定参数不能为空");
            // 在触发器边界完成枚举和可空数值归一化，领域层接收强类型参数。
            RagRetrievalConfigurationService.BindingValues values = new RagRetrievalConfigurationService.BindingValues(
                    enumValue(RagBindingTargetType.class, request.getTargetType(), "RAG_BINDING_TARGET_INVALID"),
                    request.getTargetId(), request.getKnowledgeBaseId(), request.getProfileId(),
                    Boolean.TRUE.equals(request.getRequired()), integer(request.getMaxTokens()),
                    integer(request.getPriority()));
            // 带上可信身份和归一化后的绑定参数落库，成功后裁剪成对外 DTO 返回（含 revision 供后续删除使用）。
            return success(toBinding(service.createBinding(tenant(), user(), role(), values)));
        } catch (AppException exception) {
            // 原样返回业务错误码，前端据此提示用户修正目标或知识库。
            return failure(exception);
        }
    }

    /**
     * 查询当前租户下全部运行目标绑定，供配置页面展示「谁挂了哪个知识库」。
     *
     * <p>不写库、不改状态。可见范围由领域层按租户和角色决定。</p>
     */
    @GetMapping("/bindings")
    public Response<List<RagBindingResponseDTO>> listBindings() {
        // 身份缺失或角色不足时领域层会拒绝，统一接住转成错误码。
        try {
            // 用可信身份取绑定列表，再逐条裁剪成对外 DTO。
            return success(service.listBindings(tenant(), user(), role()).stream().map(this::toBinding).toList());
        } catch (AppException exception) {
            // 原样返回业务错误码，避免前端把「无权查看」误当成「没有绑定」。
            return failure(exception);
        }
    }

    /**
     * 用乐观锁删除一条运行目标绑定。
     *
     * <p>删掉绑定意味着对应的 Agent 或节点从此不再查这个知识库，回答质量会立刻变化，
     * 所以同样要求带版本号，防止基于过期页面误删别人刚建好的绑定。</p>
     *
     * <p>会写数据库。返回布尔真只表示删除动作已完成；版本不一致、绑定不存在、角色无权时返回业务错误码。</p>
     *
     * @param bindingId 绑定ID
     * @param expectedRevision 客户端读取到的绑定版本
     * @return 删除成功标志
     */
    @DeleteMapping("/bindings/{bindingId}")
    public Response<Boolean> deleteBinding(@PathVariable String bindingId,
                                           @RequestParam(value = "expectedRevision", required = false)
                                           // 版本参数允许缺省，缺省时下面会直接拒绝，绝不做无条件删除。
                                           Long expectedRevision) {
        // 版本缺失由本方法拒绝，版本冲突和权限不足由领域层拒绝，两类都收敛成统一响应。
        try {
            // 没带版本号就不执行删除，这是防误删的最后一道闸。
            if (expectedRevision == null) {
                // 明确告诉前端缺的是 expectedRevision，避免把删除失败误当成系统故障。
                throw new AppException("RAG_BINDING_REVISION_REQUIRED", "删除绑定必须携带expectedRevision");
            }
            // 带上可信身份和期望版本执行删除；版本不匹配时领域层会抛异常，不会误删已被改动的绑定。
            service.deleteBinding(tenant(), user(), role(), bindingId, expectedRevision);
            // 删除成功，回一个布尔真；前端据此把这条从列表里移除。
            return success(true);
        } catch (AppException exception) {
            // 版本冲突或无权删除时原样返回业务错误码，前端应提示刷新后重试。
            return failure(exception);
        }
    }

    /**
     * 把外部检索策略请求归一化成领域值对象。
     *
     * <p>这是一道边界：外部传进来的枚举是字符串、数值可能为 null。领域层不该到处判空，
     * 所以在这里一次性把枚举严格解析、把可空数值补成 0，让领域层只面对强类型非空参数。</p>
     *
     * <p>两个布尔的默认方向是刻意相反的：重排和查询改写默认关（不填就不开，避免额外开销和费用），
     * 去重默认开（只有显式传 false 才关，因为重复片段挤占上下文的危害更大）。</p>
     *
     * <p>不查库、不改状态。请求体为空时直接抛业务异常，绝不让空参数往下走。</p>
     */
    private RagRetrievalConfigurationService.ProfileValues values(RagRetrievalProfileRequestDTO request) {
        // 没有参数就没有可保存的策略，立刻拒绝。
        if (request == null) throw new AppException("RAG_PROFILE_INVALID", "检索策略参数不能为空");
        // 逐项归一化：名称原样带过；检索模式与融合策略严格解析成枚举；两个权重和一串 TopK/预算值
         // 缺省时补 0，由领域层判断 0 是否合法；重排与查询改写不填即关，去重只有显式传 false 才关。
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

    /**
     * 把检索策略领域实体翻成对外 DTO。
     *
     * <p>模式和融合策略两个枚举统一转小写，作为稳定的 API 取值，避免以后枚举改名把前端带崩。
     * revision 必须回给前端，下次编辑要原样带回来做乐观锁。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagRetrievalProfileResponseDTO toProfile(RagRetrievalProfileEntity value) {
        // 逐字段搬运：策略身份与名称、两个枚举（转小写）、稠密与稀疏的权重和取数量、
         // 融合与重排配置、最终条数、邻域窗口与上下文预算、分数阈值、两个开关，最后带上乐观锁版本。
        return RagRetrievalProfileResponseDTO.builder().profileId(value.profileId()).name(value.name())
                .mode(value.mode().name().toLowerCase()).fusionStrategy(value.fusionStrategy().name().toLowerCase())
                .denseWeight(value.denseWeight()).sparseWeight(value.sparseWeight())
                .denseTopK(value.denseTopK()).sparseTopK(value.sparseTopK()).fusionTopK(value.fusionTopK())
                .rerankEnabled(value.rerankEnabled()).rerankTopK(value.rerankTopK()).finalTopK(value.finalTopK())
                .neighborWindow(value.neighborWindow()).maxContextTokens(value.maxContextTokens())
                .scoreThreshold(value.scoreThreshold()).queryRewriteEnabled(value.queryRewriteEnabled())
                .deduplicateEnabled(value.deduplicateEnabled()).revision(value.revision()).build();
    }

    /**
     * 把运行目标绑定翻成对外 DTO。
     *
     * <p>目标类型枚举转小写作为稳定 API 取值；revision 必须回给前端，删除时要原样带回来。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagBindingResponseDTO toBinding(RagAgentBindingEntity value) {
        // 逐字段搬运：绑定身份、运行目标（类型转小写 + 目标编号）、知识库与策略、
         // 是否必须命中、Token 上限、优先级，最后带上乐观锁版本。
        return RagBindingResponseDTO.builder().bindingId(value.bindingId())
                .targetType(value.targetType().name().toLowerCase()).targetId(value.targetId())
                .knowledgeBaseId(value.knowledgeBaseId()).profileId(value.retrievalProfileId())
                .required(value.required()).maxTokens(value.maxTokens()).priority(value.priority())
                .revision(value.revision()).build();
    }

    /**
     * 把外部字符串严格解析成指定的领域枚举。
     *
     * <p>为什么要严格：如果放行未知值或空值，错误会一直漏到检索执行时才爆，那时排查成本高得多。
     * 这里先去掉首尾空格再统一转大写，所以前端传 dense 或 DENSE 都能识别，
     * 但传一个不存在的取值会立刻被拒绝，并复用调用方给的错误码，让前端知道是哪个字段填错了。</p>
     *
     * <p>不查库、不改状态；空值和未知值都抛业务异常。</p>
     */
    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        // 空值直接拒绝：枚举字段没有「不填」这种语义，缺了就无法决定行为。
        if (value == null || value.isBlank()) throw new AppException(code, "枚举参数不能为空");
        // valueOf 对未知取值会抛非法参数异常，必须接住换成业务异常，否则前端会收到 500。
        try {
            // 去掉首尾空格并统一大写后解析，让前端大小写随意但取值必须真实存在。
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // 取值不在枚举里，用调用方指定的错误码告诉前端是哪个字段不受支持。
            throw new AppException(code, "枚举参数不受支持");
        }
    }

    /** 可选整数缺省时归一化为 0；领域层自己判断 0 代表「用默认值」还是非法，这里不替它决定。 */
    private int integer(Integer value) { return value == null ? 0 : value; }

    /** 可选权重缺省时归一化为 0，保证领域值对象里不会出现 null，后续算分不必到处判空。 */
    private BigDecimal decimal(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    /** 取当前请求的可信租户编号；它是所有策略和绑定的隔离边界，缺失会导致读写到别的租户的配置。 */
    private String tenant() { return TenantContextHolder.getTenantId(); }

    /** 取当前请求的可信用户编号；领域层用它判断这份配置该不该让这个人看见和修改。 */
    private String user() { return TenantContextHolder.getUserId(); }

    /** 取当前请求的可信角色编码；领域层用它判断是不是管理员，非管理员的写操作会被拒绝。 */
    private String role() { return TenantContextHolder.getRoleCode(); }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> failure(AppException exception) {
        // 只回错误码和文案，前端据此提示具体原因。
        return Response.<T>builder().code(exception.getCode()).info(exception.getInfo()).build();
    }
}
