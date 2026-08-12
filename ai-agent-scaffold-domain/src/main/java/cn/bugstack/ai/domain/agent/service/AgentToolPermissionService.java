package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentToolPermissionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 主 Agent 平台工具权限配置；运行时审批请求不存放在这里。 */
@Service
public class AgentToolPermissionService {
    private static final Set<String> TOOLS = Set.of("create_subagent_instances");
    private static final Set<String> MODES = Set.of("ALLOW", "DENY", "REQUIRE_APPROVAL");
    private static final Set<String> TIMEOUT_DECISIONS = Set.of("APPROVE", "REJECT");
    private final IAgentToolPermissionRepository repository;
    private final AgentAvailabilityService availabilityService;

    public AgentToolPermissionService(IAgentToolPermissionRepository repository,
                                      AgentAvailabilityService availabilityService) {
        this.repository = repository; this.availabilityService = availabilityService;
    }

    public AgentToolPermissionEntity resolve(String tenantId, String agentId, String toolCode) {
        require(tenantId, agentId, toolCode);
        AgentToolPermissionEntity value = repository.query(tenantId, agentId, toolCode);
        return value == null ? AgentToolPermissionEntity.builder().tenantId(tenantId).agentId(agentId)
                .toolCode(toolCode).mode("ALLOW").timeoutSeconds(600).timeoutDecision("REJECT")
                .suggestions(List.of("按建议参数创建", "减少子 Agent 数量", "拒绝本次创建"))
                .revision(0L).build() : value;
    }

    public List<AgentToolPermissionEntity> queryByAgent(String tenantId, String agentId) {
        requireText(tenantId); requireText(agentId);
        List<AgentToolPermissionEntity> values = repository.queryByAgent(tenantId, agentId);
        return values == null || values.isEmpty()
                ? List.of(resolve(tenantId, agentId, "create_subagent_instances")) : values;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentToolPermissionEntity update(String tenantId, String userId, String roleCode, String agentId,
                                             String toolCode, String mode, Integer timeoutSeconds,
                                             String timeoutDecision, List<String> suggestions,
                                             Long expectedRevision) {
        requireAdmin(tenantId, userId, roleCode); require(tenantId, agentId, toolCode);
        if (!availabilityService.isStaticAgent(agentId)) throw error("AGENT_CONFIG_NOT_FOUND");
        boolean supervisor = availabilityService.queryConfigs(tenantId, true).stream()
                .anyMatch(value -> agentId.equals(value.getAgentId())
                        && "SUPERVISOR".equalsIgnoreCase(value.getOrchestrationRole()));
        if (!supervisor) throw error("AGENT_TOOL_PERMISSION_SUPERVISOR_REQUIRED");
        String normalizedMode = upper(mode);
        String normalizedTimeout = upper(timeoutDecision);
        if (!MODES.contains(normalizedMode) || !TIMEOUT_DECISIONS.contains(normalizedTimeout)) {
            throw error("AGENT_TOOL_PERMISSION_INVALID");
        }
        int timeout = timeoutSeconds == null ? 600 : timeoutSeconds;
        if (timeout < 60 || timeout > 3600) throw error("AGENT_TOOL_PERMISSION_INVALID");
        List<String> safeSuggestions = suggestions == null ? List.of() : suggestions.stream()
                .filter(value -> value != null && !value.isBlank()).limit(8)
                .map(value -> value.substring(0, Math.min(120, value.length()))).toList();
        AgentToolPermissionEntity current = repository.query(tenantId, agentId, toolCode);
        long actual = current == null || current.getRevision() == null ? 0L : current.getRevision();
        if (expectedRevision != null && expectedRevision != actual) throw error("AGENT_TOOL_PERMISSION_CONFLICT");
        AgentToolPermissionEntity value = AgentToolPermissionEntity.builder().tenantId(tenantId).agentId(agentId)
                .toolCode(toolCode).mode(normalizedMode).timeoutSeconds(timeout).timeoutDecision(normalizedTimeout)
                .suggestions(safeSuggestions).revision(current == null ? 0L : actual + 1).updatedBy(userId).build();
        int changed = current == null ? repository.insert(value) : repository.update(value, actual);
        if (changed != 1) throw error("AGENT_TOOL_PERMISSION_CONFLICT");
        return value;
    }

    private void require(String tenantId, String agentId, String toolCode) {
        requireText(tenantId); requireText(agentId); requireText(toolCode);
        if (!TOOLS.contains(toolCode)) throw error("AGENT_TOOL_NOT_CONFIGURABLE");
    }
    private void requireAdmin(String tenantId, String userId, String role) {
        requireText(tenantId); requireText(userId);
        if (!"owner".equalsIgnoreCase(role) && !"admin".equalsIgnoreCase(role)) throw error("FORBIDDEN");
    }
    private void requireText(String value) { if (value == null || value.isBlank()) throw error("AGENT_TOOL_PERMISSION_INVALID"); }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private AppException error(String code) { return new AppException(code, code); }
}
