package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleExecutionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 定时任务执行记录 DAO。
 * <p>负责 `agent_schedule_execution` 表的基础持久化操作。</p>
 */
@Mapper
public interface IAgentScheduleExecutionDao {

    /**
     * 新增定时任务执行记录记录。
     *
     * @param agentScheduleExecution 定时任务执行记录持久化对象
     * @return 影响行数
     */
    int insert(AgentScheduleExecutionPO agentScheduleExecution);

    /**
     * 按主键更新定时任务执行记录记录。
     *
     * @param agentScheduleExecution 定时任务执行记录持久化对象
     * @return 影响行数
     */
    int updateById(AgentScheduleExecutionPO agentScheduleExecution);

    /**
     * 按主键查询定时任务执行记录记录。
     *
     * @param id 主键ID
     * @return 定时任务执行记录持久化对象
     */
    AgentScheduleExecutionPO queryById(@Param("id") Long id);

    /**
     * 按执行记录ID查询定时任务执行记录记录。
     *
     * @param executionId 执行记录ID
     * @return 定时任务执行记录持久化对象
     */
    AgentScheduleExecutionPO queryByExecutionId(@Param("executionId") String executionId);

    /**
     * 按租户业务ID查询定时任务执行记录列表。
     *
     * @param tenantId 租户业务ID
     * @return 定时任务执行记录持久化对象列表
     */
    List<AgentScheduleExecutionPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询定时任务执行记录列表。
     *
     * @param userId 用户业务ID
     * @return 定时任务执行记录持久化对象列表
     */
    List<AgentScheduleExecutionPO> queryListByUserId(@Param("userId") String userId);

    /**
     * 按调度任务实例ID查询定时任务执行记录列表。
     *
     * @param taskId 调度任务实例ID
     * @return 定时任务执行记录持久化对象列表
     */
    List<AgentScheduleExecutionPO> queryListByTaskId(@Param("taskId") String taskId);

    int insertIgnore(AgentScheduleExecutionPO execution);

    AgentScheduleExecutionPO queryByTriggerKey(@Param("triggerKey") String triggerKey);

    int markRunning(@Param("executionId") String executionId, @Param("leaseOwner") String leaseOwner,
                    @Param("fencingToken") long fencingToken, @Param("startTime") LocalDateTime startTime);

    int complete(@Param("executionId") String executionId, @Param("leaseOwner") String leaseOwner,
                 @Param("fencingToken") long fencingToken, @Param("status") String status,
                 @Param("endTime") LocalDateTime endTime, @Param("durationMs") long durationMs,
                 @Param("errorMessage") String errorMessage, @Param("resultJson") String resultJson);

    List<AgentScheduleExecutionPO> queryOwnedByConfig(@Param("tenantId") String tenantId,
                                                      @Param("userId") String userId,
                                                      @Param("configId") String configId,
                                                      @Param("limit") int limit);
}
