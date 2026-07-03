package cn.bugstack.ai.types.observability;

public final class SchedulerLog {

    SchedulerLog() {
    }

    public AiLogRecord done(String jobName,
                            String triggerType,
                            String runId,
                            Long costMs,
                            Boolean success) {
        return AiLogRecord.event(AiLogEvent.SCHEDULER_DONE)
                .field("jobName", jobName)
                .field("triggerType", triggerType)
                .field("runId", runId)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord error(String jobName,
                             String triggerType,
                             String runId,
                             Long costMs,
                             Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.SCHEDULER_ERROR)
                .field("jobName", jobName)
                .field("triggerType", triggerType)
                .field("runId", runId)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }
}
