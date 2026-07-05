package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentWorkflowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流主表 DAO。
 * <p>负责 `agent_workflow` 表的基础持久化操作。</p>
 */
@Mapper
public interface IAgentWorkflowDao {

    /**
     * 新增工作流；参数是工作流持久化对象；返回影响行数。
     */
    int insert(AgentWorkflowPO agentWorkflow);

    /**
     * 按工作流ID更新工作流；参数是工作流持久化对象；返回影响行数。
     */
    int updateByWorkflowId(AgentWorkflowPO agentWorkflow);

    /**
     * 按工作流ID查询工作流；参数是租户和工作流ID；返回工作流持久化对象。
     */
    AgentWorkflowPO queryByWorkflowId(@Param("tenantId") String tenantId, @Param("workflowId") String workflowId);

    /**
     * 按租户查询工作流列表；参数是租户ID；返回工作流列表。
     */
    List<AgentWorkflowPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者查询工作流列表；参数是用户ID；返回工作流列表。
     */
    List<AgentWorkflowPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);
}
