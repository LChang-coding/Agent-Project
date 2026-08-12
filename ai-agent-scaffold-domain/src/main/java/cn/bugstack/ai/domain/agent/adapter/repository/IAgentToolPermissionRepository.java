package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;

import java.util.List;

public interface IAgentToolPermissionRepository {
    AgentToolPermissionEntity query(String tenantId, String agentId, String toolCode);
    List<AgentToolPermissionEntity> queryByAgent(String tenantId, String agentId);
    int insert(AgentToolPermissionEntity value);
    int update(AgentToolPermissionEntity value, long expectedRevision);
}
