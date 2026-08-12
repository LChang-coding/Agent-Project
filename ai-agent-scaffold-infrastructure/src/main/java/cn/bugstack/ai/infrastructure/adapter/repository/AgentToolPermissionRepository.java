package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentToolPermissionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.infrastructure.dao.IAgentToolPermissionDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentToolPermissionPO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AgentToolPermissionRepository implements IAgentToolPermissionRepository {
    private final IAgentToolPermissionDao dao;
    private final ObjectMapper objectMapper;
    public AgentToolPermissionRepository(IAgentToolPermissionDao dao, ObjectMapper objectMapper) {
        this.dao = dao; this.objectMapper = objectMapper;
    }
    @Override public AgentToolPermissionEntity query(String tenantId, String agentId, String toolCode) {
        return entity(dao.query(tenantId, agentId, toolCode));
    }
    @Override public List<AgentToolPermissionEntity> queryByAgent(String tenantId, String agentId) {
        return dao.queryByAgent(tenantId, agentId).stream().map(this::entity).toList();
    }
    @Override public int insert(AgentToolPermissionEntity value) { return dao.insert(po(value)); }
    @Override public int update(AgentToolPermissionEntity value, long expectedRevision) {
        return dao.update(po(value), expectedRevision);
    }
    private AgentToolPermissionPO po(AgentToolPermissionEntity value) {
        AgentToolPermissionPO po = new AgentToolPermissionPO(); po.setTenantId(value.getTenantId());
        po.setAgentId(value.getAgentId()); po.setToolCode(value.getToolCode()); po.setMode(value.getMode());
        po.setTimeoutSeconds(value.getTimeoutSeconds()); po.setTimeoutDecision(value.getTimeoutDecision());
        po.setRevision(value.getRevision()); po.setUpdatedBy(value.getUpdatedBy());
        try { po.setSuggestionsJson(objectMapper.writeValueAsString(value.getSuggestions())); }
        catch (Exception exception) { throw new IllegalArgumentException("AGENT_TOOL_PERMISSION_INVALID", exception); }
        return po;
    }
    private AgentToolPermissionEntity entity(AgentToolPermissionPO value) {
        if (value == null) return null;
        try {
            List<String> suggestions = value.getSuggestionsJson() == null ? List.of()
                    : objectMapper.readValue(value.getSuggestionsJson(), new TypeReference<>() { });
            return AgentToolPermissionEntity.builder().tenantId(value.getTenantId()).agentId(value.getAgentId())
                    .toolCode(value.getToolCode()).mode(value.getMode()).timeoutSeconds(value.getTimeoutSeconds())
                    .timeoutDecision(value.getTimeoutDecision()).suggestions(suggestions)
                    .revision(value.getRevision()).updatedBy(value.getUpdatedBy()).build();
        } catch (Exception exception) { throw new IllegalStateException("AGENT_TOOL_PERMISSION_DATA_INVALID", exception); }
    }
}
