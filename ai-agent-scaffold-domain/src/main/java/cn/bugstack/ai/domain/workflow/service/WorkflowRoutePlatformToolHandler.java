package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRouteIntentRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;
import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析可信工作流边并登记模型选择的路由意图，不推进工作流节点。 */
@Service
public class WorkflowRoutePlatformToolHandler implements PlatformToolHandler {

    /** 注册给模型的平台路由函数名。 */
    private static final String FUNCTION_NAME = "select_workflow_route";
    /** 模型仅能提交路由键和理由，不能指定目标节点或可信身份。 */
    private static final Set<String> ALLOWED_INPUTS = Set.of("routeKey", "reason");

    /** 保存和读取按节点、函数调用唯一的路由意图。 */
    private final IWorkflowRouteIntentRepository repository;
    /** 在写入意图前确认根运行仍允许执行。 */
    private final RunControlService runControlService;

    /**
     * 创建不校验运行实时状态的处理器，供只验证路由登记逻辑的测试使用。
     *
     * @param registry 平台工具注册表
     * @param repository 路由意图仓储
     */
    public WorkflowRoutePlatformToolHandler(PlatformToolRegistry registry,
                                            IWorkflowRouteIntentRepository repository) {
        this(registry, repository, null);
    }

    /**
     * 创建生产使用的路由工具处理器，并注册固定函数名。
     *
     * @param registry 平台工具注册表
     * @param repository 路由意图仓储
     * @param runControlService 调用前校验运行仍可执行的服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowRoutePlatformToolHandler(PlatformToolRegistry registry,
                                            IWorkflowRouteIntentRepository repository,
                                            RunControlService runControlService) {
        this.repository = repository;
        this.runControlService = runControlService;
        registry.register(FUNCTION_NAME, this);
    }

    /**
     * 校验模型参数和可信运行坐标，幂等登记当前节点唯一的路由意图。
     *
     * @param tool 服务端为当前节点生成的路由工具目录项
     * @param input 只允许包含 routeKey 和 reason 的模型参数
     * @param context 当前工作流运行和节点的可信上下文
     * @return 登记成功或可安全返回给模型的冲突、校验失败结果
     */
    @Override
    public PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input,
                                     ToolInvokeContextEntity context) {
        try {
            TrustedContext trusted = trusted(context);
            Selection selection = selection(input, trusted.routeDescriptors);
            if (runControlService != null) {
                runControlService.requireExecutable(trusted.tenantId, trusted.userId, trusted.runId, null);
            }

            WorkflowRouteIntentEntity replay = repository.queryByFunctionCall(trusted.tenantId,
                    trusted.functionCallId);
            if (replay != null) return sameInvocationOrConflict(replay, trusted);

            WorkflowRouteIntentEntity selected = repository.queryByNode(trusted.tenantId, trusted.runId,
                    trusted.nodeExecutionId);
            if (selected != null) return sameOrConflict(selected, selection);

            WorkflowRouteIntentEntity intent = WorkflowRouteIntentEntity.builder()
                    .tenantId(trusted.tenantId).userId(trusted.userId).runId(trusted.runId)
                    .nodeExecutionId(trusted.nodeExecutionId).workflowId(trusted.workflowId)
                    .workflowVersion(trusted.workflowVersion).definitionHash(trusted.definitionHash)
                    .nodeId(trusted.sourceNodeId).routeKey(selection.primaryRouteKey)
                    .normalizedRouteKey(selection.normalizedRouteKey).resolvedEdgeId(selection.edgeId)
                    .resolvedTargetNodeId(selection.targetNodeId).reason(selection.reason)
                    .functionCallId(trusted.functionCallId).source("MODEL_TOOL")
                    .status(WorkflowRouteIntentStatus.PENDING).traceId(trusted.traceId).build();
            if (repository.claim(intent) == 1) return registered(intent);

            replay = repository.queryByFunctionCall(trusted.tenantId, trusted.functionCallId);
            if (replay != null) return sameInvocationOrConflict(replay, trusted);
            selected = repository.queryByNode(trusted.tenantId, trusted.runId, trusted.nodeExecutionId);
            if (selected != null) return sameOrConflict(selected, selection);
            return failure("WORKFLOW_ROUTE_CLAIM_CONFLICT", "路由意图并发登记失败，请重试");
        } catch (RouteToolException exception) {
            return failure(exception.code, exception.getMessage());
        }
    }

    /** 相同节点再次选择同一条边时重放成功结果，选择其他边时返回冲突。 */
    private PlatformToolResult sameOrConflict(WorkflowRouteIntentEntity selected, Selection candidate) {
        if (sameResolvedRoute(selected, candidate)) return registered(selected);
        return failure("WORKFLOW_ROUTE_ALREADY_SELECTED", "当前节点已选择其他路由");
    }

    /** 只允许函数调用在完全相同的用户、运行、节点和定义版本内重放。 */
    private PlatformToolResult sameInvocationOrConflict(WorkflowRouteIntentEntity replay, TrustedContext trusted) {
        if (trusted.runId.equals(replay.getRunId()) && trusted.nodeExecutionId.equals(replay.getNodeExecutionId())
                && trusted.workflowId.equals(replay.getWorkflowId())
                && java.util.Objects.equals(trusted.workflowVersion, replay.getWorkflowVersion())
                && trusted.definitionHash.equals(replay.getDefinitionHash())
                && trusted.userId.equals(replay.getUserId())) {
            return registered(replay);
        }
        return failure("WORKFLOW_ROUTE_REPLAY_SCOPE_MISMATCH", "函数调用重放不属于当前工作流节点");
    }

    /** 以服务端解析后的边和目标节点判断两个选择是否等价。 */
    private boolean sameResolvedRoute(WorkflowRouteIntentEntity selected, Selection candidate) {
        return candidate.edgeId.equals(selected.getResolvedEdgeId())
                && candidate.targetNodeId.equals(selected.getResolvedTargetNodeId());
    }

    /** 将已登记意图拆分为模型可见结果和服务端审计字段。 */
    private PlatformToolResult registered(WorkflowRouteIntentEntity intent) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("registered", true);
        model.put("routeKey", intent.getRouteKey());
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("routeKey", intent.getRouteKey());
        audit.put("reason", intent.getReason());
        audit.put("functionCallId", intent.getFunctionCallId());
        audit.put("nodeExecutionId", intent.getNodeExecutionId());
        return new PlatformToolResult(true, Map.copyOf(model), Map.copyOf(audit), null);
    }

    /** 校验模型参数，并将主路由键或受控别名精确解析为当前节点的工作流边。 */
    private Selection selection(Map<String, Object> input,
                                List<PlatformToolResolver.RouteDescriptor> descriptors) {
        if (input == null || !ALLOWED_INPUTS.equals(new LinkedHashSet<>(input.keySet()))) {
            throw error("WORKFLOW_ROUTE_INPUT_INVALID", "路由工具只接受routeKey和reason");
        }
        String candidate = requireText(input.get("routeKey"), "routeKey", 128,
                "WORKFLOW_ROUTE_INPUT_INVALID");
        String reason = requireText(input.get("reason"), "reason", 500,
                "WORKFLOW_ROUTE_INPUT_INVALID");
        String normalized = WorkflowRouteKey.normalize(candidate);
        if (!WorkflowRouteKey.valid(candidate)) {
            throw error("WORKFLOW_ROUTE_KEY_INVALID", "路由键不合法");
        }

        PlatformToolResolver.RouteDescriptor matched = descriptors.stream()
                .filter(descriptor -> descriptor != null && matches(descriptor, candidate))
                .findFirst().orElseThrow(() -> error("WORKFLOW_ROUTE_KEY_INVALID", "路由键不在当前合法边中"));
        PlatformToolResolver.RouteDescriptor primary = descriptors.stream()
                .filter(descriptor -> descriptor != null && sameEdge(descriptor, matched))
                .findFirst().orElse(matched);
        return new Selection(primary.routeKey(), normalized, matched.edgeId(), matched.targetNodeId(), reason);
    }

    /** 判断两个描述符是否指向同一条冻结边，供主键和别名去重。 */
    private boolean sameEdge(PlatformToolResolver.RouteDescriptor left,
                             PlatformToolResolver.RouteDescriptor right) {
        return left.edgeId().equals(right.edgeId()) && left.targetNodeId().equals(right.targetNodeId());
    }

    /** 对服务端主 route key 和受控别名执行精确规范化匹配。 */
    private boolean matches(PlatformToolResolver.RouteDescriptor descriptor, String candidate) {
        if (WorkflowRouteKey.same(descriptor.routeKey(), candidate)) return true;
        return descriptor.aliases() != null && descriptor.aliases().stream()
                .anyMatch(alias -> WorkflowRouteKey.same(alias, candidate));
    }

    /** 校验工具仅用于 TOOL_V2 智能工作流非终点节点，并提取完整运行坐标。 */
    private TrustedContext trusted(ToolInvokeContextEntity context) {
        if (context == null) throw error("PLATFORM_TOOL_CONTEXT_INVALID", "可信工具上下文不能为空");
        if (!"INTELLIGENT".equalsIgnoreCase(context.getWorkflowKind())
                || !"TOOL_V2".equalsIgnoreCase(context.getRoutingProtocolVersion())
                || Boolean.TRUE.equals(context.getTerminalNode())) {
            throw error("PLATFORM_TOOL_CONTEXT_INVALID", "当前节点不允许选择工作流路由");
        }
        List<PlatformToolResolver.RouteDescriptor> descriptors = context.getRouteDescriptors();
        if (descriptors == null || descriptors.isEmpty() || descriptors.stream().anyMatch(this::invalidDescriptor)) {
            throw error("PLATFORM_TOOL_CONTEXT_INVALID", "当前节点缺少完整合法路由边");
        }
        validateDescriptorOwnership(descriptors);
        int workflowVersion;
        try {
            workflowVersion = Integer.parseInt(requireTrusted(context.getWorkflowVersion(), "工作流版本"));
        } catch (NumberFormatException exception) {
            throw error("PLATFORM_TOOL_CONTEXT_INVALID", "工作流版本不合法");
        }
        if (workflowVersion < 1) throw error("PLATFORM_TOOL_CONTEXT_INVALID", "工作流版本不合法");
        return new TrustedContext(requireTrusted(context.getTenantId(), "租户ID"),
                requireTrusted(context.getUserId(), "用户ID"), requireTrusted(context.getRunId(), "运行ID"),
                requireTrusted(context.getWorkflowId(), "工作流ID"), workflowVersion,
                requireTrusted(context.getDefinitionHash(), "定义哈希"),
                requireTrusted(context.getNodeExecutionId(), "节点执行ID"),
                requireTrusted(context.getSourceNodeId(), "源节点ID"),
                requireTrusted(context.getFunctionCallId(), "函数调用ID"),
                requireTrusted(context.getTraceId(), "Trace ID"), List.copyOf(descriptors));
    }

    /** 拒绝标准化后相同但指向不同边的路由描述，避免同一输入产生不确定结果。 */
    private void validateDescriptorOwnership(List<PlatformToolResolver.RouteDescriptor> descriptors) {
        Map<String, PlatformToolResolver.RouteDescriptor> owners = new HashMap<>();
        for (PlatformToolResolver.RouteDescriptor descriptor : descriptors) {
            String normalized = WorkflowRouteKey.normalize(descriptor.routeKey());
            PlatformToolResolver.RouteDescriptor previous = owners.putIfAbsent(normalized, descriptor);
            if (previous != null && !sameEdge(previous, descriptor)) {
                throw error("PLATFORM_TOOL_CONTEXT_INVALID", "归一化路由键同时归属不同边");
            }
        }
    }

    /** 拒绝缺少边、键或目标身份的路由描述符。 */
    private boolean invalidDescriptor(PlatformToolResolver.RouteDescriptor descriptor) {
        return descriptor == null || !WorkflowRouteKey.valid(descriptor.routeKey())
                || blank(descriptor.edgeId()) || blank(descriptor.targetNodeId());
    }

    /** 校验只能来自服务端运行上下文的身份字段。 */
    private String requireTrusted(String value, String name) {
        if (blank(value)) throw error("PLATFORM_TOOL_CONTEXT_INVALID", name + "不能为空");
        return value;
    }

    /** 校验模型可传文本的类型、非空和长度边界。 */
    private String requireText(Object value, String name, int maxLength, String code) {
        if (!(value instanceof String text) || text.isBlank()) throw error(code, name + "不能为空");
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw error(code, name + "长度超限");
        }
        return trimmed;
    }

    /** 判断可信或模型文本是否缺失。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 构造可供模型理解但不包含内部细节的平台工具失败结果。 */
    private PlatformToolResult failure(String code, String message) {
        return PlatformToolResult.failure(code + ":" + message);
    }

    /** 构造携带稳定错误码的路由工具异常。 */
    private RouteToolException error(String code, String message) {
        return new RouteToolException(code, message);
    }

    /**
     * 从服务端工具上下文中校验并提取的工作流运行坐标。
     *
     * <p>这些字段用于限定路由意图的租户、运行、节点和定义版本，不能由模型参数覆盖。</p>
     */
    private record TrustedContext(String tenantId, String userId, String runId, String workflowId,
                                   int workflowVersion, String definitionHash, String nodeExecutionId, String sourceNodeId,
                                  String functionCallId, String traceId,
                                  List<PlatformToolResolver.RouteDescriptor> routeDescriptors) {
    }

    /** 模型提交的路由键与服务端工作流边精确匹配后的结果。 */
    private record Selection(String primaryRouteKey, String normalizedRouteKey, String edgeId,
                             String targetNodeId, String reason) {
    }

    /** 携带稳定错误码的参数或上下文校验异常。 */
    private static final class RouteToolException extends RuntimeException {
        /** 返回给模型和审计记录的稳定错误码。 */
        private final String code;

        /** 创建可以转换为平台工具失败结果的校验异常。 */
        private RouteToolException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
