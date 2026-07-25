package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 定时任务实例 DAO。
 * <p>负责 `agent_schedule_task` 表的基础持久化操作。</p>
 */
@Mapper
public interface IAgentScheduleTaskDao {

    /**
     * 新增定时任务实例记录。
     *
     * @param agentScheduleTask 定时任务实例持久化对象
     * @return 影响行数
     */
    int insert(AgentScheduleTaskPO agentScheduleTask);

    /**
     * 按主键更新定时任务实例记录。
     *
     * @param agentScheduleTask 定时任务实例持久化对象
     * @return 影响行数
     */
    int updateById(AgentScheduleTaskPO agentScheduleTask);

    /**
     * 按主键查询定时任务实例记录。
     *
     * @param id 主键ID
     * @return 定时任务实例持久化对象
     */
    AgentScheduleTaskPO queryById(@Param("id") Long id);

    /**
     * 按调度任务实例ID查询定时任务实例记录。
     *
     * @param taskId 调度任务实例ID
     * @return 定时任务实例持久化对象
     */
    AgentScheduleTaskPO queryByTaskId(@Param("taskId") String taskId);

    /**
     * 按租户业务ID查询定时任务实例列表。
     *
     * @param tenantId 租户业务ID
     * @return 定时任务实例持久化对象列表
     */
    List<AgentScheduleTaskPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询定时任务实例列表。
     *
     * @param userId 用户业务ID
     * @return 定时任务实例持久化对象列表
     */
    List<AgentScheduleTaskPO> queryListByUserId(@Param("userId") String userId);

    /**
     * 按调度配置业务ID查询定时任务实例列表。
     *
     * @param configId 调度配置业务ID
     * @return 定时任务实例持久化对象列表
     */
    List<AgentScheduleTaskPO> queryListByConfigId(@Param("configId") String configId);

    /**
     * 按调度任务实例ID查询定时任务实例列表。
     *
     * @param taskId 调度任务实例ID
     * @return 定时任务实例持久化对象列表
     */
    List<AgentScheduleTaskPO> queryListByTaskId(@Param("taskId") String taskId);

    /** 按配置 ID 冲突更新唯一运行时任务，避免 Cron 变更重复入库。 */
    int upsertRuntime(AgentScheduleTaskPO task);

    /** 查询配置对应的唯一调度任务。 */
    AgentScheduleTaskPO queryByConfigId(@Param("configId") String configId);

    /** 禁用来源配置已失效的任务。 */
    int disableInactive();

    /** 原子领取一条到期任务、设置租约并递增 fencing token。 */
    int claimDue(@Param("leaseOwner") String leaseOwner, @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 按本轮唯一租约所有者读取刚领取的任务。 */
    AgentScheduleTaskPO queryByLeaseOwner(@Param("leaseOwner") String leaseOwner);

    /** 当前持有者携带匹配围栏令牌才可续租。 */
    int renewLease(@Param("taskId") String taskId, @Param("leaseOwner") String leaseOwner,
                   @Param("fencingToken") long fencingToken, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 成功后推进上次计划时刻与下一次触发时刻并释放租约。 */
    int complete(@Param("taskId") String taskId, @Param("leaseOwner") String leaseOwner,
                 @Param("fencingToken") long fencingToken, @Param("lastPlannedTime") LocalDateTime lastPlannedTime,
                 @Param("nextFireTime") LocalDateTime nextFireTime);

    /** 失败可重试时推进重试次数和重试时刻并释放租约。 */
    int retry(@Param("taskId") String taskId, @Param("leaseOwner") String leaseOwner,
              @Param("fencingToken") long fencingToken, @Param("retryCount") int retryCount,
              @Param("retryAt") LocalDateTime retryAt);

    /** 本次发生永久失败后仍推进 Cron 游标，防止坏时刻无限重放。 */
    int releaseFailedOccurrence(@Param("taskId") String taskId, @Param("leaseOwner") String leaseOwner,
                                @Param("fencingToken") long fencingToken,
                                @Param("lastPlannedTime") LocalDateTime lastPlannedTime,
                                @Param("nextFireTime") LocalDateTime nextFireTime);

    /** 所有者手动把指定任务的下一触发时刻推进到现在。 */
    int triggerNow(@Param("tenantId") String tenantId, @Param("userId") String userId,
                   @Param("configId") String configId, @Param("now") LocalDateTime now);
}
