package cn.bugstack.ai.types.observability;

public final class DbLog {

    DbLog() {
    }

    public AiLogRecord query(String database,
                             String operation,
                             String table,
                             Integer rows,
                             Long costMs,
                             Boolean success) {
        return AiLogRecord.event(AiLogEvent.DB_QUERY)
                .field("database", database)
                .field("operation", operation)
                .field("table", table)
                .field("rows", rows)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord error(String database,
                             String operation,
                             String table,
                             Long costMs,
                             Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.DB_ERROR)
                .field("database", database)
                .field("operation", operation)
                .field("table", table)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }
}
