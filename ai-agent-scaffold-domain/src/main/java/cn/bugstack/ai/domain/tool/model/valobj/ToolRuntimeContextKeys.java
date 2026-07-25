package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具运行时上下文键。
 */
public final class ToolRuntimeContextKeys {

    /** 可信租户身份。 */
    public static final String TENANT_ID = "tenantId";
    /** 可信用户身份。 */
    public static final String USER_ID = "userId";
    /** 业务会话。 */
    public static final String SESSION_ID = "sessionId";
    /** 当前 Agent 或工作流目标。 */
    public static final String WORKFLOW_ID = "workflowId";
    /** 全链路追踪 ID。 */
    public static final String TRACE_ID = "traceId";
    /** 权威运行 ID。 */
    public static final String RUN_ID = "runId";
    /** 本轮冻结的上下文版本。 */
    public static final String CONTEXT_REVISION = "contextRevision";
    /** 历史消息最大可见序号。 */
    public static final String CONTEXT_VISIBLE_THROUGH_SEQUENCE = "contextVisibleThroughSequence";
    /** 附件绑定最大可见序号。 */
    public static final String CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE = "contextAttachmentVisibleThroughSequence";
    /** DAG 直接上游输出。 */
    public static final String CONTEXT_UPSTREAM_OUTPUT = "contextUpstreamOutput";
    /** AGENT 或 WORKFLOW 绑定类型。 */
    public static final String RAG_TARGET_TYPE = "ragTargetType";
    /** RAG 绑定目标 ID。 */
    public static final String RAG_TARGET_ID = "ragTargetId";
    /** 本轮冻结的 RAG 模式。 */
    public static final String RAG_MODE = "ragMode";
    /** 本轮冻结的绑定 ID 列表。 */
    public static final String RAG_BINDING_IDS = "ragBindingIds";
    /** 本次检索问题。 */
    public static final String RAG_QUERY = "ragQuery";
    /** 将工作流节点证据绑定到单次模型调用。 */
    public static final String RAG_EVIDENCE_INVOCATION_ID = "ragEvidenceInvocationId";

    /** 禁止实例化常量类。 */
    private ToolRuntimeContextKeys() {
    }
}
