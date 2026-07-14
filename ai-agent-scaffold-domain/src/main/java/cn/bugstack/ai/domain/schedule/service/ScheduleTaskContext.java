package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;

/**
 * 任务处理器只接收已经持久化并经过抢占的可信上下文。
 */
public record ScheduleTaskContext(ScheduleConfigEntity config, ScheduleExecutionEntity execution) {
}
