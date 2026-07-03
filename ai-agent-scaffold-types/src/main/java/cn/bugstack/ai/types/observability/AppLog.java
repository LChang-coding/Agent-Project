package cn.bugstack.ai.types.observability;

public final class AppLog {

    AppLog() {
    }

    public AiLogRecord start(String appName, String env, String version) {
        return AiLogRecord.event(AiLogEvent.APP_START)
                .field("appName", appName)
                .field("env", env)
                .field("version", version)
                .field(AiLogFields.SUCCESS, true);
    }
}
