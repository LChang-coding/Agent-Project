package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;
import cn.bugstack.ai.infrastructure.dao.IAgentScheduleConfigDao;
import cn.bugstack.ai.infrastructure.dao.IAgentScheduleExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IAgentScheduleTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleConfigPO;
import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleExecutionPO;
import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleTaskPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于 MySQL 唯一键、租约和栅栏令牌的调度仓储。
 */
@Repository
@RequiredArgsConstructor
public class ScheduleRepository implements IScheduleRepository {

    /** 用户配置持久化入口。 */
    private final IAgentScheduleConfigDao configDao;
    /** 可领取任务持久化入口。 */
    private final IAgentScheduleTaskDao taskDao;
    /** 单次计划发生账本入口。 */
    private final IAgentScheduleExecutionDao executionDao;

    @Override
    public List<ScheduleConfigEntity> listForReconcile(int limit) {
        return configDao.queryForReconcile(limit).stream().map(this::toConfig).toList();
    }

    @Override
    public ScheduleConfigEntity findConfig(String configId) {
        return toConfig(configDao.queryByConfigId(configId));
    }

    @Override
    public ScheduleConfigEntity findOwnedConfig(String tenantId, String userId, String configId) {
        return toConfig(configDao.queryOwned(tenantId, userId, configId));
    }

    @Override
    public List<ScheduleConfigEntity> listOwnedConfigs(String tenantId, String userId) {
        return configDao.queryOwnedList(tenantId, userId).stream().map(this::toConfig).toList();
    }

    @Override
    public void saveConfig(ScheduleConfigEntity config) {
        AgentScheduleConfigPO po = toConfigPo(config);
        if (configDao.queryOwned(config.getTenantId(), config.getOwnerUserId(), config.getConfigId()) == null) {
            configDao.insert(po);
        } else if (configDao.updateOwned(po) != 1) {
            throw new IllegalStateException("调度配置已被并发修改");
        }
    }

    @Override
    public boolean updateEnabled(String tenantId, String userId, String configId, boolean enabled) {
        return configDao.updateEnabled(tenantId, userId, configId, enabled ? 1 : 0,
                enabled ? "active" : "disabled") == 1;
    }

    @Override
    public void updateReconciled(String configId, String configHash, long configVersion,
                                 LocalDateTime reconciledAt, LocalDateTime expectedUpdateTime) {
        configDao.updateReconciled(configId, configHash, configVersion, reconciledAt, expectedUpdateTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 冲突更新唯一任务并在同一事务中回读完整运行时。 */
    public ScheduleTaskEntity upsertTask(ScheduleTaskEntity task) {
        taskDao.upsertRuntime(toTaskPo(task));
        return toTask(taskDao.queryByConfigId(task.getConfigId()));
    }

    @Override
    public int disableInactiveTasks() {
        return taskDao.disableInactive();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 领取成功后按本轮唯一 leaseOwner 回查，避免返回扫描旧快照。 */
    public ScheduleTaskEntity claimDueTask(String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil) {
        if (taskDao.claimDue(leaseOwner, now, leaseUntil) != 1) {
            return null;
        }
        return toTask(taskDao.queryByLeaseOwner(leaseOwner));
    }

    @Override
    public ScheduleTaskEntity findTask(String taskId) {
        return toTask(taskDao.queryByTaskId(taskId));
    }

    @Override
    public boolean renewLease(String taskId, String leaseOwner, long fencingToken, LocalDateTime leaseUntil) {
        return taskDao.renewLease(taskId, leaseOwner, fencingToken, leaseUntil) == 1;
    }

    @Override
    public boolean completeTask(String taskId, String leaseOwner, long fencingToken,
                                LocalDateTime lastPlannedTime, LocalDateTime nextFireTime) {
        return taskDao.complete(taskId, leaseOwner, fencingToken, lastPlannedTime, nextFireTime) == 1;
    }

    @Override
    public boolean retryTask(String taskId, String leaseOwner, long fencingToken, int retryCount,
                             LocalDateTime retryAt) {
        return taskDao.retry(taskId, leaseOwner, fencingToken, retryCount, retryAt) == 1;
    }

    @Override
    public boolean releaseFailedOccurrence(String taskId, String leaseOwner, long fencingToken,
                                           LocalDateTime lastPlannedTime, LocalDateTime nextFireTime) {
        return taskDao.releaseFailedOccurrence(taskId, leaseOwner, fencingToken,
                lastPlannedTime, nextFireTime) == 1;
    }

    @Override
    public boolean triggerNow(String tenantId, String userId, String configId, LocalDateTime now) {
        return taskDao.triggerNow(tenantId, userId, configId, now) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 按 triggerKey 幂等建账；已完成发生直接重放，未完成发生必须重新获得执行权。 */
    public ScheduleExecutionEntity beginExecution(ScheduleExecutionEntity execution) {
        AgentScheduleExecutionPO po = toExecutionPo(execution);
        if (executionDao.insertIgnore(po) == 0) {
            AgentScheduleExecutionPO existing = executionDao.queryByTriggerKey(execution.getTriggerKey());
            if (existing == null) {
                return null;
            }
            if ("success".equals(existing.getStatus()) || "dead".equals(existing.getStatus())) {
                return toExecution(existing);
            }
            if (executionDao.markRunning(existing.getExecutionId(), execution.getLeaseOwner(),
                    execution.getFencingToken(), execution.getStartTime()) != 1) return null;
        }
        return toExecution(executionDao.queryByTriggerKey(execution.getTriggerKey()));
    }

    @Override
    public boolean completeExecution(String executionId, String leaseOwner, long fencingToken, String status,
                                     LocalDateTime endTime, long durationMs, String errorMessage,
                                     String resultJson) {
        return executionDao.complete(executionId, leaseOwner, fencingToken, status, endTime, durationMs,
                errorMessage, resultJson) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 执行终态与下一 Cron 游标必须同时提交，否则整体回滚。 */
    public void finishSuccess(String executionId, String taskId, String leaseOwner, long fencingToken,
                              LocalDateTime endTime, long durationMs, String resultJson,
                              LocalDateTime plannedTime, LocalDateTime nextFireTime) {
        if (executionDao.complete(executionId, leaseOwner, fencingToken, "success", endTime, durationMs,
                null, resultJson) != 1 || taskDao.complete(taskId, leaseOwner, fencingToken,
                plannedTime, nextFireTime) != 1) {
            throw new IllegalStateException("调度成功结果因租约失效未能提交");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 失败账本与任务重试/游标推进必须同时提交。 */
    public void finishFailure(String executionId, String taskId, String leaseOwner, long fencingToken,
                              LocalDateTime endTime, long durationMs, String errorMessage, boolean terminal,
                              int retryCount, LocalDateTime retryAt, LocalDateTime plannedTime,
                              LocalDateTime nextFireTime) {
        String status = terminal ? "dead" : "failed";
        if (executionDao.complete(executionId, leaseOwner, fencingToken, status, endTime, durationMs,
                errorMessage, null) != 1) {
            throw new IllegalStateException("调度失败结果因租约失效未能提交");
        }
        int changed = terminal
                ? taskDao.releaseFailedOccurrence(taskId, leaseOwner, fencingToken, plannedTime, nextFireTime)
                : taskDao.retry(taskId, leaseOwner, fencingToken, retryCount, retryAt);
        if (changed != 1) throw new IllegalStateException("调度任务因租约失效未能推进");
    }

    @Override
    public List<ScheduleExecutionEntity> listExecutions(String tenantId, String userId, String configId,
                                                        int limit) {
        return executionDao.queryOwnedByConfig(tenantId, userId, configId, limit).stream()
                .map(this::toExecution).toList();
    }

    /** 将数据库空值归一为领域默认值。 */
    private ScheduleConfigEntity toConfig(AgentScheduleConfigPO po) {
        if (po == null) return null;
        return ScheduleConfigEntity.builder().tenantId(po.getTenantId()).ownerUserId(po.getOwnerUserId())
                .runAsUserId(po.getRunAsUserId()).runAsRoleCode(po.getRunAsRoleCode())
                .configId(po.getConfigId()).agentId(po.getAgentId()).agentName(po.getAgentName())
                .taskType(po.getTaskType()).taskPayload(po.getTaskPayload()).cronExpr(po.getCronExpr())
                .timezone(po.getTimezone()).enabled(Integer.valueOf(1).equals(po.getEnabled()))
                .status(po.getStatus()).misfirePolicy(po.getMisfirePolicy())
                .maxRetries(po.getMaxRetries() == null ? 0 : po.getMaxRetries())
                .configHash(po.getConfigHash()).configVersion(po.getConfigVersion() == null ? 0 : po.getConfigVersion())
                .lastReconciledAt(po.getLastReconciledAt()).createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime()).build();
    }

    private AgentScheduleConfigPO toConfigPo(ScheduleConfigEntity entity) {
        return AgentScheduleConfigPO.builder().tenantId(entity.getTenantId()).ownerUserId(entity.getOwnerUserId())
                .runAsUserId(entity.getRunAsUserId()).runAsRoleCode(entity.getRunAsRoleCode())
                .visibility("private").configId(entity.getConfigId()).agentId(entity.getAgentId())
                .agentName(entity.getAgentName()).taskType(entity.getTaskType()).taskPayload(entity.getTaskPayload())
                .cronExpr(entity.getCronExpr()).timezone(entity.getTimezone()).enabled(entity.isEnabled() ? 1 : 0)
                .status(entity.getStatus()).misfirePolicy(entity.getMisfirePolicy()).maxRetries(entity.getMaxRetries())
                .configHash(entity.getConfigHash()).configVersion(entity.getConfigVersion())
                .lastReconciledAt(entity.getLastReconciledAt()).build();
    }

    /** 恢复任务租约、围栏和 Cron 游标。 */
    private ScheduleTaskEntity toTask(AgentScheduleTaskPO po) {
        if (po == null) return null;
        return ScheduleTaskEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId())
                .configId(po.getConfigId()).taskId(po.getTaskId()).businessKey(po.getBusinessKey())
                .configHash(po.getConfigHash()).configVersion(value(po.getConfigVersion()))
                .cronExpr(po.getCronExpr()).timezone(po.getTimezone()).misfirePolicy(po.getMisfirePolicy())
                .maxRetries(intValue(po.getMaxRetries())).plannedTime(po.getPlannedTime())
                .nextFireTime(po.getNextFireTime()).lastPlannedTime(po.getLastPlannedTime())
                .retryAt(po.getRetryAt()).status(po.getStatus()).retryCount(intValue(po.getRetryCount()))
                .leaseOwner(po.getLeaseOwner()).leaseUntil(po.getLeaseUntil())
                .fencingToken(value(po.getFencingToken())).rowVersion(value(po.getRowVersion())).build();
    }

    private AgentScheduleTaskPO toTaskPo(ScheduleTaskEntity entity) {
        return AgentScheduleTaskPO.builder().tenantId(entity.getTenantId()).userId(entity.getUserId())
                .configId(entity.getConfigId()).taskId(entity.getTaskId()).businessKey(entity.getBusinessKey())
                .configHash(entity.getConfigHash()).configVersion(entity.getConfigVersion())
                .cronExpr(entity.getCronExpr()).timezone(entity.getTimezone()).misfirePolicy(entity.getMisfirePolicy())
                .maxRetries(entity.getMaxRetries()).plannedTime(entity.getPlannedTime())
                .nextFireTime(entity.getNextFireTime()).status(entity.getStatus()).retryCount(entity.getRetryCount())
                .fencingToken(entity.getFencingToken()).rowVersion(entity.getRowVersion()).build();
    }

    /** 恢复单次计划发生的幂等执行账本。 */
    private ScheduleExecutionEntity toExecution(AgentScheduleExecutionPO po) {
        if (po == null) return null;
        return ScheduleExecutionEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId())
                .configId(po.getConfigId()).taskId(po.getTaskId()).executionId(po.getExecutionId())
                .triggerKey(po.getTriggerKey()).traceId(po.getTraceId()).plannedTime(po.getPlannedTime())
                .attemptNo(intValue(po.getAttemptNo())).fencingToken(value(po.getFencingToken()))
                .leaseOwner(po.getLeaseOwner()).startTime(po.getStartTime()).endTime(po.getEndTime())
                .durationMs(po.getDurationMs()).status(po.getStatus()).errorMessage(po.getErrorMessage())
                .resultJson(po.getResultJson()).build();
    }

    private AgentScheduleExecutionPO toExecutionPo(ScheduleExecutionEntity entity) {
        return AgentScheduleExecutionPO.builder().tenantId(entity.getTenantId()).userId(entity.getUserId())
                .configId(entity.getConfigId()).taskId(entity.getTaskId()).executionId(entity.getExecutionId())
                .triggerKey(entity.getTriggerKey()).traceId(entity.getTraceId()).plannedTime(entity.getPlannedTime())
                .attemptNo(entity.getAttemptNo()).fencingToken(entity.getFencingToken())
                .leaseOwner(entity.getLeaseOwner()).startTime(entity.getStartTime()).status(entity.getStatus()).build();
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
