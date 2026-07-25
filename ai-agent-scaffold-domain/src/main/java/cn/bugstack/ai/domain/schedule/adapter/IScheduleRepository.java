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

    /** 查询需要重新收敛为运行态的配置。 */
    List<ScheduleConfigEntity> listForReconcile(int limit);

    /** 按全局配置标识读取配置，供后台调度链使用。 */
    ScheduleConfigEntity findConfig(String configId);

    /** 在租户和所有者边界内读取配置。 */
    ScheduleConfigEntity findOwnedConfig(String tenantId, String userId, String configId);

    /** 列出当前用户拥有的全部配置。 */
    List<ScheduleConfigEntity> listOwnedConfigs(String tenantId, String userId);

    /** 新增或更新一份调度配置。 */
    void saveConfig(ScheduleConfigEntity config);

    /** 仅允许所有者启停配置。 */
    boolean updateEnabled(String tenantId, String userId, String configId, boolean enabled);

    /** 以配置更新时间作并发条件，回写本次收敛结果。 */
    void updateReconciled(String configId, String configHash, long configVersion, LocalDateTime reconciledAt,
                          LocalDateTime expectedUpdateTime);

    /** 按业务键幂等新增或更新唯一运行态。 */
    ScheduleTaskEntity upsertTask(ScheduleTaskEntity task);

    /** 停用已无有效配置支撑的孤儿运行态。 */
    int disableInactiveTasks();

    /** 原子抢占一个到期任务并签发新的栅栏令牌。 */
    ScheduleTaskEntity claimDueTask(String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil);

    /** 按任务标识读取最新运行态。 */
    ScheduleTaskEntity findTask(String taskId);

    /** 仅允许当前租约和栅栏令牌续租。 */
    boolean renewLease(String taskId, String leaseOwner, long fencingToken, LocalDateTime leaseUntil);

    /** 成功消费当前触发点并推进下一次计划时间。 */
    boolean completeTask(String taskId, String leaseOwner, long fencingToken,
                         LocalDateTime lastPlannedTime, LocalDateTime nextFireTime);

    /** 保留当前触发点并登记下一次重试时间。 */
    boolean retryTask(String taskId, String leaseOwner, long fencingToken, int retryCount,
                      LocalDateTime retryAt);

    /** 终止当前触发点并释放租约，避免失效配置继续运行。 */
    boolean releaseFailedOccurrence(String taskId, String leaseOwner, long fencingToken,
                                    LocalDateTime lastPlannedTime, LocalDateTime nextFireTime);

    /** 将所有者的可用任务原子推进为立即到期。 */
    boolean triggerNow(String tenantId, String userId, String configId, LocalDateTime now);

    /** 按触发键幂等登记一次逻辑执行。 */
    ScheduleExecutionEntity beginExecution(ScheduleExecutionEntity execution);

    /** 用栅栏条件结束执行记录，拒绝过期 Worker 回写。 */
    boolean completeExecution(String executionId, String leaseOwner, long fencingToken, String status,
                              LocalDateTime endTime, long durationMs, String errorMessage, String resultJson);

    /** 在同一事务中提交成功执行记录并推进任务游标。 */
    void finishSuccess(String executionId, String taskId, String leaseOwner, long fencingToken,
                       LocalDateTime endTime, long durationMs, String resultJson,
                       LocalDateTime plannedTime, LocalDateTime nextFireTime);

    /** 在同一事务中登记失败，并选择重试当前触发点或推进到下一触发点。 */
    void finishFailure(String executionId, String taskId, String leaseOwner, long fencingToken,
                       LocalDateTime endTime, long durationMs, String errorMessage, boolean terminal,
                       int retryCount, LocalDateTime retryAt, LocalDateTime plannedTime,
                       LocalDateTime nextFireTime);

    /** 在所有权边界内倒序查询执行审计记录。 */
    List<ScheduleExecutionEntity> listExecutions(String tenantId, String userId, String configId, int limit);
}
