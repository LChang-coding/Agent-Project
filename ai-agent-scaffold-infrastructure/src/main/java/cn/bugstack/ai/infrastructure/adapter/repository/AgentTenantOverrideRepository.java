package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTenantOverrideRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentTenantOverrideEntity;
import cn.bugstack.ai.infrastructure.dao.IAgentTenantOverrideDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentTenantOverridePO;
import org.springframework.stereotype.Repository;

import java.util.List;

/** MyBatis 租户 Agent 覆盖仓储。 */
@Repository
public class AgentTenantOverrideRepository implements IAgentTenantOverrideRepository {
    /** 读写租户级 Agent 启停覆盖记录。 */
    private final IAgentTenantOverrideDao dao;

    /** 注入租户 Agent 覆盖 DAO。 */
    public AgentTenantOverrideRepository(IAgentTenantOverrideDao dao) { this.dao = dao; }

    /** 查询一个租户对指定 Agent 的覆盖状态。 */
    @Override public AgentTenantOverrideEntity query(String tenantId, String agentId) { return toEntity(dao.query(tenantId, agentId)); }

    /** 查询租户已经配置的全部 Agent 覆盖记录。 */
    @Override public List<AgentTenantOverrideEntity> queryList(String tenantId) { return dao.queryList(tenantId).stream().map(this::toEntity).toList(); }

    /** 新建租户 Agent 覆盖记录。 */
    @Override public int insert(AgentTenantOverrideEntity value) { return dao.insert(toPO(value)); }

    /** 按预期 revision 更新覆盖状态，返回值用于识别并发修改。 */
    @Override public int update(AgentTenantOverrideEntity value, long expectedRevision) { return dao.update(toPO(value), expectedRevision); }

    /** 将领域覆盖状态复制到持久化对象。 */
    private AgentTenantOverridePO toPO(AgentTenantOverrideEntity value) {
        return AgentTenantOverridePO.builder().tenantId(value.getTenantId()).agentId(value.getAgentId())
                .status(value.getStatus()).reason(value.getReason()).updatedBy(value.getUpdatedBy())
                .revision(value.getRevision()).disabledAt(value.getDisabledAt()).build();
    }
    /** 将数据库记录恢复为领域覆盖状态，未查询到时返回空值。 */
    private AgentTenantOverrideEntity toEntity(AgentTenantOverridePO value) {
        return value == null ? null : AgentTenantOverrideEntity.builder().tenantId(value.getTenantId())
                .agentId(value.getAgentId()).status(value.getStatus()).reason(value.getReason())
                .updatedBy(value.getUpdatedBy()).revision(value.getRevision()).disabledAt(value.getDisabledAt()).build();
    }
}
