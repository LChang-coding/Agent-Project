package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * ADK 运行时 state（一个 Map）里所有约定 key 的集合地，是编排层与工具层之间唯一的字段契约。
 *
 * <p>为什么需要它：模型运行时（ADK）只给工具一个 {@code Map<String, Object> state}，
 * 身份、会话、运行编号这些关键信息全靠字符串 key 传递。写字符串 key 一旦手抖打错，
 * 拿到的就是 null——具体表现是「工具突然说身份不完整」或「租户隔离失效」，而且编译期完全发现不了。
 * 所以生产方和消费方必须共用这里的常量。</p>
 *
 * <p>谁往里写：{@code ChatService} 在启动一次运行时把可信身份、上下文版本、RAG 快照塞进 state。</p>
 *
 * <p>谁从里读：{@code GatewayToolset} 读身份来决定加载哪些工具；{@code GatewayAdkTool} 读身份和运行信息组装调用上下文；
 * {@code ContextInjectionPlugin} 读上下文可见范围与 RAG 快照来拼提示词；{@code ToolExecutionGuardPlugin} 读上下文版本做执行门禁。</p>
 *
 * <p>它不负责什么：不存放任何值，也不校验值的合法性；state 里的值可信不可信由写入方（编排层）保证，
 * 工具层只做「缺了就拒绝执行」的兜底。</p>
 */
public final class ToolRuntimeContextKeys {

    /** 租户编号在 state 中的 key；这是跨租户隔离的根，取不到就必须拒绝调用，否则工具可能读写到别的公司的数据。 */
    public static final String TENANT_ID = "tenantId";
 /** 用户编号在 state 中的 key；决定这次调用能看到哪些私有工具，也是调用审计的责任人，绝不能用模型给的参数覆盖。 */
    public static final String USER_ID = "userId";
    /** 会话编号在 state 中的 key；工具调用日志按它归档，前端才能在对话里展示「这轮用了哪些工具」。 */
    public static final String SESSION_ID = "sessionId";
    /** 本轮执行目标编号在 state 中的 key；跑工作流时是工作流编号，跑普通 Agent 时兼容存 Agent 编号，用于审计区分来源。 */
    public static final String WORKFLOW_ID = "workflowId";
    /** 全链路追踪编号在 state 中的 key；一路带到工具调用日志和外部请求日志里，用户报障时凭它串起整条链路。 */
    public static final String TRACE_ID = "traceId";
    /** 运行编号在 state 中的 key；工具产生副作用前要凭它给运行加行锁并检查是否已被取消，缺失就退化成不可取消的裸调用。 */
    public static final String RUN_ID = "runId";
  /** 上下文版本号在 state 中的 key；记录模型「做出调用这个工具的决定时」看到的历史版本，用于拦住基于过期上下文的迟到调用。 */
    public static final String CONTEXT_REVISION = "contextRevision";
    /** 历史消息可见序号上限在 state 中的 key；拼提示词时只取序号不超过它的消息，保证同一轮推理反复重试看到的历史完全一致。 */
    public static final String CONTEXT_VISIBLE_THROUGH_SEQUENCE = "contextVisibleThroughSequence";
    /** 附件可见序号上限在 state 中的 key；比历史消息上限多放一格，因为本轮刚上传的附件必须被本轮看见。 */
    public static final String CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE = "contextAttachmentVisibleThroughSequence";
    /** 上游节点输出在 state 中的 key；工作流里当前节点要把直接前驱节点的产出接进提示词，靠它传递。 */
    public static final String CONTEXT_UPSTREAM_OUTPUT = "contextUpstreamOutput";
    /** RAG 绑定目标类型在 state 中的 key；值是 AGENT 或 WORKFLOW，同时也是「本轮是否启用了知识库」的开关标志。 */
    public static final String RAG_TARGET_TYPE = "ragTargetType";
    /** RAG 绑定目标编号在 state 中的 key；配合类型定位到底该检索哪个 Agent 或工作流绑定的知识库。 */
    public static final String RAG_TARGET_ID = "ragTargetId";
    /** 本轮冻结的 RAG 检索模式在 state 中的 key；运行开始时就固化，避免中途改配置导致同一轮前后检索策略不一致。 */
    public static final String RAG_MODE = "ragMode";
    /** 本轮冻结的知识库绑定编号列表在 state 中的 key；同样是运行开始时的快照，保证引用校验能对得上当时允许的范围。 */
    public static final String RAG_BINDING_IDS = "ragBindingIds";
  /** 本次检索问题在 state 中的 key；通常是用户这轮的原始提问，检索插件直接拿它去召回资料。 */
    public static final String RAG_QUERY = "ragQuery";
    /** 证据归属编号在 state 中的 key；工作流节点没有 ADK 的 invocationId，需要显式指定一个编号把检索证据挂到这一次模型调用上。 */
    public static final String RAG_EVIDENCE_INVOCATION_ID = "ragEvidenceInvocationId";
    public static final String RAG_INVOCATION_MODE = "ragInvocationMode";
    public static final String WORKFLOW_KIND = "workflowKind";
    public static final String ROUTING_PROTOCOL_VERSION = "routingProtocolVersion";
    public static final String TERMINAL_NODE = "terminalNode";
    public static final String ROUTE_DESCRIPTORS = "routeDescriptors";
    public static final String NODE_EXECUTION_ID = "nodeExecutionId";
    public static final String SOURCE_NODE_ID = "sourceNodeId";
    public static final String DEFINITION_HASH = "definitionHash";
    public static final String WORKFLOW_VERSION = "workflowVersion";
    public static final String ROUTE_REPAIR_ONLY = "routeRepairOnly";

    /** 私有构造：纯常量类，不允许实例化，避免被误注入或误当作上下文对象使用。 */
    private ToolRuntimeContextKeys() {
    }
}
