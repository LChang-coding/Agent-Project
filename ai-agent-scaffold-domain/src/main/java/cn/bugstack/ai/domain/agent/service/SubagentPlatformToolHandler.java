package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.AgentCatalogEntryEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 五个主 Agent 编排平台工具的统一执行入口。 */
@Service
public class SubagentPlatformToolHandler implements PlatformToolHandler {
    private static final String SEARCH = "search_agent_catalog";
    private static final String DELEGATE = "create_subagent_instances";
    private static final String READ = "read_subagent_result";
    private static final String READ_FULL = "read_subagent_full_context";
    private static final String CANCEL = "cancel_subagent_instances";

    private final AgentCatalogService catalogService;
    private final SubagentOrchestrationService orchestrationService;

    @org.springframework.beans.factory.annotation.Autowired
    public SubagentPlatformToolHandler(PlatformToolRegistry registry, AgentCatalogService catalogService,
                                       SubagentOrchestrationService orchestrationService) {
        this.catalogService = catalogService;
        this.orchestrationService = orchestrationService;
        registry.register(SEARCH, this); registry.register(DELEGATE, this);
        registry.register(READ, this); registry.register(READ_FULL, this); registry.register(CANCEL, this);
    }

    /** 保留历史测试构造入口；权限已上移到 ToolGateway 统一执行。 */
    public SubagentPlatformToolHandler(PlatformToolRegistry registry, AgentCatalogService catalogService,
                                       SubagentOrchestrationService orchestrationService,
                                       AgentToolPermissionService ignoredPermissionService,
                                       ToolApprovalService ignoredApprovalService) {
        this(registry, catalogService, orchestrationService);
    }

    @Override
    public PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input,
                                     ToolInvokeContextEntity context) {
        try {
            Trusted trusted = trusted(context);
            String function = tool == null ? null : tool.getFunctionName();
            if (SEARCH.equals(function)) return search(input, trusted);
            if (DELEGATE.equals(function)) return delegate(input, context, trusted);
            if (READ.equals(function)) return read(input, trusted);
            if (READ_FULL.equals(function)) return readFullContext(input, trusted);
            if (CANCEL.equals(function)) return cancel(input, trusted);
            return PlatformToolResult.failure("SUBAGENT_TOOL_UNKNOWN");
        } catch (RuntimeException exception) {
            return PlatformToolResult.failure(exception.getMessage() == null
                    ? "SUBAGENT_TOOL_FAILED" : exception.getMessage());
        }
    }

    private PlatformToolResult search(Map<String, Object> input, Trusted trusted) {
        requireKeys(input, Set.of("query", "category"), false);
        List<AgentCatalogEntryEntity> entries = catalogService.search(trusted.tenantId,
                trusted.allowedSubAgentIds, text(input, "query", false), text(input, "category", false));
        return success(Map.of("agents", entries), Map.of("count", entries.size()));
    }

    private PlatformToolResult delegate(Map<String, Object> input, ToolInvokeContextEntity context, Trusted trusted) {
        if (trusted.summaryOnly) throw new IllegalArgumentException("SUBAGENT_SUMMARY_ONLY");
        requireKeys(input, Set.of("tasks"), true);
        Object raw = input.get("tasks");
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("SUBAGENT_TASKS_INVALID");
        List<SubagentOrchestrationService.TaskRequest> requests = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map) || !Set.of("agentId", "instruction").equals(map.keySet())) {
                throw new IllegalArgumentException("SUBAGENT_TASK_INVALID");
            }
            requests.add(new SubagentOrchestrationService.TaskRequest(mapAgentId(map, "agentId"),
                    mapText(map, "instruction")));
        }
        List<SubagentTaskEntity> tasks = orchestrationService.delegate(supervisor(context, trusted),
                required(context.getFunctionCallId(), "SUBAGENT_FUNCTION_CALL_REQUIRED"), requests);
        List<Map<String, Object>> accepted = tasks.stream().map(task -> Map.<String, Object>of(
                "taskId", task.getTaskId(), "agentId", task.getChildAgentId(), "status", task.getStatus().name())).toList();
        return success(Map.of("accepted", accepted, "waitMode", "EVENT_DRIVEN"), Map.of("count", accepted.size()));
    }

    private PlatformToolResult read(Map<String, Object> input, Trusted trusted) {
        requireKeys(input, Set.of("taskIds"), false);
        List<String> taskIds = stringList(input == null ? null : input.get("taskIds"), 100);
        List<SubagentTaskEntity> tasks = orchestrationService.read(trusted.tenantId, trusted.parentRunId, taskIds);
        List<Map<String, Object>> results = tasks.stream().map(this::result).toList();
        return success(Map.of("results", results), Map.of("count", results.size()));
    }

    private PlatformToolResult cancel(Map<String, Object> input, Trusted trusted) {
        if (trusted.summaryOnly) throw new IllegalArgumentException("SUBAGENT_SUMMARY_ONLY");
        requireKeys(input, Set.of("taskIds"), true);
        int cancelled = orchestrationService.cancel(trusted.tenantId, trusted.parentRunId,
                stringList(input.get("taskIds"), 100));
        return success(Map.of("cancelled", cancelled), Map.of("cancelled", cancelled));
    }

    private PlatformToolResult readFullContext(Map<String, Object> input, Trusted trusted) {
        requireKeys(input, Set.of("taskIds"), true);
        List<String> taskIds = stringList(input.get("taskIds"), 20);
        if (taskIds.isEmpty()) throw new IllegalArgumentException("SUBAGENT_TASK_IDS_REQUIRED");
        List<Map<String, Object>> results = orchestrationService
                .read(trusted.tenantId, trusted.parentRunId, taskIds).stream()
                .map(this::fullContextResult).toList();
        return success(Map.of("results", results), Map.of("count", results.size()));
    }

    private Map<String, Object> result(SubagentTaskEntity task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", task.getTaskId()); value.put("agentId", task.getChildAgentId());
        value.put("status", task.getStatus().name());
        if (task.getResultSummary() != null) value.put("summary", task.getResultSummary());
        value.put("hasFullContext", task.getFullContext() != null && !task.getFullContext().isBlank());
        value.put("summaryTruncated", Boolean.TRUE.equals(task.getSummaryTruncated()));
        if (task.getErrorCode() != null) value.put("errorCode", task.getErrorCode());
        return Map.copyOf(value);
    }

    private Map<String, Object> fullContextResult(SubagentTaskEntity task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", task.getTaskId()); value.put("agentId", task.getChildAgentId());
        value.put("status", task.getStatus().name());
        if (task.getFullContext() != null) value.put("fullContext", task.getFullContext());
        if (task.getErrorCode() != null) value.put("errorCode", task.getErrorCode());
        return Map.copyOf(value);
    }

    private Trusted trusted(ToolInvokeContextEntity context) {
        if (context == null || !"SUPERVISOR".equalsIgnoreCase(context.getOrchestrationRole())) {
            throw new IllegalArgumentException("SUBAGENT_SUPERVISOR_REQUIRED");
        }
        return new Trusted(required(context.getTenantId(), "SUBAGENT_CONTEXT_INVALID"),
                required(context.getUserId(), "SUBAGENT_CONTEXT_INVALID"),
                required(context.getOrchestrationRootRunId(), "SUBAGENT_CONTEXT_INVALID"),
                required(context.getAgentId(), "SUBAGENT_CONTEXT_INVALID"),
                context.getAllowedSubAgentIds() == null ? List.of() : List.copyOf(context.getAllowedSubAgentIds()),
                Boolean.TRUE.equals(context.getOrchestrationSummaryOnly()));
    }

    private SubagentOrchestrationService.TrustedSupervisor supervisor(ToolInvokeContextEntity context, Trusted value) {
        return new SubagentOrchestrationService.TrustedSupervisor(value.tenantId, value.userId,
                value.parentRunId, required(context.getSessionId(), "SUBAGENT_CONTEXT_INVALID"),
                value.parentAgentId, context.getOrchestrationRole(),
                value.allowedSubAgentIds, context.getTraceId());
    }

    private PlatformToolResult success(Map<String, Object> model, Map<String, Object> audit) {
        return new PlatformToolResult(true, model, audit, null);
    }

    private void requireKeys(Map<String, Object> input, Set<String> allowed, boolean required) {
        if (input == null) {
            if (required) throw new IllegalArgumentException("SUBAGENT_INPUT_REQUIRED");
            return;
        }
        if (!allowed.containsAll(new LinkedHashSet<>(input.keySet()))) {
            throw new IllegalArgumentException("SUBAGENT_INPUT_INVALID");
        }
        if (required && input.isEmpty()) throw new IllegalArgumentException("SUBAGENT_INPUT_REQUIRED");
    }

    private String text(Map<String, Object> input, String key, boolean required) {
        Object value = input == null ? null : input.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("SUBAGENT_INPUT_INVALID");
        return text;
    }

    private String mapText(Map<?, ?> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("SUBAGENT_TASK_INVALID");
        return text;
    }

    /** JSON 模型偶尔会把纯数字 Agent ID 序列化为 number，在授权校验前做无损归一化。 */
    private String mapAgentId(Map<?, ?> input, String key) {
        Object value = input.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("SUBAGENT_TASK_INVALID");
    }

    private List<String> stringList(Object value, int maxItems) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > maxItems
                || list.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("SUBAGENT_TASK_IDS_INVALID");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private String required(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
        return value;
    }

    private record Trusted(String tenantId, String userId, String parentRunId, String parentAgentId,
                           List<String> allowedSubAgentIds, boolean summaryOnly) { }
}
