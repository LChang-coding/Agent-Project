package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTenantOverrideRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentTenantOverrideEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 判定「某个 Agent 在当前租户能不能用」，并提供管理端的启停能力。
 *
 * <p>解决什么问题：Agent 的定义是全局静态配置，所有租户共享一份；但每个租户想关掉的 Agent 不同。
 * 与其为每个租户复制一份配置（复制就会发散、就要同步），不如让静态配置当唯一事实源，
 * 数据库里只存「哪个租户关了哪个 Agent」这一点差异，查询时把两者合并。</p>
 *
 * <p>所属层次：领域层的领域服务。</p>
 *
 * <p>谁会调用它：{@code ChatService} 在每次建会话和发消息前调用它做准入判断；
 * 管理端接口调用它查列表和改启停状态。</p>
 *
 * <p>它向下调用什么：{@code IAgentTenantOverrideRepository} 读写租户覆盖表，
 * {@code AiAgentAutoConfigProperties} 提供静态 Agent 定义。</p>
 *
 * <p>它不负责什么：不装配 Agent、不执行对话、不管理会话；也不认识工作流内部的运行时 Agent，
 * 那类 ID 不在静态配置里，一律按「不需要租户启停判断」放行。</p>
 */
@Service
public class AgentAvailabilityService {

    /**
     * 租户启停覆盖的读写出口。
     *
     * <p>它只存差异，不存 Agent 定义。判断可用性时先读它，没读到就说明该租户没动过这个 Agent 的开关。</p>
     */
    private final IAgentTenantOverrideRepository repository;
    /**
     * 静态 Agent 定义，是 Agent 身份和元数据的唯一事实源。
     *
     * <p>「系统里有哪些 Agent」「某个 agentId 是否合法」全部以它为准；
     * 它不含任何租户信息，因此单独看它无法判断可用性，必须和覆盖表合并。</p>
     */
    private final AiAgentAutoConfigProperties properties;

    /**
     * 构造可用性服务，注入覆盖仓储和静态配置。
     *
     * <p>用构造注入而不是字段注入，保证两个依赖在对象建好时就已就绪，
     * 不会出现「服务已经能被调用但配置还是空」的中间状态。</p>
     */
    public AgentAvailabilityService(IAgentTenantOverrideRepository repository, AiAgentAutoConfigProperties properties) {
        // 保存覆盖仓储，后续所有租户级启停读写都通过它。
        this.repository = repository;
        // 保存静态配置，后续判断 agentId 合法性和取展示信息都靠它。
        this.properties = properties;
    }

    /**
     * 列出当前租户下所有静态 Agent 及其可用状态。
     *
     * <p>各层职责：
     * 第一层：确认有可信租户身份，没有就直接拒绝，避免无租户地读出全量数据。
     * 第二层：一次性把该租户的全部覆盖记录读出来做成索引，避免对每个 Agent 各查一次库。
     * 第三层：拿静态配置里的 Agent 列表逐个和覆盖记录合并，算出最终状态。
     * 第四层：按调用方要求决定是否把已禁用的 Agent 也返回。</p>
     *
     * <p>数据流：
     * 租户身份
     * → 校验租户非空
     * → 查询该租户全部覆盖记录
     * → 按 agentId 建索引
     * → 遍历静态 Agent 合并状态
     * → 按 includeDisabled 过滤
     * → 返回状态列表</p>
     *
     * <p>只读不写。includeDisabled 传 false 时返回的就是用户下拉框该显示的内容；
     * 传 true 用于管理端，需要看到被关掉的 Agent 才能重新打开它。</p>
     */
    public List<AgentConfigStatusEntity> queryConfigs(String tenantId, boolean includeDisabled) {
        // 第一层：没有可信租户就不允许查询，否则等于跨租户读数据。
        requireTenant(tenantId);
        // 第二层：一次查出该租户所有覆盖记录并按 agentId 建索引；重复记录保留先出现的那条，避免因脏数据抛异常。
        Map<String, AgentTenantOverrideEntity> overrides = repository.queryList(tenantId).stream()
                .collect(Collectors.toMap(AgentTenantOverrideEntity::getAgentId, Function.identity(), (a, b) -> a));
        // 第三、四层：静态配置提供 Agent 全集，逐个合并覆盖状态后按需过滤掉已禁用项。
        return staticAgents().stream().map(agent -> status(agent, overrides.get(agent.getAgentId())))
                .filter(item -> includeDisabled || Boolean.TRUE.equals(item.getEnabled())).toList();
    }

    /**
     * 判断某个 Agent 在指定租户下是否允许运行。
     *
     * <p>非静态 Agent（例如工作流编译出来的内部运行时 Agent）直接放行：它们不对用户暴露，
     * 也没有租户级开关，用户根本没法直接指定它们，再加一道判断只会误伤。</p>
     *
     * <p>默认可用：查不到覆盖记录就当作启用，因此新加的 Agent 上线后所有租户立刻可用，
     * 不需要为每个租户补一条启用记录。</p>
     */
    public boolean isEnabled(String tenantId, String agentId) {
        // 不是静态配置里的 Agent，说明它不走租户启停这套机制，直接放行。
        if (!isStaticAgent(agentId)) return true;
        // 到这里必须有可信租户，否则无法判断该看谁的开关。
        requireTenant(tenantId);
        // 读这个租户对该 Agent 的覆盖记录。
        AgentTenantOverrideEntity override = repository.query(tenantId, agentId);
        // 没有记录或记录不是 disabled 都算可用；只有显式禁用才拦。
        return override == null || !"disabled".equalsIgnoreCase(override.getStatus());
    }

    /**
     * 准入校验：Agent 被当前租户禁用时直接抛业务异常中断请求。
     *
     * <p>放在建会话和发消息之前调用，保证被禁用的 Agent 不会产生任何会话、运行记录和模型消费。</p>
     *
     * <p>抛出的是带明确错误码的业务异常，前端可以据此提示「该智能体已被管理员关闭」。</p>
     */
    public void assertEnabled(String tenantId, String agentId) {
        // 复用可用性判断，只在不可用时把「返回 false」升级成「抛异常」。
        if (!isEnabled(tenantId, agentId)) {
            // 用固定错误码抛出，让上层能识别出这是权限类拒绝而不是系统故障。
            throw new AppException("AGENT_DISABLED", "当前租户已禁用该智能体");
        }
    }

    /**
     * 管理端改某个 Agent 在本租户的启停状态。
     *
     * <p>各层职责：
     * 第一层：权限校验，只有 owner/admin 能改，且必须有可信租户和用户。
     * 第二层：合法性校验，目标 Agent 必须真实存在于静态配置，状态取值必须归一成 active 或 disabled。
     * 第三层：幂等短路，目标状态和当前状态一样就原样返回，不产生无意义的版本递增和审计噪音。
     * 第四层：乐观锁校验，调用方给的预期版本必须等于库里的实际版本，否则说明有人并发改过。
     * 第五层：按有无历史记录选择插入或更新，并用影响行数再确认一次没有被并发抢先。</p>
     *
     * <p>数据流：
     * 管理请求（可信租户+用户+角色）
     * → 权限校验
     * → Agent 存在性校验
     * → 状态取值归一
     * → 读取当前覆盖记录
     * → 幂等判断（相同则直接返回）
     * → 乐观锁版本比对
     * → 组装新覆盖值（版本+1、按需记录禁用时间）
     * → 插入或乐观锁更新
     * → 校验影响行数
     * → 返回新状态</p>
     *
     * <p>会写数据库，整段在事务里；任一步抛异常都会回滚，不会留下「版本涨了但状态没改」的中间态。
     * 主要失败条件：非管理员操作、Agent 不存在、状态取值非法、版本冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentTenantOverrideEntity updateStatus(String tenantId, String userId, String roleCode, String agentId,
                                                   String status, String reason, Long expectedRevision) {
        // 第一层：先卡权限，非 owner/admin 一律不允许改启停。
        requireAdmin(tenantId, userId, roleCode);
        // 第二层：只允许操作静态配置里真实存在的 Agent，避免库里留下指向不存在 Agent 的孤儿记录。
        if (!isStaticAgent(agentId)) throw new AppException("AGENT_CONFIG_NOT_FOUND", "静态智能体不存在");
        // 把外部传来的状态归一成库内只认的两个值，取值非法会在这里抛异常。
        String target = normalizeStatus(status);
        // 读出当前覆盖记录，它决定接下来是插入还是更新，以及实际版本是多少。
        AgentTenantOverrideEntity current = repository.query(tenantId, agentId);
        // 第三层：目标状态和现状一致，直接返回现状；重复点击不会让版本号无意义地往上涨。
        if (current != null && target.equals(current.getStatus())) return current;
        // 计算库里的实际版本：没有记录或版本为空都按 0 处理，和「首次插入版本为 0」保持一致。
        long actualRevision = current == null || current.getRevision() == null ? 0L : current.getRevision();
        // 第四层：调用方带了预期版本就必须匹配，不匹配说明页面上的状态已经过期。
        if (expectedRevision != null && expectedRevision != actualRevision) {
            // 明确告诉前端这是并发冲突，让用户刷新后重试，而不是悄悄覆盖别人的修改。
            throw new AppException("AGENT_STATUS_CONFLICT", "智能体状态已变化，请刷新后重试");
        }
        // 组装要落库的新值：首次插入版本保持 0，更新则在实际版本上加一；禁用时记下时间，启用时清空。
        AgentTenantOverrideEntity value = AgentTenantOverrideEntity.builder().tenantId(tenantId).agentId(agentId)
                .status(target).reason(safeReason(reason)).updatedBy(userId).revision(current == null ? 0L : actualRevision + 1)
                .disabledAt("disabled".equals(target) ? LocalDateTime.now() : null).build();
        // 第五层：没有历史记录就插入，有则按乐观锁更新，两条路径都返回影响行数。
        int changed = current == null ? repository.insert(value) : repository.update(value, actualRevision);
        // 影响行数不是 1 说明期间被别人抢先改了，事务回滚并告知冲突。
        if (changed != 1) throw new AppException("AGENT_STATUS_CONFLICT", "智能体状态已变化，请刷新后重试");
        // 返回本次生效的新状态，管理端据此刷新界面上的版本号。
        return value;
    }

    /**
     * 判断一个 agentId 是不是静态配置里定义的公共 Agent。
     *
     * <p>这道判断是公共入口和内部运行时 Agent 的分界线：只有静态 Agent 才允许用户直接建会话，
     * 也只有它们才受租户启停约束。</p>
     *
     * <p>传入空值时返回 false，避免把「没传 Agent」误判成命中某个配置。</p>
     */
    public boolean isStaticAgent(String agentId) {
        // 在静态 Agent 全集里找有没有编号完全相同的一个；顺带挡掉空入参。
        return staticAgents().stream().anyMatch(agent -> agentId != null && agentId.equals(agent.getAgentId()));
    }

    /**
     * 从所有配置表里取出「身份完整」的静态 Agent 列表。
     *
     * <p>为什么要过滤：配置表可能只写了模块部分而没写对外 Agent，或写了 Agent 但漏了 agentId。
     * 这类残缺配置没法被寻址，放进列表只会导致后续判断出现空指针。</p>
     *
     * <p>没有配置任何表时返回空列表，让上层照常返回空数组而不是报错。</p>
     */
    private List<AiAgentConfigTableVO.Agent> staticAgents() {
        // 一张配置表都没有，说明系统里没有静态 Agent。
        if (properties.getTables() == null) return List.of();
        // 每张表取出它对外暴露的那个 Agent，丢掉缺失或没有编号的残缺配置。
        return properties.getTables().values().stream().map(AiAgentConfigTableVO::getAgent)
                .filter(agent -> agent != null && agent.getAgentId() != null).toList();
    }

    /**
     * 把静态定义和租户覆盖合并成一条对外的状态记录。
     *
     * <p>合并规则：身份和展示信息全部取静态配置，可用性和版本取覆盖记录；
     * 覆盖记录为空表示该租户没设置过，按「启用、版本 0、无禁用时间」处理。</p>
     *
     * <p>数据流：静态 Agent + 覆盖记录 → 判定是否启用 → 填充身份与展示字段 → 填充状态、版本、禁用时间 → 状态实体。</p>
     */
    private AgentConfigStatusEntity status(AiAgentConfigTableVO.Agent agent, AgentTenantOverrideEntity override) {
        // 只有显式 disabled 才算禁用，没有记录一律按启用，保证新 Agent 默认对所有租户可用。
        boolean enabled = override == null || !"disabled".equalsIgnoreCase(override.getStatus());
        // 身份和文案取静态配置，状态与版本取覆盖记录；版本为空时统一按 0，和插入时的初始版本对齐。
        return AgentConfigStatusEntity.builder().agentId(agent.getAgentId()).agentName(agent.getAgentName())
                .agentDesc(agent.getAgentDesc()).status(enabled ? "enabled" : "disabled").enabled(enabled)
                .revision(override == null || override.getRevision() == null ? 0L : override.getRevision())
                .disabledAt(override == null ? null : override.getDisabledAt()).build();
    }

    /**
     * 把外部传来的状态文本归一成库内唯一认可的两个值。
     *
     * <p>为什么需要：前端历史上用过 enabled，库里统一存 active。在入口处收敛成一种写法，
     * 后面所有比对就都可以用简单的字符串相等，不用到处考虑同义词。</p>
     *
     * <p>无法识别的取值直接抛业务异常，宁可拒绝也不能猜测意图后写错状态。</p>
     */
    private String normalizeStatus(String value) {
        // active 和 enabled 都视为启用，统一落成 active。
        if ("active".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value)) return "active";
        // disabled 保持原样，这是唯一表示禁用的写法。
        if ("disabled".equalsIgnoreCase(value)) return "disabled";
        // 其他任何取值都拒绝，避免把无法理解的状态写进库里。
        throw new AppException("AGENT_STATUS_INVALID", "智能体状态只允许 active 或 disabled");
    }

    /**
     * 启停操作的权限闸门：必须有可信租户、有用户身份，且角色是 owner 或 admin。
     *
     * <p>这三条缺一不可——缺租户会改错租户的开关，缺用户则审计字段查不到人，
     * 角色不足则普通成员能关掉整个租户共用的 Agent。</p>
     *
     * <p>不通过就抛业务异常，调用方不会执行任何写库动作。</p>
     */
    private void requireAdmin(String tenantId, String userId, String roleCode) {
        // 先确认租户身份可信，否则连「改谁的开关」都不确定。
        requireTenant(tenantId);
        // 再确认操作人存在且角色足够；owner 和 admin 之外的角色一律拒绝。
        if (userId == null || userId.isBlank() || !("owner".equalsIgnoreCase(roleCode) || "admin".equalsIgnoreCase(roleCode))) {
            // 抛出权限类错误码，前端可据此隐藏或禁用启停按钮。
            throw new AppException("AGENT_STATUS_PERMISSION_DENIED", "只有 owner/admin 可以变更智能体状态");
        }
    }

    /**
     * 确认存在可信租户身份。
     *
     * <p>租户是所有查询和写入的隔离维度，一旦为空就会跨租户读写数据，
     * 所以这里选择直接拒绝而不是用默认值兜底。</p>
     */
    private void requireTenant(String tenantId) {
        // 空值和纯空白都算缺失，立即抛异常中断请求。
        if (tenantId == null || tenantId.isBlank()) throw new AppException("TENANT_CONTEXT_MISSING", "缺少可信租户身份");
    }

    /**
     * 清洗管理员填写的变更原因：去掉首尾空白，并限制在 256 字符内。
     *
     * <p>截断而不是报错，是因为原因只是审计说明，不该因为写得太长就让整个启停操作失败；
     * 但也不能原样入库，否则会超出字段长度导致写入异常。</p>
     */
    private String safeReason(String reason) {
        // 没填原因就保持为空，库里存 null 表示「未说明」。
        if (reason == null) return null;
        // 去掉首尾空白，避免存进一堆看不见的空格。
        String value = reason.trim();
        // 超过 256 字符就截断，保证一定能写进字段。
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
