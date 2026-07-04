package cn.bugstack.ai.types.observability;

public enum AiLogDomain {

    APP("app"),
    HTTP("http"),
    MODEL("model"),
    DB("db"),
    AUTH("auth"),
    REDIS("redis"),
    RAG("rag"),
    OSS("oss"),
    SCHEDULER("scheduler");

    private final String code;

    AiLogDomain(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
