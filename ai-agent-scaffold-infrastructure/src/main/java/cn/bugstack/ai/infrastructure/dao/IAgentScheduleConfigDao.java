package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentScheduleConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定时任务配置 DAO。
 * <p>负责 `agent_schedule_config` 表的基础持久化操作。</p>
 */
@Mapper
public interface IAgentScheduleConfigDao {

    /**
     * 新增定时任务配置记录。
     *
     * @param agentScheduleConfig 定时任务配置持久化对象
     * @return 影响行数
     */
    int insert(AgentScheduleConfigPO agentScheduleConfig);

    /**
     * 按主键更新定时任务配置记录。
     *
     * @param agentScheduleConfig 定时任务配置持久化对象
     * @return 影响行数
     */
    int updateById(AgentScheduleConfigPO agentScheduleConfig);

    /**
     * 按主键查询定时任务配置记录。
     *
     * @param id 主键ID
     * @return 定时任务配置持久化对象
     */
    AgentScheduleConfigPO queryById(@Param("id") Long id);

    /**
     * 按调度配置业务ID查询定时任务配置记录。
     *
     * @param configId 调度配置业务ID
     * @return 定时任务配置持久化对象
     */
    AgentScheduleConfigPO queryByConfigId(@Param("configId") String configId);

    /**
     * 按租户业务ID查询定时任务配置列表。
     *
     * @param tenantId 租户业务ID
     * @return 定时任务配置持久化对象列表
     */
    List<AgentScheduleConfigPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询定时任务配置列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return 定时任务配置持久化对象列表
     */
    List<AgentScheduleConfigPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按执行身份用户ID查询定时任务配置列表。
     *
     * @param runAsUserId 执行身份用户ID
     * @return 定时任务配置持久化对象列表
     */
    List<AgentScheduleConfigPO> queryListByRunAsUserId(@Param("runAsUserId") String runAsUserId);

    /**
     * 按调度配置业务ID查询定时任务配置列表。
     *
     * @param configId 调度配置业务ID
     * @return 定时任务配置持久化对象列表
     */
    List<AgentScheduleConfigPO> queryListByConfigId(@Param("configId") String configId);

    /**
     * 按租户和可见范围查询定时任务配置列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return 定时任务配置持久化对象列表
     */
    List<AgentScheduleConfigPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
