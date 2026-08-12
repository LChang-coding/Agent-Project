package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentToolPermissionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 主 Agent 平台工具权限配置；运行时审批请求不存放在这里。 */
@Service
public class AgentToolPermissionService {
    private static final Set<String> MODES = Set.of("ALLOW", "DENY", "REQUIRE_APPROVAL");
    private static final Set<String> TIMEOUT_DECISIONS = Set.of("APPROVE", "REJECT");
    private final IAgentToolPermissionRepository repository;
    private final AgentAvailabilityService availabilityService;
    private final IToolRepository toolRepository;

    public AgentToolPermissionService(IAgentToolPermissionRepository repository,
                                      AgentAvailabilityService availabilityService,
                                      IToolRepository toolRepository) {
        this.repository = repository; this.availabilityService = availabilityService;
        this.toolRepository = toolRepository;
    }

    public AgentToolPermissionEntity resolve(String tenantId, String agentId, String toolCode) {
        require(tenantId, agentId, toolCode);
        AgentToolPermissionEntity value = repository.query(tenantId, agentId, toolCode);
        return value == null ? defaultPolicy(tenantId, agentId, toolCode) : value;
    }

    public List<AgentToolPermissionEntity> queryByAgent(String tenantId, String userId, String agentId) {
        requireText(tenantId); requireText(userId); requireText(agentId);
        Map<String, AgentToolPermissionEntity> stored = new LinkedHashMap<>();
        List<AgentToolPermissionEntity> values = repository.queryByAgent(tenantId, agentId);
        if (values != null) values.forEach(value -> stored.put(value.getToolCode(), value));
        List<AgentToolPermissionEntity> result = new ArrayList<>();
        for (ToolDescriptor descriptor : configurableTools(tenantId, userId, agentId)) {
            AgentToolPermissionEntity value = stored.get(descriptor.code());
            if (value == null) value = defaultPolicy(tenantId, agentId, descriptor.code());
            value.setToolName(descriptor.name()); value.setToolType(descriptor.type());
            value.setDescription(descriptor.description()); result.add(value);
        }
        return List.copyOf(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentToolPermissionEntity update(String tenantId, String userId, String roleCode, String agentId,
                                             String toolCode, String mode, Integer timeoutSeconds,
                                             String timeoutDecision, List<String> suggestions,
                                             Long expectedRevision) {
        requireAdmin(tenantId, userId, roleCode); require(tenantId, agentId, toolCode);
        if (!availabilityService.isStaticAgent(agentId)) throw error("AGENT_CONFIG_NOT_FOUND");
        if (configurableTools(tenantId, userId, agentId).stream().noneMatch(value -> toolCode.equals(value.code()))) {
            throw error("AGENT_TOOL_NOT_CONFIGURABLE");
        }
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
        if (toolCode.length() > 190 || !toolCode.matches("[a-zA-Z0-9_:.\\-]+")) {
            throw error("AGENT_TOOL_NOT_CONFIGURABLE");
        }
    }

    /** 把运行目录项转成与数据库权限键一致的编码。 */
    public static String permissionCode(ToolCatalogEntity tool) {
        if (tool == null) return null;
        if (ToolType.PLATFORM.equals(tool.getToolType())) return first(tool.getFunctionName(), tool.getToolCode(), tool.getToolId());
        String identity = first(tool.getToolCode(), tool.getToolId());
        return ToolType.MCP.equals(tool.getToolType()) ? "mcp:" + identity : "skill:" + identity;
    }

    private List<ToolDescriptor> configurableTools(String tenantId, String userId, String agentId) {
        List<ToolDescriptor> result = new ArrayList<>();
        result.add(new ToolDescriptor("rag_retrieve", "RAG 检索", ToolType.PLATFORM, "检索当前运行已绑定的知识库"));
        result.add(new ToolDescriptor("query_trace_logs", "Trace 日志查询", ToolType.PLATFORM, "查询并分析当前运行链路日志"));
        List<AgentConfigStatusEntity> agents = availabilityService.queryConfigs(tenantId, true);
        AgentConfigStatusEntity agent = (agents == null ? List.<AgentConfigStatusEntity>of() : agents).stream()
                .filter(value -> agentId.equals(value.getAgentId())).findFirst().orElse(null);
        if (agent != null && "SUPERVISOR".equalsIgnoreCase(agent.getOrchestrationRole())) {
            result.add(new ToolDescriptor("search_agent_catalog", "检索子 Agent 目录", ToolType.PLATFORM, "检索当前被授权的子 Agent 模板"));
            result.add(new ToolDescriptor("create_subagent_instances", "创建子 Agent", ToolType.PLATFORM, "批量创建临时子 Agent 并异步执行"));
            result.add(new ToolDescriptor("read_subagent_result", "读取子 Agent 结果", ToolType.PLATFORM, "读取当前主运行已收到的结果摘要"));
            result.add(new ToolDescriptor("read_subagent_full_context", "读取子 Agent 完整上下文", ToolType.PLATFORM, "按需读取子 Agent 完整输出"));
            result.add(new ToolDescriptor("cancel_subagent_instances", "取消子 Agent", ToolType.PLATFORM, "取消尚未终结的子 Agent 任务"));
        }
        List<ToolCatalogEntity> catalog = toolRepository.queryAvailableTools(tenantId, userId);
        if (catalog != null) for (ToolCatalogEntity tool : catalog) {
            String code = permissionCode(tool);
            if (code != null && result.stream().noneMatch(value -> code.equals(value.code()))) {
                result.add(new ToolDescriptor(code, first(tool.getToolName(), code), tool.getToolType(), tool.getDescription()));
            }
        }
        return result;
    }

    private AgentToolPermissionEntity defaultPolicy(String tenantId, String agentId, String toolCode) {
        List<String> suggestions = "create_subagent_instances".equals(toolCode)
                ? List.of("按建议参数创建", "减少子 Agent 数量", "拒绝本次创建") : List.of("允许本次调用", "修改参数后允许", "拒绝本次调用");
        return AgentToolPermissionEntity.builder().tenantId(tenantId).agentId(agentId).toolCode(toolCode)
                .mode("ALLOW").timeoutSeconds(600).timeoutDecision("REJECT").suggestions(suggestions)
                .revision(0L).build();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "unknown";
    }

    private record ToolDescriptor(String code, String name, String type, String description) { }
    private void requireAdmin(String tenantId, String userId, String role) {
        requireText(tenantId); requireText(userId);
        if (!"owner".equalsIgnoreCase(role) && !"admin".equalsIgnoreCase(role)) throw error("FORBIDDEN");
    }
    private void requireText(String value) { if (value == null || value.isBlank()) throw error("AGENT_TOOL_PERMISSION_INVALID"); }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private AppException error(String code) { return new AppException(code, code); }
}
