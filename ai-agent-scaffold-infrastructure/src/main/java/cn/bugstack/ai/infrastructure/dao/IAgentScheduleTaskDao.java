package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
}
