package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 把一个已授权的工具「翻译」成大模型能理解的函数，并在模型决定调用时把请求交给执行网关。
 *
 * <p>所属层次：领域层组件，继承 ADK 的工具基类。它是「我们的工具」和「大模型的 function calling」之间的转换器。</p>
 *
 * <p>谁创建它：{@code GatewayToolset}，每轮为每个可用工具各创建一个实例。</p>
 *
 * <p>它向下调用什么：只调 {@code ToolGateway}。所有门禁、幂等、外部调用、审计都在网关里，
 * 这个类一行外部请求都不发。</p>
 *
 * <p>它最核心的职责是「声明」：构造时就把函数名、描述和入参 schema 冻结下来。
 * 这三样东西决定了模型能不能正确使用这个工具——名字不合规模型会调不到，
 * 描述不清楚模型会乱调或不调，schema 不明确模型会给出缺字段或错类型的参数。</p>
 *
 * <p>它的第二个核心职责是「不信任模型」：模型给的参数只被当作业务入参复制一份传下去，
 * 而身份（租户、用户、会话、运行）一律从 ADK 运行时和服务端回退值重建。
 * 模型可能被提示词注入操纵，如果身份也听它的，等于把越权的钥匙交给了模型。</p>
 *
 * <p>它不负责什么：不判断这个工具该不该出现（在解析阶段决定）、不执行任何外部动作、
 * 不做幂等和审计、不裁剪结果（在网关和协议客户端里做）。</p>
 */
public class GatewayAdkTool extends BaseTool {

 /**
   * 用来解析已发布的 MCP 工具清单快照，从中提取给模型看的可用远程工具摘要。
     *
     * <p>静态共享是安全的：它无状态，也不承载任何运行期数据。</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * 给模型看的工具描述长度上限。
   *
   * <p>为什么要限：每个工具的描述都会出现在每一次模型请求里。一个 MCP 可能有几十个远程工具，
     * 摘要拼起来轻易就上万字，几个工具一叠加就把上下文窗口占满，留给对话历史的空间就没了。</p>
     */
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;

    /**
     * 本轮解析出的工具目录项，构造时就固定不变。
     *
     * <p>为什么要固定：模型基于这一刻的描述和 schema 做决定，如果中途有人改了工具配置，
     * 模型的调用就会打到与它预期不同的地方。固定下来保证「模型看到的」和「实际执行的」是同一版。</p>
     */
    private final ToolCatalogEntity tool;
    /** 工具执行网关；本类唯一的向下依赖，所有真实执行、门禁和审计都由它完成。 */
    private final ToolGateway toolGateway;
    /**
     * 身份与运行信息的回退值，来自工具集装配时从可信 state 读出的内容。
  *
     * <p>用途：模型真正发起调用时，ADK 给的工具上下文可能缺字段（尤其是工作流场景），
 * 缺的部分用这份值补上，避免审计记录里出现大片空值而无法追溯。它全部来自服务端，与模型输出无关。</p>
     */
    private final ToolInvokeContextEntity fallbackContext;
    /**
     * 冻结好的模型函数声明：名字、描述、参数 schema 三件套。
     *
     * <p>构造时一次算好并缓存，因为每一轮模型请求都要用到它；同时也保证同一个实例在整轮里声明不变。</p>
     */
    private final FunctionDeclaration declaration;

    /**
     * 把一个工具目录项包装成 ADK 函数工具，并在此刻冻结它对模型的全部声明。
     *
     * <p>各层职责：
     * 第一层：先调父类构造，把「规范化后的函数名」和「拼装好的描述」交给 ADK，
     *  这两样是模型识别和选择工具的依据。
     * 第二层：保存工具目录项与执行网关。
     * 第三层：处理回退上下文。为空时至少造一个带链路标识的对象，保证审计里永远有 traceId 可查。
     * 第四层：构建并缓存函数声明（含入参 schema）。
     * 第五层：写入自定义元数据，让框架层的日志和追踪能看出这个函数背后是哪个工具的哪一版。</p>
     *
     * <p>数据流：工具目录项 → 规范化函数名 + 拼装描述 → 父类注册 → 保存依赖 → 补齐回退上下文
     * → 构建参数 schema → 组装函数声明 → 写入工具编号/类型/版本元数据。</p>
     *
     * <p>不写库、不发外部请求，纯粹的声明构建。</p>
     */
    public GatewayAdkTool(ToolCatalogEntity tool, ToolGateway toolGateway, ToolInvokeContextEntity fallbackContext) {
        // 冻结名称、描述、参数 schema 和审计元数据。
        super(toolName(tool), toolDescription(tool));
        // 记住目录项：后续执行时要用它的类型、版本和连接参数，且整轮不再变化。
        this.tool = tool;
    // 记住执行网关：模型点了这个函数之后，真正干活的是它。
        this.toolGateway = toolGateway;
    // 回退上下文为空时也要造一个带链路标识的对象，保证审计里至少有 traceId 能串起整条链路。
        this.fallbackContext = fallbackContext == null
                ? ToolInvokeContextEntity.builder().traceId(TraceContext.currentOrNewTraceId()).build()
                : fallbackContext;
        // 构建并缓存函数声明；每轮模型请求都要读它，提前算好避免重复构造。
        this.declaration = buildDeclaration(name(), description(), tool);
   // 写入工具编号，便于框架层日志把这个函数对应回具体工具。
        setCustomMetadata("toolId", tool.getToolId());
        // 写入工具类型，排查时能立刻分清是 Skill 还是 MCP。
        setCustomMetadata("toolType", tool.getToolType());
        // 写入版本号，保证事后能确认模型当时用的是哪一版行为。
        setCustomMetadata("version", tool.getVersion());
    }

    /**
 * 把冻结好的函数声明交给 ADK，让它拼进发给大模型的请求里。
     *
     * <p>这是模型「知道自己有这个能力」的唯一途径：声明里的名字、描述和参数 schema
     * 就是模型判断该不该调、怎么调的全部依据。</p>
     *
     * <p>直接返回构造时缓存的对象，不重新计算，保证整轮声明稳定一致。</p>
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
 // 返回构造期冻结的声明，避免每轮重新构造导致模型看到的 schema 前后不一致。
        return Optional.of(declaration);
    }

    /**
     * 模型决定调用这个工具时的入口：只搬运模型给的业务参数，身份一律重建，然后交给执行网关。
     *
     * <p>各层职责：
  * 第一层：复制模型给的参数。复制而不是直接用，是为了不让后续处理影响框架持有的原始对象；
     *      参数为空时给一个空表，因为「无参调用」是完全正常的情形。
     * 第二层：重建可信调用上下文。这一步刻意只看 ADK 运行时和服务端回退值，绝不看模型给的参数。
     * 第三层：校验身份。租户或用户缺失时直接返回一条拒绝说明给模型，而不是抛异常——
     *抛异常会中断整轮对话，而返回说明能让模型知道这条路走不通并换个方式回答用户。
* 第四层：把真正的执行包成惰性任务交给框架调度，执行体内部才去过门禁、调外部系统、写审计。</p>
     *
     * <p>数据流：
     * 模型给的参数 + ADK 工具上下文
     * → 复制业务参数
     * → 从 ADK state 与服务端回退值重建身份
     * → 身份校验（不通过则直接返回拒绝说明）
     * → 包成惰性任务
     * → 由框架订阅时执行网关调用（门禁 → 外部调用 → 审计）
* → 返回结构化结果给模型</p>
     *
     * <p>为什么用惰性任务：调用是同步阻塞的（要建连、等远程返回），包成 Single 后由框架决定在哪个线程执行，
     * 不会阻塞模型事件流的主线程。注意任务体在被订阅时才真正执行，方法返回并不代表工具已经跑完。</p>
     *
     * <p>返回结构固定含 success 标志，失败时带一句可读的 error 文案——这段文案会进入模型下一轮的提示词。</p>
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        // 仅复制模型参数并重建可信上下文；身份不完整时不进入 ToolGateway。
        Map<String, Object> input = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
   // 第二层：重建身份与运行坐标，全部取自 ADK 运行时和服务端回退值，与模型给的参数完全隔离。
        ToolInvokeContextEntity context = invokeContext(toolContext);
        if (blank(context.getFunctionCallId())) {
            String functionCallId = "server_call_" + UUID.randomUUID();
            context.setFunctionCallId(functionCallId);
            if (toolContext != null) toolContext.functionCallId(functionCallId);
        }
 // 第三层：身份不全就不执行。返回说明而不是抛异常，让模型能自行调整而不是整轮对话崩掉。
        if (blank(context.getTenantId()) || blank(context.getUserId())) {
        // 文案会进入模型的下一轮提示词，所以只说结论不带内部细节。
            return Single.just(Map.of("success", false, "error", "工具调用缺少可信身份上下文，已拒绝执行"));
        }
   // 第四层：包成惰性任务交给框架调度；被订阅时才真正过门禁并调用外部系统，避免阻塞事件流主线程。
        return Single.fromCallable(() -> toolGateway.invoke(tool, input, context));
    }

    /**
     * 重建这次调用的可信身份与运行坐标。
     *
     * <p>各层职责：
     * 第一层：连 ADK 工具上下文都没有时，复制一份回退值使用。
     * 第二层：有上下文时逐字段按优先级取值——先看编排层写入 state 的值，再看 ADK 框架自己的值，
     *     最后才用装配时的回退值。这个顺序体现了「谁的信息更贴近本次调用」。
     * 第三层：链路标识多兜一层，实在没有就现场生成一个，保证审计永远有可查的线索。</p>
     *
     * <p>数据流：ADK 工具上下文 → 取出 state → 逐字段三级回退取值
     * → 单独取模型函数调用编号（幂等键的关键输入）→ 组装成调用上下文返回。</p>
   *
     * <p>安全铁律：整个方法一次都没有读 args。模型给的参数只能当业务入参，绝不能参与身份构造——
     * 一旦采信，被提示词注入的模型就能把 tenantId 填成别人的租户，直接造成跨租户越权。</p>
     *
     * <p>为什么函数调用编号很重要：它和运行编号一起决定幂等键。缺了它，幂等键会退化成随机值，
     * 模型重试同一次调用就会被当成两件不同的事，可能造成重复下单、重复扣费。</p>
  */
    private ToolInvokeContextEntity invokeContext(ToolContext toolContext) {
        // ADK state 优先于 fallback；模型 args 从不参与身份构造。
        if (toolContext == null) {
      // 没有框架上下文时只能用装配期的回退值，但要复制一份以免污染共享对象。
            return copyFallbackContext();
        }
        // 取出 state；为空时用不可变空表，让下面每个取值都走到回退分支而不必判空。
        Map<String, Object> state = toolContext.state() == null ? Map.of() : toolContext.state();
      // 第二层：逐字段按「state → 框架值 → 回退值」的优先级重建，越靠前的来源越贴近本次调用。
        return ToolInvokeContextEntity.builder()
                .tenantId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID)), fallbackContext.getTenantId()))
                .userId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), toolContext.userId()), fallbackContext.getUserId()))
                .sessionId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID)), toolContext.sessionId()), fallbackContext.getSessionId()))
                .workflowId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_ID)), fallbackContext.getWorkflowId()))
                .invocationId(defaultString(toolContext.invocationId(), fallbackContext.getInvocationId()))
                .runId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RUN_ID)), fallbackContext.getRunId()))
                .contextRevision(defaultLong(longValue(state.get(ToolRuntimeContextKeys.CONTEXT_REVISION)), fallbackContext.getContextRevision()))
                .functionCallId(toolContext.functionCallId().orElse(null))
                .traceId(defaultString(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID)), fallbackContext.getTraceId()), TraceContext.currentOrNewTraceId()))
                .ragInvocationMode(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_INVOCATION_MODE)), fallbackContext.getRagInvocationMode()))
                .ragToolEnabled(defaultBoolean(booleanValue(state.get(ToolRuntimeContextKeys.RAG_TOOL_ENABLED)), fallbackContext.getRagToolEnabled()))
                .workflowMcpIds(defaultList(stringList(state.get(ToolRuntimeContextKeys.WORKFLOW_MCP_IDS)), fallbackContext.getWorkflowMcpIds()))
                .workflowSkillIds(defaultList(stringList(state.get(ToolRuntimeContextKeys.WORKFLOW_SKILL_IDS)), fallbackContext.getWorkflowSkillIds()))
                .ragMode(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_MODE)), fallbackContext.getRagMode()))
                .ragEvidenceInvocationId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID)), fallbackContext.getRagEvidenceInvocationId()))
                .ragTargetType(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_TARGET_TYPE)), fallbackContext.getRagTargetType()))
                .ragTargetId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_TARGET_ID)), fallbackContext.getRagTargetId()))
                .ragBindingIds(defaultList(stringList(state.get(ToolRuntimeContextKeys.RAG_BINDING_IDS)), fallbackContext.getRagBindingIds()))
                .workflowKind(defaultString(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_KIND)), fallbackContext.getWorkflowKind()))
                .routingProtocolVersion(defaultString(stringValue(state.get(ToolRuntimeContextKeys.ROUTING_PROTOCOL_VERSION)), fallbackContext.getRoutingProtocolVersion()))
                .terminalNode(defaultBoolean(booleanValue(state.get(ToolRuntimeContextKeys.TERMINAL_NODE)), fallbackContext.getTerminalNode()))
                .routeDescriptors(defaultList(routeDescriptors(state.get(ToolRuntimeContextKeys.ROUTE_DESCRIPTORS)), fallbackContext.getRouteDescriptors()))
                .nodeExecutionId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.NODE_EXECUTION_ID)), fallbackContext.getNodeExecutionId()))
                .sourceNodeId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.SOURCE_NODE_ID)), fallbackContext.getSourceNodeId()))
                .definitionHash(defaultString(stringValue(state.get(ToolRuntimeContextKeys.DEFINITION_HASH)), fallbackContext.getDefinitionHash()))
                .workflowVersion(defaultString(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_VERSION)), fallbackContext.getWorkflowVersion()))
                .routeRepairOnly(defaultBoolean(booleanValue(state.get(ToolRuntimeContextKeys.ROUTE_REPAIR_ONLY)), fallbackContext.getRouteRepairOnly()))
                .agentId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.AGENT_ID)), fallbackContext.getAgentId()))
                .orchestrationRole(defaultString(stringValue(state.get(ToolRuntimeContextKeys.ORCHESTRATION_ROLE)), fallbackContext.getOrchestrationRole()))
                .allowedSubAgentIds(defaultList(stringList(state.get(ToolRuntimeContextKeys.ALLOWED_SUB_AGENT_IDS)), fallbackContext.getAllowedSubAgentIds()))
                .orchestrationRootRunId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.ORCHESTRATION_ROOT_RUN_ID)), fallbackContext.getOrchestrationRootRunId()))
                .build();
    }

    /**
   * 复制一份回退上下文，用于没有 ADK 工具上下文的场景。
   *
   * <p>为什么必须复制而不是直接返回那个对象：回退上下文是工具集装配时创建的，
     * 本轮所有工具包装器共享同一个实例。直接交出去，任何下游对它的修改都会影响其他工具的调用身份，
     * 那种串扰问题极难定位。</p>
   *
     * <p>复制时顺带给链路标识兜底，保证审计里永远有可查线索。</p>
     */
    private ToolInvokeContextEntity copyFallbackContext() {
 // 无 ADK ToolContext 时复制隔离对象，避免调用方修改共享回退实例。
        return ToolInvokeContextEntity.builder()
                .tenantId(fallbackContext.getTenantId())
                .userId(fallbackContext.getUserId())
                .sessionId(fallbackContext.getSessionId())
                .workflowId(fallbackContext.getWorkflowId())
                .invocationId(fallbackContext.getInvocationId())
                .runId(fallbackContext.getRunId())
                .contextRevision(fallbackContext.getContextRevision())
                .functionCallId(fallbackContext.getFunctionCallId())
                .traceId(defaultString(fallbackContext.getTraceId(), TraceContext.currentOrNewTraceId()))
                .ragInvocationMode(fallbackContext.getRagInvocationMode())
                .ragToolEnabled(fallbackContext.getRagToolEnabled())
                .workflowMcpIds(copyList(fallbackContext.getWorkflowMcpIds()))
                .workflowSkillIds(copyList(fallbackContext.getWorkflowSkillIds()))
                .ragMode(fallbackContext.getRagMode())
                .ragEvidenceInvocationId(fallbackContext.getRagEvidenceInvocationId())
                .ragTargetType(fallbackContext.getRagTargetType())
                .ragTargetId(fallbackContext.getRagTargetId())
                .ragBindingIds(copyList(fallbackContext.getRagBindingIds()))
                .workflowKind(fallbackContext.getWorkflowKind())
                .routingProtocolVersion(fallbackContext.getRoutingProtocolVersion())
                .terminalNode(fallbackContext.getTerminalNode())
                .routeDescriptors(copyList(fallbackContext.getRouteDescriptors()))
                .nodeExecutionId(fallbackContext.getNodeExecutionId())
                .sourceNodeId(fallbackContext.getSourceNodeId())
                .definitionHash(fallbackContext.getDefinitionHash())
                .workflowVersion(fallbackContext.getWorkflowVersion())
                .routeRepairOnly(fallbackContext.getRouteRepairOnly())
                .agentId(fallbackContext.getAgentId())
                .orchestrationRole(fallbackContext.getOrchestrationRole())
                .allowedSubAgentIds(copyList(fallbackContext.getAllowedSubAgentIds()))
                .orchestrationRootRunId(fallbackContext.getOrchestrationRootRunId())
                .build();
    }

    /**
   * 把函数名、描述和参数 schema 组装成一份完整的模型函数声明。
     *
   * <p>这三样合起来就是模型对这个工具的全部认知；组装出来后会被缓存，整轮不再变化。</p>
     */
    private static FunctionDeclaration buildDeclaration(String name, String description, ToolCatalogEntity tool) {
    // 名字用于模型发起调用时的标识，描述用于模型判断该不该调，参数 schema 用于约束模型怎么给参数。
        return FunctionDeclaration.builder()
                .name(name)
                .description(description)
                .parameters(parameterSchema(tool))
                .build();
    }

    /**
     * 声明这个工具的入参格式，也就是告诉大模型「调我要给什么参数」。
     *
     * <p>Skill 只要一个可选的任务描述：因为调用 Skill 的效果只是把说明书读出来，
     * 有没有任务描述都能正常工作，设成必填反而会让模型在只想「先看看」时无从下手。</p>
     *
     * <p>MCP 则把远程工具名和 JSON 参数都设为必填，而且描述里给了具体示例。原因是模型经常
     * 漏给工具名或者把参数平铺在顶层，导致调用直接失败。声明成必填并给示例，能显著提高模型一次给对的概率。</p>
     *
     * <p>为什么参数用「JSON 文本」而不是嵌套对象：不同 MCP 的远程工具入参结构千差万别，
     * 没法用一个固定 schema 描述完。让模型给一段 JSON 文本，再由网关解析，是通用性和可用性的折中。
  * 代价是模型可能给出不合法的 JSON，所以网关侧必须做解析校验。</p>
 *
     * <p>关键认识：schema 只是「请求」而不是「保证」。模型完全可能不遵守——给不合法的 JSON、
     * 编一个不存在的工具名，甚至在提示词注入下给出危险参数。所以真正的校验必须在执行侧做，
     * 这里的声明只负责把期望说清楚。</p>
     */
    private static Schema parameterSchema(ToolCatalogEntity tool) {
        if (ToolType.PLATFORM.equals(tool.getToolType())) {
            return platformSchema(tool.getSchemaJson());
        }
        // Skill 只收任务文本；MCP 强制要求远程工具名和 JSON 参数。
        Map<String, Schema> properties = new LinkedHashMap<>();
     // Skill 分支：只声明一个可选的任务描述字段。
        if (ToolType.SKILL.equals(tool.getToolType())) {
   // 明确写「可以为空」，让模型在只想读说明书时也敢调用。
            properties.put("task", Schema.builder()
                    .type(Type.Known.STRING)
                    .description("本次希望 Skill 帮助完成的任务，可以为空。")
                    .build());
     // Skill 没有必填字段，所以不声明 required。
            return Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(properties)
                    .build();
        }
    // MCP 分支：参数用 JSON 文本承载，描述里给出具体示例，显著降低模型给错格式的概率。
        properties.put("argumentsJson", Schema.builder()
                .type(Type.Known.STRING)
                .description("传给远程 MCP 具体工具的 JSON 参数文本，例如 {\"origin\":\"北京\",\"destination\":\"南昌\"}。")
                .build());
        // 远程工具名必须来自已发布清单，描述里明确这一点，防止模型凭想象编一个名字。
        properties.put("toolName", Schema.builder()
                .type(Type.Known.STRING)
                .description("远程 MCP 的具体工具名，必须来自当前 MCP 工具清单。")
                .build());
      // 两个字段都声明为必填：模型漏给任何一个，调用都会在执行侧失败，提前声明能减少这类无效往返。
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required(List.of("toolName", "argumentsJson"))
                .build();
    }

    /**
     * 生成一个既符合模型函数命名规范、又能稳定对应到这个工具的函数名。
     *
     * <p>数据流：按类型加 mcp_ 或 skill_ 前缀 → 取工具编码（没有则用编号）→ 把非法字符统一替换成下划线
     * → 首字符不是字母或下划线时再补一个 tool_ 前缀 → 超长则截断到 64 字符。</p>
     *
     * <p>为什么要这么折腾：模型函数名只允许字母、数字和下划线，且有长度上限。名字不合规的直接后果是
     * 模型根本发不出这次调用，而且报错信息往往指向框架层，非常难定位到「原来是工具名带了个减号」。</p>
     *
* <p>加类型前缀还有一个好处：模型在函数名上就能看出这是本地技能还是远程工具，选择时更准。</p>
     *
   * <p>注意截断带来的隐患：两个编码很长且前 64 个字符相同的工具会得到同名函数，模型将无法区分它们。
     * 实践中编码远短于这个长度，但值得知道这个边界。</p>
   */
    private static String toolName(ToolCatalogEntity tool) {
        if (ToolType.PLATFORM.equals(tool.getToolType())) {
            String name = tool.getFunctionName();
            if (name == null || !name.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$")) {
                throw new IllegalArgumentException("平台工具函数名不合法");
            }
            return name;
        }
       // 规范为 ADK 合法且不超过 64 字符的稳定函数名。
        String prefix = ToolType.MCP.equals(tool.getToolType()) ? "mcp_" : "skill_";
     // 优先用可读的工具编码，没有编码才退回用编号，让模型看到的名字尽量有意义。
        String raw = defaultString(tool.getToolCode(), tool.getToolId());
        // 把所有不被允许的字符统一替换成下划线，避免模型因为名字里的连字符等字符而调不出来。
        String value = (prefix + raw).replaceAll("[^a-zA-Z0-9_]", "_");
        // 函数名不能以数字开头，遇到这种情况再补一个前缀把首字符纠正过来。
        if (!value.matches("^[a-zA-Z_].*")) {
            // 补一个前缀把首字符纠正成字母，否则模型侧会因为名字以数字开头而拒绝这个函数。
            value = "tool_" + value;
        }
  // 最后卡长度上限，超长直接截断；名字过长同样会被模型侧拒绝。
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /**
     * 拼出给大模型看的工具描述，这段文字直接决定模型会不会正确选用这个工具。
     *
     * <p>数据流：类型名 + 工具名称 + 用途说明 → 若是 MCP 再追加调用要求和可用远程工具摘要
     * → 超长则截断 → 返回。</p>
     *
     * <p>为什么要把远程工具清单写进描述：模型必须知道有哪些远程工具名可选，否则它只能靠猜，
     * 猜错就是一次无效调用。把清单摊开给它看，能明显减少「工具名不存在」这类失败。</p>
     *
     * <p>为什么必须截断：这段描述会出现在每一次模型请求里。一个 MCP 有几十个远程工具时摘要能上万字，
     * 不限长就会把上下文窗口挤满，对话历史被顶掉，模型开始「失忆」。截断会让尾部的工具从摘要里消失，
     * 那些工具仍然可以调用，只是模型不一定知道它们存在——这是有意接受的取舍。</p>
     *
     * <p>注意：这里只放名称、说明和远程工具清单，绝不放连接地址、命令或环境变量。
 * 那些内容含有外部系统凭证，一旦进入描述就等于交给了模型，模型可能在回答里把它复述给用户。</p>
     */
    private static String toolDescription(ToolCatalogEntity tool) {
        if (ToolType.PLATFORM.equals(tool.getToolType())) {
            return defaultString(tool.getDescription(), "平台内置工具。");
        }
  // 描述中附带已发布 MCP 工具清单，但限制总长度。
        String typeName = ToolType.MCP.equals(tool.getToolType()) ? "MCP" : "Skill";
    // 先拼「类型 + 名称 + 用途」；用途缺失时给一句兜底说明，避免描述里出现空白让模型无法判断。
        String description = typeName + "：" + defaultString(tool.getToolName(), tool.getToolId()) + "。"
                + defaultString(tool.getDescription(), "当前用户有权限调用的工具。");
        // MCP 还要额外告诉模型两件事：必须给哪两个参数，以及远程到底有哪些工具可选。
        if (ToolType.MCP.equals(tool.getToolType())) {
            // 追加必填参数要求和远程工具清单，模型据此知道要给哪两个字段、工具名有哪些可选。
            description += " 调用时必须提供 toolName 和 argumentsJson。可用远程工具：" + mcpSchemaSummary(tool.getSchemaJson());
        }
        // 卡总长度：描述每轮都会发给模型，不限长会把对话历史挤出上下文窗口。
        return description.length() > MAX_DESCRIPTION_LENGTH ? description.substring(0, MAX_DESCRIPTION_LENGTH) : description;
    }

    @SuppressWarnings("unchecked")
    /** 将平台工具声明的受控 JSON Schema 转换为 ADK Schema，非法结构直接拒绝装配。 */
    private static Schema platformSchema(String schemaJson) {
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(schemaJson, new TypeReference<>() {});
            if (!"object".equals(root.get("type")) || !(root.get("properties") instanceof Map<?, ?> properties)) {
                throw new IllegalArgumentException("平台工具参数 Schema 不合法");
            }
            return convertPlatformSchema(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("平台工具参数 Schema 不合法", exception);
        }
    }

    /**
     * 递归转换平台 Schema；编排工具的 tasks/taskIds 会用到数组和嵌套对象。
     * JSON Schema 中本类未显式支持的关键字不会放宽服务端校验；真正执行时仍由 handler 做严格白名单检查。
     */
    private static Schema convertPlatformSchema(Map<?, ?> definition) {
        String type = String.valueOf(definition.get("type"));
        Type.Known known = switch (type) {
            case "object" -> Type.Known.OBJECT;
            case "array" -> Type.Known.ARRAY;
            case "string" -> Type.Known.STRING;
            case "integer" -> Type.Known.INTEGER;
            case "number" -> Type.Known.NUMBER;
            case "boolean" -> Type.Known.BOOLEAN;
            default -> throw new IllegalArgumentException("平台工具参数类型不支持: " + type);
        };
        Schema.Builder builder = Schema.builder().type(known);
        if (definition.get("description") != null) {
            builder.description(String.valueOf(definition.get("description")));
        }
        if (definition.get("enum") instanceof List<?> values) {
            builder.enum_(values.stream().map(String::valueOf).toList());
        }
        if (definition.get("minLength") != null) builder.minLength(longBoundary(definition.get("minLength"), "minLength"));
        if (definition.get("maxLength") != null) builder.maxLength(longBoundary(definition.get("maxLength"), "maxLength"));
        if (definition.get("minimum") != null) builder.minimum(doubleBoundary(definition.get("minimum"), "minimum"));
        if (definition.get("maximum") != null) builder.maximum(doubleBoundary(definition.get("maximum"), "maximum"));
        if (definition.get("minItems") != null) builder.minItems(longBoundary(definition.get("minItems"), "minItems"));
        if (definition.get("maxItems") != null) builder.maxItems(longBoundary(definition.get("maxItems"), "maxItems"));

        if (known == Type.Known.OBJECT) {
            if (!(definition.get("properties") instanceof Map<?, ?> properties)) {
                throw new IllegalArgumentException("平台工具对象参数缺少 properties");
            }
            Map<String, Schema> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> child)) {
                    throw new IllegalArgumentException("平台工具参数定义不合法");
                }
                converted.put(String.valueOf(entry.getKey()), convertPlatformSchema(child));
            }
            builder.properties(converted);
            List<String> required = definition.get("required") instanceof List<?> values
                    ? values.stream().map(String::valueOf).toList() : List.of();
            builder.required(required);
        } else if (known == Type.Known.ARRAY) {
            if (!(definition.get("items") instanceof Map<?, ?> items)) {
                throw new IllegalArgumentException("平台工具数组参数缺少 items");
            }
            builder.items(convertPlatformSchema(items));
        }
        return builder.build();
    }

    /** 读取 Schema 整数边界，拒绝非数字或超出 long 范围的配置。 */
    private static long longBoundary(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("平台工具参数 " + name + " 必须是数字");
        }
        return number.longValue();
    }

    /** 读取 Schema 小数边界，拒绝非数字和非有限值。 */
    private static double doubleBoundary(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("平台工具参数 " + name + " 必须是数字");
        }
        return number.doubleValue();
    }

    /**
     * 把已发布的 MCP 工具清单快照压成一句「名称：说明」的摘要，供拼进工具描述。
     *
     * <p>数据流：清单 JSON → 判空（给出「请先测试」的提示）→ 解析出 tools 列表
 * → 只保留对象条目 → 每项拼成「名称：说明」→ 用分号连接返回。</p>
     *
     * <p>三种异常情况都返回一句人话而不是空串或异常：没测过就提示去测试、解析失败就提示重新测试、
     * 结构不认识就说暂无清单。原因是这段文字会被模型读到，一句明确的提示能引导模型
     * 告诉用户「这个工具还没配好」，而空串只会让模型茫然地去猜工具名。</p>
     *
  * <p>纯解析，不发网络请求、不抛异常。</p>
     */
    @SuppressWarnings("unchecked")
    private static String mcpSchemaSummary(String schemaJson) {
        // 没有清单说明这个 MCP 还没成功测试过，直接给一句可操作的提示。
        if (schemaJson == null || schemaJson.isBlank()) {
            // 给一句可操作的提示，引导用户先去测试，而不是返回空串让模型茫然猜工具名。
            return "未测试，暂无远程工具清单。请先在 MCP 中心点击测试。";
        }
        // 快照可能因版本变化解析不了，需要接住。
        try {
    // 整体解析成 Map，再取出工具清单字段。
            Map<String, Object> schema = OBJECT_MAPPER.readValue(schemaJson, new TypeReference<>() {
            });
     // 取出 tools 字段，它应该是一个条目列表。
            Object tools = schema.get("tools");
    // 确认是列表才继续，避免对意外结构做强制转换。
            if (tools instanceof List<?> list) {
       // 逐项拼成「名称：说明」，让模型既知道能调什么，也知道每个工具是干什么的。
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .map(tool -> {
    // 名称是模型发起调用时必须原样给出的值。
                            String name = String.valueOf(tool.get("name"));
      // 说明帮助模型在多个远程工具之间做选择。
                            Object description = tool.get("description");
   // 没有说明时只给名称，避免拼出「名称：null」这种误导性文本。
                            return description == null ? name : name + "：" + description;
                        })
                        .collect(Collectors.joining("；"));
            }
        } catch (Exception ignored) {
    // 解析失败时给一句可操作的提示，而不是抛异常让整轮工具装配失败。
            return "Schema 解析失败，请重新测试 MCP。";
        }
        // tools 字段缺失或结构不认识时，同样给一句人话。
        return "暂无远程工具清单。";
    }

    /**
     * 把任意值安全地转成字符串，null 依然返回 null。
 *
     * <p>用于读取 ADK state：那里的值类型完全不受控。保留 null 而不是转成 "null" 字面量，
   * 是为了让多级回退逻辑能正确识别出「这个键根本不存在」。</p>
     */
    private static String stringValue(Object value) {
        // 保留 null 不转成字面量，这样多级回退才能识别出「这个键根本不存在」。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取第一个有内容的字符串，是本类里实现「多级回退」的基础工具。
   *
     * <p>空白串也算没有：state 里残留空字符串很常见，若被当成有效租户或用户传下去，
     * 会导致租户隔离失效或审计归属错乱，而这类问题在日志里几乎看不出来。</p>
     */
    private static String defaultString(String value, String defaultValue) {
        // 有值就用原值，空引用和纯空白都退到下一级来源。
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 把 state 里的值转成长整数，转不出来就返回 null。
   *
     * <p>专用于上下文版本号。它可能本来是数字，也可能因为序列化环节变成字符串，两种都要能读。</p>
  *
     * <p>转不出来时返回 null 而不是 0：0 是个看起来合法的版本号，会让「上下文是否过期」的校验
     * 得出错误结论并放过一次本该被拦下的迟到调用；null 表示「未知」，下游会跳过校验，更安全。</p>
     */
    private static Long longValue(Object value) {
   // 键不存在时返回空，表示没有版本信息。
        if (value == null) {
            // 键不存在时返回空，表示这一轮没有版本信息。
            return null;
        }
        // 已经是数字时直接取长整数值，省掉字符串转换。
        if (value instanceof Number number) {
            // 已经是数字时直接取值，省掉一次字符串转换。
            return number.longValue();
        }
        // 其余情况按字符串解析，失败要接住。
        try {
     // 尝试解析成长整数。
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
      // 解析失败返回空表示版本未知，绝不用 0 兜底以免误判上下文仍然新鲜。
            return null;
        }
    }

    /**
     * 取第一个非空的长整数，用于上下文版本号的两级回退。
     *
     * <p>这里只判 null 不判「是否为 0」：0 是一个合法的版本号（运行刚开始时就是 0），
     * 把它当缺失处理会错误地退回旧值，反而让版本校验失效。</p>
     */
    private static Long defaultLong(Long value, Long defaultValue) {
        // 只判空引用：0 是合法版本号，不能当成缺失去退回旧值。
        return value == null ? defaultValue : value;
    }

    /** 从运行时属性恢复可选布尔值。 */
    private static Boolean booleanValue(Object value) {
        return value instanceof Boolean result ? result : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    /** 从运行时属性提取非空字符串列表，类型不匹配时返回空。 */
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return null;
        return values.stream().filter(item -> item != null).map(String::valueOf)
                .filter(item -> !item.isBlank()).toList();
    }

    /** 从运行时属性提取经过服务端构造的路由描述符，丢弃其他对象。 */
    private static List<PlatformToolResolver.RouteDescriptor> routeDescriptors(Object value) {
        if (!(value instanceof List<?> values)) return null;
        return values.stream().filter(PlatformToolResolver.RouteDescriptor.class::isInstance)
                .map(PlatformToolResolver.RouteDescriptor.class::cast).toList();
    }

    /** 仅在值缺失时使用冻结运行上下文中的默认布尔值。 */
    private static Boolean defaultBoolean(Boolean value, Boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** 仅在列表缺失时使用冻结默认值，并返回不可变副本。 */
    private static <T> List<T> defaultList(List<T> value, List<T> defaultValue) {
        return copyList(value == null ? defaultValue : value);
    }

    /** 对可选列表建立不可变副本，避免工具执行期间被调用方修改。 */
    private static <T> List<T> copyList(List<T> value) {
        return value == null ? null : List.copyOf(value);
    }

    /**
     * 判断一个身份字段是否等于没有值（空引用或全是空白字符）。
     *
     * <p>用于执行前的身份校验。连空白串也算空很关键：空串若被当成有效租户传进网关，
   * 租户隔离就形同虚设，而这种问题在日志里看起来一切正常。</p>
  */
    private static boolean blank(String value) {
        // 空引用和纯空白都算缺失，防止空串被当成有效身份放过。
        return value == null || value.isBlank();
    }
}
