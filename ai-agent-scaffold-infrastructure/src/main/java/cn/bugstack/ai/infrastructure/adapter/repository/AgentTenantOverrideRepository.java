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
    private final IAgentTenantOverrideDao dao;
    /** 创建仓储；参数是覆盖 DAO；返回仓储实例。 */
    public AgentTenantOverrideRepository(IAgentTenantOverrideDao dao) { this.dao = dao; }
    @Override public AgentTenantOverrideEntity query(String tenantId, String agentId) { return toEntity(dao.query(tenantId, agentId)); }
    @Override public List<AgentTenantOverrideEntity> queryList(String tenantId) { return dao.queryList(tenantId).stream().map(this::toEntity).toList(); }
    @Override public int insert(AgentTenantOverrideEntity value) { return dao.insert(toPO(value)); }
    @Override public int update(AgentTenantOverrideEntity value, long expectedRevision) { return dao.update(toPO(value), expectedRevision); }
    private AgentTenantOverridePO toPO(AgentTenantOverrideEntity value) {
        return AgentTenantOverridePO.builder().tenantId(value.getTenantId()).agentId(value.getAgentId())
                .status(value.getStatus()).reason(value.getReason()).updatedBy(value.getUpdatedBy())
                .revision(value.getRevision()).disabledAt(value.getDisabledAt()).build();
    }
    private AgentTenantOverrideEntity toEntity(AgentTenantOverridePO value) {
        return value == null ? null : AgentTenantOverrideEntity.builder().tenantId(value.getTenantId())
                .agentId(value.getAgentId()).status(value.getStatus()).reason(value.getReason())
                .updatedBy(value.getUpdatedBy()).revision(value.getRevision()).disabledAt(value.getDisabledAt()).build();
    }
}
