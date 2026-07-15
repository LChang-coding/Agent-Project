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
 * Agent 租户可用性服务。
 * <p>静态配置是事实源，数据库只保存租户级启停覆盖。</p>
 */
@Service
public class AgentAvailabilityService {

    private final IAgentTenantOverrideRepository repository;
    private final AiAgentAutoConfigProperties properties;

    /** 创建可用性服务；参数是覆盖仓储和静态配置；返回服务实例。 */
    public AgentAvailabilityService(IAgentTenantOverrideRepository repository, AiAgentAutoConfigProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 查询静态 Agent；参数是租户和是否包含禁用项；返回配置状态列表。 */
    public List<AgentConfigStatusEntity> queryConfigs(String tenantId, boolean includeDisabled) {
        requireTenant(tenantId);
        Map<String, AgentTenantOverrideEntity> overrides = repository.queryList(tenantId).stream()
                .collect(Collectors.toMap(AgentTenantOverrideEntity::getAgentId, Function.identity(), (a, b) -> a));
        return staticAgents().stream().map(agent -> status(agent, overrides.get(agent.getAgentId())))
                .filter(item -> includeDisabled || Boolean.TRUE.equals(item.getEnabled())).toList();
    }

    /** 判断静态 Agent 是否启用；参数是租户和 Agent；返回是否可运行。 */
    public boolean isEnabled(String tenantId, String agentId) {
        if (!isStaticAgent(agentId)) return true;
        requireTenant(tenantId);
        AgentTenantOverrideEntity override = repository.query(tenantId, agentId);
        return override == null || !"disabled".equalsIgnoreCase(override.getStatus());
    }

    /** 校验 Agent 可运行；参数是租户和 Agent；禁用时抛出异常。 */
    public void assertEnabled(String tenantId, String agentId) {
        if (!isEnabled(tenantId, agentId)) {
            throw new AppException("AGENT_DISABLED", "当前租户已禁用该智能体");
        }
    }

    /** 更新静态 Agent 状态；参数是可信身份、目标状态和预期版本；返回新状态。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentTenantOverrideEntity updateStatus(String tenantId, String userId, String roleCode, String agentId,
                                                   String status, String reason, Long expectedRevision) {
        requireAdmin(tenantId, userId, roleCode);
        if (!isStaticAgent(agentId)) throw new AppException("AGENT_CONFIG_NOT_FOUND", "静态智能体不存在");
        String target = normalizeStatus(status);
        AgentTenantOverrideEntity current = repository.query(tenantId, agentId);
        if (current != null && target.equals(current.getStatus())) return current;
        long actualRevision = current == null || current.getRevision() == null ? 0L : current.getRevision();
        if (expectedRevision != null && expectedRevision != actualRevision) {
            throw new AppException("AGENT_STATUS_CONFLICT", "智能体状态已变化，请刷新后重试");
        }
        AgentTenantOverrideEntity value = AgentTenantOverrideEntity.builder().tenantId(tenantId).agentId(agentId)
                .status(target).reason(safeReason(reason)).updatedBy(userId).revision(current == null ? 0L : actualRevision + 1)
                .disabledAt("disabled".equals(target) ? LocalDateTime.now() : null).build();
        int changed = current == null ? repository.insert(value) : repository.update(value, actualRevision);
        if (changed != 1) throw new AppException("AGENT_STATUS_CONFLICT", "智能体状态已变化，请刷新后重试");
        return value;
    }

    /** 判断是否静态配置 Agent；参数是 Agent ID；返回匹配结果。 */
    public boolean isStaticAgent(String agentId) {
        return staticAgents().stream().anyMatch(agent -> agentId != null && agentId.equals(agent.getAgentId()));
    }

    private List<AiAgentConfigTableVO.Agent> staticAgents() {
        if (properties.getTables() == null) return List.of();
        return properties.getTables().values().stream().map(AiAgentConfigTableVO::getAgent)
                .filter(agent -> agent != null && agent.getAgentId() != null).toList();
    }

    private AgentConfigStatusEntity status(AiAgentConfigTableVO.Agent agent, AgentTenantOverrideEntity override) {
        boolean enabled = override == null || !"disabled".equalsIgnoreCase(override.getStatus());
        return AgentConfigStatusEntity.builder().agentId(agent.getAgentId()).agentName(agent.getAgentName())
                .agentDesc(agent.getAgentDesc()).status(enabled ? "enabled" : "disabled").enabled(enabled)
                .revision(override == null || override.getRevision() == null ? 0L : override.getRevision())
                .disabledAt(override == null ? null : override.getDisabledAt()).build();
    }

    private String normalizeStatus(String value) {
        if ("active".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value)) return "active";
        if ("disabled".equalsIgnoreCase(value)) return "disabled";
        throw new AppException("AGENT_STATUS_INVALID", "智能体状态只允许 active 或 disabled");
    }

    private void requireAdmin(String tenantId, String userId, String roleCode) {
        requireTenant(tenantId);
        if (userId == null || userId.isBlank() || !("owner".equalsIgnoreCase(roleCode) || "admin".equalsIgnoreCase(roleCode))) {
            throw new AppException("AGENT_STATUS_PERMISSION_DENIED", "只有 owner/admin 可以变更智能体状态");
        }
    }

    private void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new AppException("TENANT_CONTEXT_MISSING", "缺少可信租户身份");
    }

    private String safeReason(String reason) {
        if (reason == null) return null;
        String value = reason.trim();
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
