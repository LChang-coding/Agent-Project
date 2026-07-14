package cn.bugstack.ai.domain.schedule.service;

/**
 * 可扩展的调度业务处理器。
 */
public interface ScheduleTaskHandler {

    String taskType();

    String execute(ScheduleTaskContext context) throws Exception;
}
