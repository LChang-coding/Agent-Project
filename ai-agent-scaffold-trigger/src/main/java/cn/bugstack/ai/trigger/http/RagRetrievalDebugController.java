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
 * RAG 检索链路调试入口：按真实配置跑一次检索，并把中间过程全部摊开给人看。
 *
 * <p>解决什么问题：RAG 答不准时，光看最终答案根本判断不出是哪一步坏了——可能是向量检索没召回，
 * 可能是重排把对的挤掉了，也可能是上下文预算不够被截断了。这个接口用指定 Agent 或工作流的真实绑定
 * 跑一次检索，把每一路候选、每个阶段耗时、每条被淘汰的原因和降级情况都返回，让人能直接定位问题。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端调试页面。</p>
 *
 * <p>谁会调用它：Web 前端的 RAG 调试页面，通过 POST /api/v1/rag/retrieval-debug 调用。</p>
 *
 * <p>它向下调用什么：只调 {@code RagRetrievalDebugService}——由它读绑定与检索策略、做权限过滤、
 * 执行向量与关键词检索、融合、重排、装配上下文，并沿途记录候选轨迹和阶段耗时。</p>
 *
 * <p>它不负责什么：不执行检索算法、不判断谁有权调试、不生成向量、不写库、不改任何配置。
 * 这里只做三件事：拒绝空请求、把目标类型字符串严格解析成枚举、把庞大的领域调试结果逐字段翻成对外 DTO。</p>
 */
@RestController
@RequestMapping("/api/v1/rag/retrieval-debug")
public class RagRetrievalDebugController {

    /**
     * RAG 调试检索领域服务，本控制器唯一的下游依赖。
     *
     * <p>它按运行目标读出真实的绑定和检索策略再执行检索，因此调试结果和线上回答走的是同一套配置，
     * 不存在「调试正常线上不正常」的偏差。权限过滤也在它内部完成。
     * final 且构造注入，并发请求共享同一实例。</p>
     */
    private final RagRetrievalDebugService service;

    /**
     * 启动时由 Spring 注入调试检索服务，注入后依赖不再变化。
     *
     * @param service RAG 调试检索领域服务
     */
    public RagRetrievalDebugController(RagRetrievalDebugService service) {
        // 保存领域服务引用；这是本类唯一的初始化动作。
        this.service = service;
    }

    /**
     * 用指定运行目标的真实绑定执行一次可观测检索。
     *
     * <p>数据流：
     * 调试请求（目标类型 + 目标编号 + 测试问题 + 可选 Token 预算）
     * → 拒绝空请求
     * → 目标类型字符串严格解析成枚举
     * → 取当前 HTTP 链路的 traceId
     * → 领域层读绑定与策略、执行检索、记录候选轨迹与阶段耗时
     * → 逐字段翻成调试响应
     * → 返回引用、候选轨迹、降级原因和各阶段耗时</p>
     *
     * <p>为什么要传 traceId：调试响应里的数字只能说明「哪一步慢、哪条被淘汰」，
     * 真要看细节还得去日志。共用同一个 traceId，就能拿着调试结果直接反查完整检索日志。</p>
     *
     * <p>不写库、不改配置，但会真实调用向量库和重排模型，因此有实际开销。
     * 未提供预算时按 4096 Token 计算，保证调试口径稳定可比。
     * 主要失败情形：请求体为空、目标类型不受支持、该目标没有配置绑定、角色无权调试。</p>
     *
     * @param request 运行目标、测试问题和上下文 Token 预算
     * @return 引用、候选轨迹、降级信息和逐阶段耗时
     */
    @PostMapping
    public Response<RagRetrievalDebugResponseDTO> debug(
            @RequestBody(required = false) RagRetrievalDebugRequestDTO request) {
        // 参数非法、无绑定、权限不足都是可预期拒绝，统一接住转成业务错误码。
        try {
            // 空请求不能确定租户内运行目标，必须在进入昂贵的模型调用前拒绝。
            if (request == null) throw new AppException("RAG_DEBUG_REQUEST_INVALID", "调试请求不能为空");
            // traceId 与 HTTP 链路保持一致，便于从调试响应反查完整检索日志。
            RagRetrievalResult result = service.debug(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(),
                    targetType(request.getTargetType()), request.getTargetId(), request.getQuery(),
                    request.getMaxContextTokens() == null ? 4096 : request.getMaxContextTokens(),
                    TraceContext.currentOrNewTraceId());
            // 检索完成，把庞大的领域结果逐字段翻成调试响应返回。
            return success(toResponse(result));
        } catch (AppException exception) {
            // 原样返回领域层给出的错误码和文案，前端据此提示是参数问题还是配置缺失。
            return Response.<RagRetrievalDebugResponseDTO>builder().code(exception.getCode())
                    .info(exception.getInfo()).build();
        }
    }

    /**
     * 把外部字符串严格解析成受支持的绑定目标枚举。
     *
     * <p>为什么严格：目标类型决定去读哪张绑定表，猜错就会检索到完全不相干的知识库，
     * 而且报错会推迟到很后面才出现。这里先去空格再统一大写，所以前端传 agent 或 AGENT 都行，
     * 但传一个不存在的取值会立刻被拒绝。</p>
     *
     * <p>不查库、不改状态；空值和未知值都抛业务异常。</p>
     */
    private RagBindingTargetType targetType(String value) {
        // 空值直接拒绝：不知道调试对象是谁就无法读取任何绑定。
        if (value == null || value.isBlank()) throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不能为空");
        // valueOf 遇到未知取值会抛非法参数异常，必须接住换成业务异常，否则前端会收到 500。
        try {
            // 去掉首尾空格并统一大写后解析，让前端大小写随意但取值必须真实存在。
            return RagBindingTargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // 取值不在支持范围内，告诉前端目标类型填错了。
            throw new AppException("RAG_DEBUG_TARGET_INVALID", "目标类型不受支持");
        }
    }

    /**
     * 把完整的领域检索结果翻成调试响应。
     *
     * <p>这是一道边界，也是本类最主要的工作：领域结果是嵌套很深的对象（指标、引用、诊断、候选轨迹），
     * 不能直接序列化出去。这里逐层展开成前端约定的结构。</p>
     *
     * <p>数据流：领域检索结果 → 取出指标对象 → 展开候选数量与各阶段耗时
     * → 引用逐条转成 DTO → 诊断与候选轨迹逐条转成 DTO → 组装完整调试响应。</p>
     *
     * <p>不查库、不改状态，纯结构转换；入参为空会抛空指针，调用方必须先确认有值。</p>
     */
    private RagRetrievalDebugResponseDTO toResponse(RagRetrievalResult result) {
        // 指标单独展开，避免把领域对象直接暴露为外部协议。
        RagRetrievalResult.Metrics metrics = result.metrics();
        // 组装响应：先是本次检索编号、预估 Token 数和降级信息（降级说明某一路检索失败后走了兜底，
         // 这往往就是答不准的直接原因）；接着摊开四路候选数量和从向量化到装配的每一步耗时，
         // 用来定位是哪一步慢或哪一步召回为零；最后带上最终进入上下文的引用和候选诊断轨迹。
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

    /**
     * 把一条最终进入上下文的引用翻成对外 DTO，并保留它在各检索阶段拿到的分数。
     *
     * <p>四个分数（稠密、稀疏、融合、重排）一起返回，才能看出一条引用是靠哪一路召回、
     * 又是在哪一步被提上来或压下去的；rank 和 context 则用来核对最终塞给模型的原文对不对。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagRetrievalDebugResponseDTO.Citation toCitation(RagRetrievalResult.Citation value) {
        // 逐字段搬运：引用编号与最终排名、文档定位信息（知识库、文档、版本、代数、分块、页码、标题路径）、
         // 实际送进上下文的原文，以及四个阶段的分数和附加元数据。
        return RagRetrievalDebugResponseDTO.Citation.builder().citationId(value.citationId()).rank(value.rank())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .documentName(value.documentName()).documentVersion(value.documentVersion())
                .generation(value.generation()).chunkId(value.chunkId()).context(value.context())
                .pageNumber(value.pageNumber()).headingPath(value.headingPath())
                .denseScore(value.denseScore()).sparseScore(value.sparseScore())
                .fusionScore(value.fusionScore()).rerankScore(value.rerankScore())
                .metadata(value.metadata()).build();
    }

    /**
     * 把候选诊断汇总翻成对外 DTO。
     *
     * <p>为什么要 truncated 和两个 count：候选可能成千上万，全部返回会把响应撑爆，
     * 所以领域层只采样一部分。前端必须知道「是不是被截断了、采了多少、上限多少」，
     * 否则会把采样结果误当成全部候选来分析，得出错误结论。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagRetrievalDebugResponseDTO.Diagnostics toDiagnostics(RagRetrievalResult.Diagnostics value) {
        // 逐字段搬运：诊断是否开启、是否被截断、实际采集数与采集上限，以及逐条候选轨迹。
        return RagRetrievalDebugResponseDTO.Diagnostics.builder().enabled(value.enabled())
                .truncated(value.truncated()).capturedCount(value.capturedCount())
                .maxCapturedCount(value.maxCapturedCount())
                .candidates(value.candidates().stream().map(this::toCandidate).toList()).build();
    }

    /**
     * 把单条候选在融合、重排和淘汰各阶段的轨迹翻成对外 DTO。
     *
     * <p>outcome 是关键字段：它说明这条候选最终是被采用了、被分数阈值淘汰了、还是被条数限制挤掉了。
     * 配上 bindingId 和 profileId，就能定位到是哪个绑定、哪份策略把它筛掉的。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagRetrievalDebugResponseDTO.Candidate toCandidate(RagRetrievalResult.CandidateTrace value) {
        // 逐字段搬运：来自哪个绑定与策略、所处阶段与排名、文档定位信息、四个阶段分数，
         // 最后是这条候选的最终去向（采用还是被淘汰）。
        return RagRetrievalDebugResponseDTO.Candidate.builder().bindingId(value.bindingId())
                .profileId(value.profileId()).stage(value.stage()).rank(value.rank())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .versionId(value.versionId()).generation(value.generation()).chunkId(value.chunkId())
                .headingPath(value.headingPath())
                .denseScore(value.denseScore()).sparseScore(value.sparseScore())
                .fusionScore(value.fusionScore()).rerankScore(value.rerankScore())
                .outcome(value.outcome()).build();
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }
}
