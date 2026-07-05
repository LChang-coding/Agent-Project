package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具运行时上下文键。
 */
public final class ToolRuntimeContextKeys {

    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String WORKFLOW_ID = "workflowId";
    public static final String TRACE_ID = "traceId";

    /**
     * 禁止创建常量类；无参数；无返回值。
     */
    private ToolRuntimeContextKeys() {
    }
}
