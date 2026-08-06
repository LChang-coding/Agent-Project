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
    private final PlatformToolResolver platformToolResolver;

    /**
     * 注入工具解析器和执行网关，完成构造。
     *
     * <p>只做依赖装配，不预热、不缓存任何工具清单，保证每轮解析拿到的都是最新状态。</p>
   */
    public GatewayToolset(ToolResolver toolResolver, ToolGateway toolGateway) {
        this(toolResolver, toolGateway, new PlatformToolResolver(false, false));
    }

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
     * 回答 ADK 的提问：这一轮模型可以调用哪些工具？
     *
     * <p>各层职责：
     * 第一层：从只读 state 里取出可信身份（租户、用户、角色）。租户只能来自编排层写入的 state，
     *         用户可以退回 ADK 自己的认证用户，因为那也是框架侧的可信来源。
     * 第二层：再从 state 里凑一份「运行回退上下文」。它的作用是：模型真正发起调用时，
     *         如果 ADK 给的工具上下文缺了某些字段，就用这份回退值补上，避免审计里出现大片空值。
   * 第三层：现查一次可用工具目录，让发布和停用能立刻生效。
     * 第四层：把每个目录项包装成一个 ADK 函数工具，包装时会生成给模型看的函数名、描述和入参 schema。</p>
     *
     * <p>数据流：
     * ADK 只读上下文
   * → 取出可信身份（租户/用户/角色）
     * → 拼出运行回退上下文（会话、工作流、运行、上下文版本、链路标识）
     * → 查询已授权的工具目录
     * → 逐项包装成 ADK 工具
     * → 以数据流形式交还框架</p>
 *
     * <p>返回值直接决定模型这一轮的能力边界：多返回一个工具，模型就多一种触发外部动作的可能；
     * 少返回一个，模型就会明确表示自己做不到。</p>
     *
     * <p>只读，不写库、不改状态。身份不完整时解析器会抛异常，这一轮的工具装配随之失败。</p>
   */
    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
   // tenantId 必须来自 ChatService state；userId 可回退 ADK 认证用户。
        ToolUserContextEntity context = ToolUserContextEntity.builder()
                .tenantId(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.TENANT_ID)))
                .userId(defaultString(stringValue(readonlyContext.state().get(ToolRuntimeContextKeys.USER_ID)), readonlyContext.userId()))
                .roleCode(stringValue(readonlyContext.state().get("roleCode")))
                .build();
 // 第二层：凑一份运行回退上下文。模型真正发起调用时，ADK 给的上下文可能缺字段，
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
        tools.addAll(platformToolResolver.resolve(fallbackContext));
        if (Boolean.TRUE.equals(fallbackContext.getRouteRepairOnly())) {
            tools.removeIf(tool -> !ToolType.PLATFORM.equals(tool.getToolType())
                    || !"select_workflow_route".equals(tool.getFunctionName()));
        }
        assertUniqueFunctionNames(tools);
      // 第四层：逐项包装成 ADK 函数工具。包装动作会冻结函数名、描述和入参 schema，
    // 并把执行网关与回退上下文一起交给每个包装器，模型点哪个就由哪个去走门禁。
        return Flowable.fromIterable(tools).map(tool -> new GatewayAdkTool(tool, toolGateway, fallbackContext));
    }

    /**
     * 框架回收工具集时调用，这里无需释放任何东西。
     *
     * <p>原因是包装器不持有长连接：MCP 客户端由每一次调用现建现关，Skill 包也是现读现取。
     * 这种「不复用连接」的设计换来的好处正是这里——工具集本身没有生命周期负担，
     * 也不会出现「上一次调用的连接状态串到下一次」的问题。</p>
     */
    @Override
    public void close() {
  // 当前工具集不持有长连接，暂不需要释放资源。
    }

    /**
* 把 state 里取出的任意值安全地转成字符串，null 依然返回 null。
     *
     * <p>state 是一个值类型完全不受控的 Map，直接强制转换会抛类型异常。
     * 保留 null 而不是转成 "null" 字面量，是为了让下面的默认值逻辑能正确识别「这个键根本没有」。</p>
   */
    private String stringValue(Object value) {
        // 保留 null 不转成字面量，这样两级回退才能识别出「state 里没有这个键」。
        return value == null ? null : String.valueOf(value);
    }

    /**
   * 取第一个有内容的字符串，用于「state 里没有就退回框架侧取值」这种两级回退。
     *
     * <p>空白串也算没有：state 里残留一个空字符串很常见，若把它当成有效身份传下去，
     * 会造成租户隔离失效或审计归属错乱，而这类问题在日志里很难看出来。</p>
     */
    private String defaultString(String value, String defaultValue) {
        // 有值就用原值，空引用和纯空白都退回框架侧取值。
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 把 state 里的值转成长整数，转不出来就返回 null。
     *
     * <p>专门用于上下文版本号。它可能是数字，也可能因为序列化环节变成了字符串，两种都要能读。</p>
     *
   * <p>转不出来时返回 null 而不是 0：0 是一个看起来合法的版本号，会让「上下文是否过期」的判断
     * 得出错误结论；返回 null 表示「不知道」，下游会跳过版本校验，这比误判安全。</p>
     */
    private Long longValue(Object value) {
        // 键不存在时直接返回空，表示这一轮没有版本信息。
        if (value == null) {
            // 键不存在时返回空，表示这一轮没有版本信息。
            return null;
        }
        // 已经是数字类型时直接取长整数值，避免多余的字符串转换。
        if (value instanceof Number number) {
            // 已经是数字时直接取值，省掉一次字符串转换。
            return number.longValue();
        }
    // 剩下的情况按字符串解析，解析失败要接住。
        try {
      // 尝试把文本解析成长整数。
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
       // 解析不了就返回空，表示版本未知；绝不用 0 兜底，那会让版本校验得出错误结论。
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean result ? result : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(item -> item != null).map(String::valueOf)
                .filter(item -> !item.isBlank()).toList();
    }

    private List<PlatformToolResolver.RouteDescriptor> routeDescriptors(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(PlatformToolResolver.RouteDescriptor.class::isInstance)
                .map(PlatformToolResolver.RouteDescriptor.class::cast).toList();
    }

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
