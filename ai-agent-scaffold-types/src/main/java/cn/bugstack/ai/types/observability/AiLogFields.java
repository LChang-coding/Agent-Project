package cn.bugstack.ai.types.observability;

public final class AiLogFields {

    public static final String EVENT = "event";
    public static final String EVENT_NAME = "eventName";
    public static final String MESSAGE = "message";
    public static final String DOMAIN = "domain";
    public static final String LOG_ID = "logId";
    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String ROLE_CODE = "roleCode";
    public static final String SESSION_ID = "sessionId";
    public static final String RUN_ID = "runId";
    public static final String RETRIEVAL_ID = "retrievalId";
    public static final String TASK_ID = "taskId";
    public static final String STAGE = "stage";
    public static final String OPERATION = "operation";
    public static final String OUTCOME = "outcome";
    public static final String INPUT_COUNT = "inputCount";
    public static final String OUTPUT_COUNT = "outputCount";
    public static final String SKIP_REASON = "skipReason";
    public static final String MESSAGE_ID = "messageId";
    public static final String AGENT_ID = "agentId";
    public static final String AGENT_NAME = "agentName";
    public static final String APP_NAME = "appName";
    public static final String ROLE = "role";
    public static final String SEQUENCE_NO = "sequenceNo";
    public static final String CONTENT_LENGTH = "contentLength";
    public static final String INVOCATION_ID = "invocationId";
    public static final String TRACE_ID = "traceId";
    public static final String SUCCESS = "success";
    public static final String COST_MS = "costMs";
    public static final String METHOD = "method";
    public static final String URI = "uri";
    public static final String STATUS = "status";
    public static final String SAMPLE = "sample";
    public static final String ERROR_TYPE = "errorType";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_MESSAGE = "errorMessage";

    /**
     * 禁止创建常量类实例；无参数；无返回值。
     */
    private AiLogFields() {
    }
}
