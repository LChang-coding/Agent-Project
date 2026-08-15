package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * ADK 运行时 state 中编排层与工具层共用的字段名。
 *
 * <p>{@code ChatService} 写入服务端确认的身份、运行、上下文和 RAG 设置；工具解析、上下文注入和执行校验
 * 组件读取这些值。该类只统一字段名，不保存或校验字段值。</p>
 */
public final class ToolRuntimeContextKeys {

    /** 可信租户编号；缺失时工具调用必须拒绝执行。 */
    public static final String TENANT_ID = "tenantId";
 /** 可信用户编号；决定私有工具可见范围和审计归属。 */
    public static final String USER_ID = "userId";
    /** 会话编号；用于按会话查询工具调用记录。 */
    public static final String SESSION_ID = "sessionId";
    /** 本轮执行目标编号；工作流使用工作流编号，普通运行兼容使用 Agent 编号。 */
    public static final String WORKFLOW_ID = "workflowId";
    /** 请求跟踪编号；用于关联工具审计和外部请求日志。 */
    public static final String TRACE_ID = "traceId";
    /** 权威运行编号；工具产生外部影响前按该编号锁定并校验运行状态。 */
    public static final String RUN_ID = "runId";
  /** 模型决定调用工具时读取的上下文版本；版本过期时拒绝执行。 */
    public static final String CONTEXT_REVISION = "contextRevision";
    /** 历史消息可见序号上限；同一轮重试使用相同的历史范围。 */
    public static final String CONTEXT_VISIBLE_THROUGH_SEQUENCE = "contextVisibleThroughSequence";
    /** 附件可见序号上限；允许本轮新上传附件进入当前模型上下文。 */
    public static final String CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE = "contextAttachmentVisibleThroughSequence";
    /** 当前工作流节点可读取的直接上游节点输出。 */
    public static final String CONTEXT_UPSTREAM_OUTPUT = "contextUpstreamOutput";
    /** RAG 绑定目标类型，值为 AGENT 或 WORKFLOW。 */
    public static final String RAG_TARGET_TYPE = "ragTargetType";
    /** RAG 绑定目标编号；与目标类型共同确定知识库绑定范围。 */
    public static final String RAG_TARGET_ID = "ragTargetId";
    /** 运行创建时保存的 RAG 选择模式。 */
    public static final String RAG_MODE = "ragMode";
    /** 运行创建时保存的知识库绑定编号列表。 */
    public static final String RAG_BINDING_IDS = "ragBindingIds";
  /** 本轮用于知识检索的查询文本。 */
    public static final String RAG_QUERY = "ragQuery";
    /** 检索证据归属的模型调用编号。 */
    public static final String RAG_EVIDENCE_INVOCATION_ID = "ragEvidenceInvocationId";
    /** 运行创建时保存的 RAG 调用方式。 */
    public static final String RAG_INVOCATION_MODE = "ragInvocationMode";
    /** 当前工作流节点是否允许模型调用 RAG 工具。 */
    public static final String RAG_TOOL_ENABLED = "ragToolEnabled";
    /** 当前工作流节点允许使用的 MCP 工具编号。 */
    public static final String WORKFLOW_MCP_IDS = "workflowMcpIds";
    /** 当前工作流节点允许使用的 Skill 编号。 */
    public static final String WORKFLOW_SKILL_IDS = "workflowSkillIds";
    /** 当前运行的工作流类型。 */
    public static final String WORKFLOW_KIND = "workflowKind";
    /** 当前运行保存的工作流路由协议版本。 */
    public static final String ROUTING_PROTOCOL_VERSION = "routingProtocolVersion";
    /** 当前工作流节点是否为终点。 */
    public static final String TERMINAL_NODE = "terminalNode";
    /** 当前节点允许模型选择的业务路由描述。 */
    public static final String ROUTE_DESCRIPTORS = "routeDescriptors";
    /** 本次逻辑节点执行标识。 */
    public static final String NODE_EXECUTION_ID = "nodeExecutionId";
    /** 产生当前调用的工作流节点标识。 */
    public static final String SOURCE_NODE_ID = "sourceNodeId";
    /** 当前运行保存的工作流定义摘要。 */
    public static final String DEFINITION_HASH = "definitionHash";
    /** 当前运行保存的工作流版本。 */
    public static final String WORKFLOW_VERSION = "workflowVersion";
    /** 是否处于只允许补充路由意图的修复调用。 */
    public static final String ROUTE_REPAIR_ONLY = "routeRepairOnly";
    /** 当前运行的公共 Agent 编号。 */
    public static final String AGENT_ID = "agentId";
    /** 服务端冻结的 Agent 编排角色。 */
    public static final String ORCHESTRATION_ROLE = "orchestrationRole";
    /** 主 Agent 可委派子 Agent 的服务端白名单。 */
    public static final String ALLOWED_SUB_AGENT_IDS = "allowedSubAgentIds";
    /** 一次多 Agent 编排最初的主运行编号；内部回调续跑时保持不变。 */
    public static final String ORCHESTRATION_ROOT_RUN_ID = "orchestrationRootRunId";
    /** 主 Agent 是否处于 WAIT_ALL 后的唯一汇总阶段。 */
    public static final String ORCHESTRATION_SUMMARY_ONLY = "orchestrationSummaryOnly";

    /** 私有构造：纯常量类，不允许实例化，避免被误注入或误当作上下文对象使用。 */
    private ToolRuntimeContextKeys() {
    }
}
