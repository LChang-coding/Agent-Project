package cn.bugstack.ai.types.observability;

public final class RedisLog {

    RedisLog() {
    }

    public AiLogRecord command(String command,
                               String key,
                               Boolean hit,
                               Long costMs,
                               Boolean success) {
        return AiLogRecord.event(AiLogEvent.REDIS_COMMAND)
                .field("command", command)
                .field("key", key)
                .field("hit", hit)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord error(String command, String key, Long costMs, Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.REDIS_ERROR)
                .field("command", command)
                .field("key", key)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }
}
