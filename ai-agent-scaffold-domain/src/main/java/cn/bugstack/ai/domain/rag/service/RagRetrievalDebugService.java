package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * 给租户管理员用的「检索调试」入口：输入一个问题，看看当前配置到底会召回哪些资料。
 *
 * <p>解决什么问题：RAG 效果不好时，管理员需要知道是知识库没切好、检索参数不合适，还是压根没命中。
 * 但检索链路本身会读到文档正文，绝不能变成一个「随便填个目标 ID 就能翻别人资料」的后门。
 * 所以这里在真正检索之前，把身份、目标、问题、Token 预算逐项卡死。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用编排服务，只做前置校验和参数归一，不自己实现检索算法。</p>
 *
 * <p>谁会调用它：调试用的 HTTP 控制器（RagRetrievalDebugController），面向租户管理员。</p>
 *
 * <p>它向下调用什么：授权服务判角色、绑定仓储确认目标真有绑定、检索服务跑真正的召回，
 * 并且把诊断开关打开，让返回结果里额外带上各阶段候选轨迹。</p>
 *
 * <p>它不负责什么：不实现向量化、召回、融合、重排，不做知识库可见性判断（那是检索服务内部的事），
 * 不写任何数据，也不会因为调试而改变线上会话的上下文。</p>
 */
@Service
public class RagRetrievalDebugService {

    /**
     * 绑定关系仓储，这里只用它做一件事：确认调试目标在当前租户下确实配过知识库绑定。
     *
     * <p>为什么必须查：没有绑定就意味着这个 Agent 或工作流本来根本不会走 RAG，
     * 允许调试就等于让管理员凭空指定一个目标去触发检索。查询天然带租户号，跨租户的绑定查不出来。</p>
     */
    private final IRagRepository repository;
    /**
     * 授权服务，用来确认发起调试的人是本租户的 owner 或 admin。
     *
     * <p>调试结果里会带回文档正文片段和候选轨迹，属于敏感信息，所以普通成员一律不开放。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorization;
    /**
     * 线上真正在用的检索服务，调试和聊天共用同一个实现。
     *
     * <p>刻意不做一套「调试专用检索」，否则调试看到的结果和线上跑出来的结果会不一致，调优就失去意义。
     * 差别只有一个：调试时把诊断开关置为 true，多带回各阶段候选轨迹。</p>
     */
    private final RagRetrievalService retrievalService;

    /**
     * 由 Spring 注入绑定仓储、授权服务和线上检索服务；三者都是必需依赖，缺一不可。
     */
    public RagRetrievalDebugService(IRagRepository repository,
                                    RagKnowledgeBaseAuthorizationService authorization,
                                    RagRetrievalService retrievalService) {
        // 保存绑定仓储引用，后面用它确认调试目标是否真的配置过知识库。
        this.repository = repository;
        // 保存授权服务引用，每次调试都要先过管理员校验。
        this.authorization = authorization;
        // 保存检索服务引用，保证调试走的是线上同一条召回链路。
        this.retrievalService = retrievalService;
    }

    /**
     * 跑一次带诊断信息的检索，把候选轨迹返回给管理员做效果分析。
     *
     * <p>各层职责：
     * 第一层：权限校验，只有本租户管理员能调试；
     * 第二层：入参校验，调试目标和问题都必须是实实在在的值；
     * 第三层：绑定存在性校验，目标必须真的配过知识库，避免凭空指定目标触发检索；
     * 第四层：预算校验，Token 上限必须在合理区间，防止一次调试把上下文和费用打爆；
     * 第五层：交给线上检索服务执行，并打开诊断开关取回各阶段候选轨迹。</p>
     *
     * <p>数据流：
     * 管理员调试请求（目标类型、目标 ID、问题、Token 预算）
     * → 管理员权限校验
     * → 目标与问题非空校验
     * → 目标 ID 去空格归一
     * → 按租户查绑定，确认目标确实接入了 RAG
     * → Token 预算区间校验
     * → 组装检索请求（诊断开关打开，会话与运行号留空）
     * → 线上检索服务执行召回
     * → 返回引用列表 + 阶段指标 + 候选轨迹</p>
     *
     * <p>关键输入：targetType 区分 Agent 还是工作流；maxContextTokens 是这次调试允许注入的上下文预算；
     * traceId 用于把这次调试和链路日志串起来。</p>
     *
     * <p>不写任何数据、不改任何状态、不影响线上会话；但会真实读取文档分块正文，所以权限必须卡死。
     * 主要失败条件：非管理员、目标或问题为空、目标未配置绑定、Token 预算越界。</p>
     */
    public RagRetrievalResult debug(String tenantId, String userId, String roleCode,
                                    RagBindingTargetType targetType, String targetId, String query,
                                    int maxContextTokens, String traceId) {
        // 第一层：先确认是本租户管理员。调试结果会带回资料正文，普通成员看到就等于绕过了权限。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：调试目标必须给全。类型为空无法判断查 Agent 还是查工作流，ID 为空或空白则无从校验归属。
        if (targetType == null || targetId == null || targetId.isBlank()) {
            // 目标不完整直接中断，不进入任何查询，避免用空目标去扫库。
            throw new AppException("RAG_DEBUG_TARGET_INVALID", "调试目标不能为空");
        }
        // 第二层续：问题也必须是真实文本。空问题向量化后没有语义，只会召回一堆噪声，白烧一次向量化开销。
        if (query == null || query.isBlank()) {
            // 问题为空直接中断，提示管理员补上要调试的真实提问。
            throw new AppException("RAG_DEBUG_QUERY_INVALID", "调试问题不能为空");
        }
        // 去掉目标 ID 首尾空格再用。前端输入框很容易带上空格，不归一会导致明明配了绑定却查不到。
        String normalizedTarget = targetId.trim();
        // 第三层：按「当前租户 + 目标类型 + 目标 ID」查绑定。查询本身带租户号，别家租户的绑定查不出来，
        // 因此这一步同时完成了两件事：确认目标接入过 RAG，以及确认这个目标属于当前租户。
        if (repository.listBindings(tenantId, targetType, normalizedTarget).isEmpty()) {
            // 没有任何绑定说明该目标线上本来就不会走 RAG，调试它没有意义，也不允许借此触发检索。
            throw new AppException("RAG_DEBUG_TARGET_NOT_BOUND", "当前租户未给该运行目标配置知识库绑定");
        }
        // 第四层：Token 预算必须落在 1 到 32768 之间。下限防止预算为 0 时组装请求直接报错，
        // 上限防止管理员填一个巨大值，把大量文档正文一次性拉进内存和上下文。
        if (maxContextTokens < 1 || maxContextTokens > 32768) {
            // 预算越界直接中断，让管理员改成合理值再试，而不是先跑一遍再截断。
            throw new AppException("RAG_DEBUG_BUDGET_INVALID", "调试Token预算必须位于1到32768之间");
        }
        // 第五层：组装检索请求交给线上检索服务。sessionId 和 runId 传 null，表示这是一次独立调试，
        // 不挂在任何真实会话上；最后一个 true 打开诊断开关，返回结果里会带上各阶段候选和淘汰原因。
        return retrievalService.retrieve(new RagRetrievalRequest(tenantId, userId, null, null, targetType,
                normalizedTarget, query, traceId, maxContextTokens, true));
    }
}
