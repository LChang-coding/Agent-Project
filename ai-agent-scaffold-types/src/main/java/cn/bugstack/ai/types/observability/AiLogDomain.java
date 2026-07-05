package cn.bugstack.ai.types.observability;

public enum AiLogDomain {

    APP("app"),
    HTTP("http"),
    CHAT("chat"),
    MODEL("model"),
    DB("db"),
    AUTH("auth"),
    WORKFLOW("workflow"),
    REDIS("redis"),
    RAG("rag"),
    OSS("oss"),
    TOOL("tool"),
    SCHEDULER("scheduler");

    private final String code;

    /**
     * 创建日志领域；参数是领域编码；返回枚举实例。
     */
    AiLogDomain(String code) {
        this.code = code;
    }

    /**
     * 读取领域编码；无参数；返回领域编码。
     */
    public String code() {
        return code;
    }
}
