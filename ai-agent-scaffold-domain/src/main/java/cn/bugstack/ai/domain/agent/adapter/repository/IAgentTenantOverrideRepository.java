package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.AgentTenantOverrideEntity;

import java.util.List;

/**
 * 租户 Agent 状态覆盖仓储。
 */
public interface IAgentTenantOverrideRepository {
    /** 查询覆盖记录；参数是租户和 Agent；返回覆盖实体。 */
    AgentTenantOverrideEntity query(String tenantId, String agentId);

    /** 查询租户全部覆盖；参数是租户；返回覆盖列表。 */
    List<AgentTenantOverrideEntity> queryList(String tenantId);

    /** 新增覆盖；参数是覆盖实体；返回影响行数。 */
    int insert(AgentTenantOverrideEntity override);

    /** 乐观锁更新覆盖；参数是新值和预期版本；返回影响行数。 */
    int update(AgentTenantOverrideEntity override, long expectedRevision);
}
