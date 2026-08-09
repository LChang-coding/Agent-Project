package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每轮模型调用之前，负责把「这个用户现在能用的工具」交给 ADK 运行时。
 *
 * <p>所属层次：领域层组件，实现 ADK 的工具集接口，是我们的工具体系接入大模型框架的那个插头。</p>
 *
 * <p>谁调用它：ADK 运行时。每次要请求大模型之前，框架都会来问一次「这轮有哪些工具可用」。</p>
 *
 * <p>它向下调用什么：{@code ToolResolver}（按身份查出已授权的工具目录）、
 * 以及为每个目录项创建一个 {@code GatewayAdkTool} 包装器。</p>
 *
 * <p>为什么不缓存工具清单：这里每轮都重新查库。工具发布、停用、权限变更因此能在下一轮立刻生效，
 * 不需要重启服务或重新装配 Agent。代价是每轮多一次数据库查询，换来的是「管理动作立即见效」这个重要性质——
 * 尤其是出事时停用一个工具必须马上起作用。</p>
 *
 * <p>身份从哪来：只从 ADK 的只读 state 里取，那些值是编排层（ChatService）在启动运行时写进去的可信数据。
 * 绝不采用大模型输出的任何内容作为身份，否则模型被提示词注入操纵后就能越权访问别人的工具。</p>
 *
 * <p>它不负责什么：不执行工具、不做幂等和审计、不判断单个工具的权限（在解析阶段的 SQL 里完成）。</p>
 */
@Component
public class GatewayToolset implements BaseToolset {

    /**
     * 工具解析器。它按租户和用户查出「已发布、有激活版本、且这个用户有权使用」的工具目录。
     *
   * <p>身份不完整时它会直接抛异常而不是返回空列表，这样问题会立刻暴露，
     * 而不是变成「模型莫名其妙说自己没有工具」这种极难排查的现象。</p>
     */
    private final ToolResolver toolResolver;
    /**
     * 工具执行网关。所有工具包装器共享同一个实例，因为它无状态，
     * 所有的门禁、幂等和审计都在它内部依赖数据库完成，不依赖 Java 层的对象状态。
     */
    private final ToolGateway toolGateway;
    /** 根据当前可信运行上下文生成可见的平台内置工具。 */
    private final PlatformToolResolver platformToolResolver;

    /**
     * 注入工具解析器和执行网关，完成构造。
     *
     * <p>只做依赖装配，不预热、不缓存任何工具清单，保证每轮解析拿到的都是最新状态。</p>
   */
    public GatewayToolset(ToolResolver toolResolver, ToolGateway toolGateway) {
        this(toolResolver, toolGateway, new PlatformToolResolver(false, false));
    }

    /**
     * 创建同时支持租户工具和平台内置工具的工具集。
     *
     * @param toolResolver 查询用户可用 Skill/MCP 的解析器
     * @param toolGateway 统一执行和审计工具调用的网关
     * @param platformToolResolver 根据运行上下文生成平台工具的解析器
     */
    @Autowired
    public GatewayToolset(ToolResolver toolResolver, ToolGateway toolGateway,
                          PlatformToolResolver platformToolResolver) {
        // 记住工具解析器，每轮都靠它现查一次可用工具，保证发布和停用立刻生效。
        this.toolResolver = toolResolver;
        // 记住执行网关，本轮所有工具包装器共用同一个实例。
        this.toolGateway = toolGateway;
        this.platformToolResolver = platformToolResolver;
    }

    /**
     * 为本轮模型请求生成实际可用的工具清单。
     *
     * <p>身份、运行编号和节点范围只从服务端写入的 ADK 状态读取。方法每轮重新查询已发布工具，
     * 再按当前节点过滤 MCP 与 Skill、加入符合条件的平台工具，最后检查函数名是否冲突。</p>
     *
     * @param readonlyContext ADK 提供的只读运行状态
     * @return 当前模型请求可以看到的函数工具流
     */
    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        // tenantId 必须来自 ChatService 写入的状态；userId 可使用 ADK 已认证的用户。
        ToolUserContextEntity context = ToolUserContextEntity.builder()
                .tenantId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.TENANT_ID)))
                .userId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.USER_ID)), readonlyContext.userId()))
                .roleCode(stringValue(readonlyContext.state().get("roleCode")))
                .build();
        // 组装运行回退上下文。模型真正调用时若 ADK 上下文缺字段，会用这些可信值补齐。
        // 缺的部分就用这份值补，保证审计里的会话、运行、链路标识不会大面积为空。
        ToolInvokeContextEntity fallbackContext = ToolInvokeContextEntity.builder()
                .tenantId(context.getTenantId())
                .userId(context.getUserId())
                .sessionId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.SESSION_ID)), readonlyContext.sessionId()))
                .workflowId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_ID)))
                .invocationId(readonlyContext.invocationId())
                .runId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RUN_ID)))
                .contextRevision(longValue(readonlyContext.state().get(ToolRuntimeContextKeys.CONTEXT_REVISION)))
                .traceId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.currentOrNewTraceId()))
                .ragInvocationMode(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_INVOCATION_MODE)))
                .ragToolEnabled(booleanValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_TOOL_ENABLED)))
                .workflowMcpIds(stringList(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_MCP_IDS)))
                .workflowSkillIds(stringList(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_SKILL_IDS)))
                .ragMode(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_MODE)))
                .ragEvidenceInvocationId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID)))
                .ragTargetType(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_TARGET_TYPE)))
                .ragTargetId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_TARGET_ID)))
                .ragBindingIds(stringList(readonlyContext.state().get(ToolRuntimeContextKeys.RAG_BINDING_IDS)))
                .workflowKind(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_KIND)))
                .routingProtocolVersion(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.ROUTING_PROTOCOL_VERSION)))
                .terminalNode(booleanValue(readonlyContext.state().get(ToolRuntimeContextKeys.TERMINAL_NODE)))
                .routeDescriptors(routeDescriptors(readonlyContext.state().get(ToolRuntimeContextKeys.ROUTE_DESCRIPTORS)))
                .nodeExecutionId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.NODE_EXECUTION_ID)))
                .sourceNodeId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.SOURCE_NODE_ID)))
                .definitionHash(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.DEFINITION_HASH)))
                .workflowVersion(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.WORKFLOW_VERSION)))
                .routeRepairOnly(booleanValue(readonlyContext.state().get(ToolRuntimeContextKeys.ROUTE_REPAIR_ONLY)))
                .build();
        // 每轮重新查询，发布、停用和权限变化无需重装配 Agent。
        List<ToolCatalogEntity> tools = new ArrayList<>(toolResolver.resolve(context));
        // 工作流节点只能看到画布为该节点配置的 MCP，不能继承用户的全部 MCP 权限。
        if (fallbackContext.getWorkflowKind() != null && fallbackContext.getWorkflowMcpIds() != null) {
            Set<String> allowedMcpIds = new HashSet<>(fallbackContext.getWorkflowMcpIds());
            tools.removeIf(tool -> ToolType.MCP.equals(tool.getToolType()) && !allowedMcpIds.contains(tool.getToolId()));
        }
        // Skill 使用与 MCP 相同的节点范围，工具发现和执行前授权都会再次核对。
        if (fallbackContext.getWorkflowKind() != null && fallbackContext.getWorkflowSkillIds() != null) {
            Set<String> allowedSkillIds = new HashSet<>(fallbackContext.getWorkflowSkillIds());
            tools.removeIf(tool -> ToolType.SKILL.equals(tool.getToolType()) && !allowedSkillIds.contains(tool.getToolId()));
        }
        // RAG、路由和 Trace 日志等平台工具根据本次可信运行状态动态加入。
        tools.addAll(platformToolResolver.resolve(fallbackContext));
        // 补选路径时不再开放业务工具，避免 Agent 在修正路由期间产生新的外部副作用。
        if (Boolean.TRUE.equals(fallbackContext.getRouteRepairOnly())) {
            tools.removeIf(tool -> !ToolType.PLATFORM.equals(tool.getToolType())
                    || !"select_workflow_route".equals(tool.getFunctionName()));
        }
        // 两个工具如果最终函数名相同，模型无法可靠区分，必须在请求模型前明确失败。
        assertUniqueFunctionNames(tools);
        // 每个目录项转换为 ADK 函数工具，并绑定统一执行网关与可信回退上下文。
        return Flowable.fromIterable(tools).map(tool -> new GatewayAdkTool(tool, toolGateway, fallbackContext));
    }

    /** 工具集本身不持有连接；MCP 客户端由每次调用创建并关闭。 */
    @Override
    public void close() {
        // 无需释放资源。
    }

    /** 把运行状态中的值转为字符串，同时保留“未提供”的 null。 */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 运行状态未提供有效值时，退回框架给出的可信值。 */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 上下文版本可能是数字或字符串；无法解析时返回 null，避免误用 0。 */
    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从可信运行时属性恢复可选布尔开关。 */
    private Boolean booleanValue(Object value) {
        return value instanceof Boolean result ? result : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    /** 从可信运行时属性提取非空字符串列表。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(item -> item != null).map(String::valueOf)
                .filter(item -> !item.isBlank()).toList();
    }

    /** 只接受服务端生成的路由描述符，拒绝模型或普通对象伪造。 */
    private List<PlatformToolResolver.RouteDescriptor> routeDescriptors(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(PlatformToolResolver.RouteDescriptor.class::isInstance)
                .map(PlatformToolResolver.RouteDescriptor.class::cast).toList();
    }

    /** 按 ADK 最终函数名检查冲突，防止不同工具规范化后覆盖彼此。 */
    private void assertUniqueFunctionNames(List<ToolCatalogEntity> tools) {
        Set<String> names = new HashSet<>();
        for (ToolCatalogEntity tool : tools) {
            String name = ToolType.PLATFORM.equals(tool.getToolType()) ? tool.getFunctionName()
                    : (ToolType.MCP.equals(tool.getToolType()) ? "mcp_" : "skill_") + defaultString(tool.getToolCode(), tool.getToolId());
            String normalized = name == null ? null : name.replaceAll("[^a-zA-Z0-9_]", "_");
            if (normalized != null && !normalized.matches("^[a-zA-Z_].*")) normalized = "tool_" + normalized;
            if (normalized != null && normalized.length() > 64) normalized = normalized.substring(0, 64);
            if (normalized == null || normalized.isBlank() || !names.add(normalized)) {
                throw new IllegalStateException("工具函数名冲突，已拒绝装配");
            }
        }
    }
}
