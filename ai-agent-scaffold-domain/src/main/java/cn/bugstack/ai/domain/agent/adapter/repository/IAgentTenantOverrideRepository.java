package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.AgentTenantOverrideEntity;

import java.util.List;

/**
 * 租户级 Agent 启停覆盖的读写出口。
 *
 * <p>解决什么问题：Agent 的定义是全局静态配置，但每个租户想关掉的 Agent 不一样。
 * 这个仓储只负责持久化「哪个租户关了哪个 Agent」这一点差异，不复制任何 Agent 定义。</p>
 *
 * <p>所属层次：领域层的仓储接口（出口），实现在基础设施层，落到租户覆盖库表上。</p>
 *
 * <p>谁会调用它：{@code AgentAvailabilityService}，用于查询列表状态、判断能否运行、执行管理端启停。</p>
 *
 * <p>它不负责什么：不做权限校验、不做状态取值归一、不判断 agentId 是否真实存在，
 * 这些前置判断全部由领域服务完成，仓储只管把校验过的数据存好取好。</p>
 */
public interface IAgentTenantOverrideRepository {
    /**
     * 读取某个租户对某个 Agent 的覆盖记录。
     *
     * <p>返回 null 表示这个租户从没设置过该 Agent 的开关，按「默认启用」处理。
     * tenantId 必须是可信租户，传错会读到别人的开关状态。</p>
     */
    AgentTenantOverrideEntity query(String tenantId, String agentId);

    /**
     * 一次性读出某个租户的全部覆盖记录，供 Agent 列表页合并状态用。
     *
     * <p>比逐个 Agent 查库少很多次往返；返回空列表表示该租户全部 Agent 都是默认启用。</p>
     */
    List<AgentTenantOverrideEntity> queryList(String tenantId);

    /**
     * 首次为某个租户 + Agent 写入覆盖记录，版本从 0 开始。
     *
     * <p>返回影响行数，正常是 1；返回 0 通常意味着并发下已有别人抢先插入，
     * 领域服务会把它当作状态冲突并要求刷新重试。</p>
     */
    int insert(AgentTenantOverrideEntity override);

    /**
     * 按乐观锁更新已有覆盖记录。
     *
     * <p>只有库里的版本等于 expectedRevision 时才会更新成功，因此返回 0 表示这条记录
     * 已经被别人改过，本次修改必须放弃，避免把并发的修改悄悄覆盖掉。</p>
     */
    int update(AgentTenantOverrideEntity override, long expectedRevision);
}
