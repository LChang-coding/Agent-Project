package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * RAG 绑定目标可用性校验服务。
 * <p>创建绑定前，确认 Agent 已在平台注册且未被当前租户禁用，或者工作流
 * 属于当前租户且已发布。未知目标类型默认拒绝。</p>
 */
@Service
public final class RagBindingTargetAuthorizationService {

    /**
     * 工作流「已发布」这个状态的字面值，与工作流库里存的状态字段对齐。
     *
     * <p>为什么单独抽成常量：绑定只认已发布的工作流。草稿状态的工作流随时会改结构，
     * 绑定上去之后运行时可能找不到节点。比较时忽略大小写，避免历史数据大小写不一致导致误判。</p>
     */
    private static final String PUBLISHED = "published";

    /**
     * Agent 可用性服务，用来回答两个问题：这个 agentId 是不是平台配置里注册过的，以及当前租户有没有把它禁用。
     *
     * <p>Agent 的身份来自平台静态配置（不是租户自己建的），启停状态则是租户级覆盖，
     * 所以「存在」和「可用」必须分两步问，且第二步必须带上租户号。</p>
     */
    private final AgentAvailabilityService agentAvailabilityService;
    /**
     * 工作流仓储，用来核对工作流的租户归属和发布版本。
     *
     * <p>查询强制带租户号，再加上一次实体上租户号的二次比对，双重保证不会把别家租户的工作流当成合法绑定目标。</p>
     */
    private final IWorkflowRepository workflowRepository;

    /**
     * 由 Spring 注入 Agent 与工作流两类绑定目标的可信查询入口；两者都是必需依赖。
     */
    public RagBindingTargetAuthorizationService(AgentAvailabilityService agentAvailabilityService,
                                                IWorkflowRepository workflowRepository) {
        // 保存 Agent 可用性服务引用，后面用它判断 Agent 是否注册且未被本租户禁用。
        this.agentAvailabilityService = agentAvailabilityService;
        // 保存工作流仓储引用，后面用它判断工作流的租户归属和发布状态。
        this.workflowRepository = workflowRepository;
    }

    /**
     * 按目标类型分流，完成绑定目标的存在性、租户归属与可运行状态校验。
     *
     * <p>各层职责：
     * 第一层：识别目标类型，Agent 和工作流的校验口径完全不同（一个查平台配置，一个查租户数据）；
     * 第二层：交给对应的私有方法做真正的存在性与状态判断；
     * 第三层：类型不认识时直接拒绝，绝不放行未知类型。</p>
     *
     * <p>数据流：
     * 可信租户号 + 目标类型 + 目标 ID
     * → 判断类型是 AGENT 还是 WORKFLOW
     * → AGENT：查平台注册 → 查租户启停
     * → WORKFLOW：按租户查实体 → 比对租户号 → 查发布状态与版本号
     * → 全部通过则正常返回，调用方继续写绑定记录
     * → 任一不通过则抛异常，绑定不会落库</p>
     *
     * <p>校验通过时没有返回值，也不写库；失败一律抛业务异常，调用方的写入流程随之中断。</p>
     *
     * <p>为什么未知类型也要拒绝：将来新增目标类型时，如果这里默认放过，就会出现「没人校验的绑定」，
     * 直接变成越权口子。所以宁可让新类型先失败，也不默许放行。</p>
     */
    public void requireAvailable(String tenantId, RagBindingTargetType targetType, String targetId) {
        // 第一层：先看是不是 Agent 目标。Agent 的身份来自平台静态配置，校验方式和工作流完全不同。
        if (targetType == RagBindingTargetType.AGENT) {
            // 第二层：走 Agent 专属校验，不通过会在里面直接抛异常。
            requireAgent(tenantId, targetId);
            // Agent 分支校验完就结束，避免继续落到后面的工作流判断和兜底拒绝。
            return;
        }
        // 第一层续：再看是不是工作流目标。工作流是租户自己的数据，必须额外核对归属和发布状态。
        if (targetType == RagBindingTargetType.WORKFLOW) {
            // 第二层：走工作流专属校验，不通过会在里面直接抛异常。
            requireWorkflow(tenantId, targetId);
            // 工作流分支校验完同样直接结束，不再往下走兜底拒绝。
            return;
        }
        // 第三层：走到这里说明目标类型既不是 Agent 也不是工作流（新增枚举没接校验、或调用方传了脏值）。
        // 这种情况一律拒绝：没有校验规则的目标绝不允许绑定，否则等于开一个无人把关的入口。
        throw new AppException("RAG_BINDING_TARGET_INVALID", "绑定目标类型不受支持");
    }

    /**
     * 校验 Agent 目标：必须是平台注册过的 Agent，且没有被当前租户禁用。
     *
     * <p>两步刻意分开，返回的错误也不同：查不到当成「不存在」，查到但被禁用当成「当前不可运行」。
     * 这样管理员能分清是 ID 填错了，还是自己把这个 Agent 关掉了。</p>
     *
     * <p>不写库、不改状态；不通过则抛异常，绑定不会落库。</p>
     */
    private void requireAgent(String tenantId, String agentId) {
        // 先问平台静态配置里有没有这个 Agent。Agent 不是租户自建的，配置里没有就是彻底不存在。
        if (!agentAvailabilityService.isStaticAgent(agentId)) {
            // 不存在按统一的「不存在或不属于当前租户」返回，不透露更多细节。
            throw notFound();
        }
        // 再带上租户号问一次启停状态：同一个 Agent 可以被某个租户单独禁用，禁用后不允许再绑新知识库。
        if (!agentAvailabilityService.isEnabled(tenantId, agentId)) {
            // 存在但被本租户禁用，用「当前不可运行」区分开，管理员看到就知道要先启用再绑定。
            throw unavailable();
        }
    }

    /**
     * 校验工作流目标：必须属于当前租户，并且至少已经成功发布过一个版本。
     *
     * <p>各层职责：
     * 第一层：按租户查实体，并对实体上的租户号再比一次，杜绝跨租户绑定；
     * 第二层：检查发布状态和已发布版本号，未发布的草稿不允许绑定。</p>
     *
     * <p>数据流：
     * 可信租户号 + 工作流 ID
     * → 按租户查工作流实体
     * → 实体为空或租户号不符 → 统一按「不存在」拒绝
     * → 状态不是 published，或已发布版本号缺失 / 小于 1 → 按「当前不可运行」拒绝
     * → 全部通过则正常返回</p>
     *
     * <p>会读一次工作流库，但不写任何数据。</p>
     */
    private void requireWorkflow(String tenantId, String workflowId) {
        // 按「租户 + 工作流 ID」查实体。查询本身带租户号，是租户隔离的第一道保险。
        WorkflowEntity workflow = workflowRepository.queryWorkflow(tenantId, workflowId);
        // 第一层：实体为空（真的没有）和实体租户号不匹配（仓储实现万一漏了租户条件）合并处理，
        // 这是防止跨租户绑定的第二道保险，哪怕仓储层出了问题也不会放行。
        if (workflow == null || !tenantId.equals(workflow.getTenantId())) {
            // 两种情况都按「不存在或不属于当前租户」返回，让攻击者无法靠错误码差异探测别家租户有哪些工作流。
            throw notFound();
        }
        // 第二层：可运行性由三个条件同时决定，任一不满足就说明这个工作流现在不能被绑定——
        // 条件一，状态必须是 published（草稿随时会改结构，绑上去运行时会找不到节点）；
        // 条件二，已发布版本号不能为空（状态写成 published 但没版本号属于脏数据）；
        // 条件三，已发布版本号必须大于 0（版本号从 1 开始，0 或负数说明从未真正发布成功）。
        if (!PUBLISHED.equalsIgnoreCase(workflow.getStatus())
                || workflow.getPublishedVersion() == null || workflow.getPublishedVersion() < 1) {
            // 工作流确实是本租户的，但现在不具备运行条件，用专门的状态错误码提示管理员先发布再绑定。
            throw unavailable();
        }
    }

    /**
     * 生成统一的「目标不存在或不属于当前租户」异常。
     *
     * <p>刻意把「真的没有」和「是别人的」合并成同一个错误码：如果分开返回，
     * 攻击者就能拿一批 ID 逐个试探，靠错误码差异反推出别家租户到底有哪些 Agent 和工作流。</p>
     */
    private AppException notFound() {
        // 只构造异常并返回，由调用方决定在哪一步抛出，保证所有拒绝路径的错误码完全一致。
        return new AppException("RAG_BINDING_TARGET_NOT_FOUND", "绑定目标不存在或不属于当前租户");
    }

    /**
     * 生成「目标存在但当前不能运行」异常。
     *
     * <p>这条和「不存在」分开，是因为它不泄露任何跨租户信息：能走到这里说明目标本来就是当前租户的，
     * 明确告诉管理员「先启用 / 先发布」比含糊的不存在提示更有用。</p>
     */
    private AppException unavailable() {
        // 同样只构造异常返回，让 Agent 和工作流两条分支共用完全一致的状态错误码。
        return new AppException("RAG_BINDING_TARGET_UNAVAILABLE", "绑定目标当前不可运行");
    }
}
