package cn.bugstack.ai.domain.schedule.service;

/**
 * 可扩展的调度业务处理器。
 */
public interface ScheduleTaskHandler {

    /** 返回该处理器支持的持久化任务类型。 */
    String taskType();

    /** 执行已抢占的可信任务上下文，并返回可持久化结果。 */
    String execute(ScheduleTaskContext context) throws Exception;
}
