package cn.bugstack.ai.domain.schedule.adapter;

import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分布式定时任务仓储契约。
 */
public interface IScheduleRepository {

    List<ScheduleConfigEntity> listForReconcile(int limit);

    ScheduleConfigEntity findConfig(String configId);

    ScheduleConfigEntity findOwnedConfig(String tenantId, String userId, String configId);

    List<ScheduleConfigEntity> listOwnedConfigs(String tenantId, String userId);

    void saveConfig(ScheduleConfigEntity config);

    boolean updateEnabled(String tenantId, String userId, String configId, boolean enabled);

    void updateReconciled(String configId, String configHash, long configVersion, LocalDateTime reconciledAt);

    ScheduleTaskEntity upsertTask(ScheduleTaskEntity task);

    int disableInactiveTasks();

    ScheduleTaskEntity claimDueTask(String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil);

    ScheduleTaskEntity findTask(String taskId);

    boolean renewLease(String taskId, String leaseOwner, long fencingToken, LocalDateTime leaseUntil);

    boolean completeTask(String taskId, String leaseOwner, long fencingToken,
                         LocalDateTime lastPlannedTime, LocalDateTime nextFireTime);

    boolean retryTask(String taskId, String leaseOwner, long fencingToken, int retryCount,
                      LocalDateTime retryAt);

    boolean releaseFailedOccurrence(String taskId, String leaseOwner, long fencingToken,
                                    LocalDateTime lastPlannedTime, LocalDateTime nextFireTime);

    boolean triggerNow(String tenantId, String userId, String configId, LocalDateTime now);

    ScheduleExecutionEntity beginExecution(ScheduleExecutionEntity execution);

    boolean completeExecution(String executionId, String leaseOwner, long fencingToken, String status,
                              LocalDateTime endTime, long durationMs, String errorMessage, String resultJson);

    void finishSuccess(String executionId, String taskId, String leaseOwner, long fencingToken,
                       LocalDateTime endTime, long durationMs, String resultJson,
                       LocalDateTime plannedTime, LocalDateTime nextFireTime);

    void finishFailure(String executionId, String taskId, String leaseOwner, long fencingToken,
                       LocalDateTime endTime, long durationMs, String errorMessage, boolean terminal,
                       int retryCount, LocalDateTime retryAt, LocalDateTime plannedTime,
                       LocalDateTime nextFireTime);

    List<ScheduleExecutionEntity> listExecutions(String tenantId, String userId, String configId, int limit);
}
