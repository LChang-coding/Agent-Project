package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentWorkflowVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作流版本表 DAO。
 * <p>负责 `agent_workflow_version` 表的基础持久化操作。</p>
 */
@Mapper
public interface IAgentWorkflowVersionDao {

    /**
     * 新增工作流版本；参数是版本持久化对象；返回影响行数。
     */
    int insert(AgentWorkflowVersionPO version);

    /**
     * 按工作流和版本更新；参数是版本持久化对象；返回影响行数。
     */
    int updateByWorkflowVersion(AgentWorkflowVersionPO version);

    /**
     * 按工作流和版本查询；参数是租户、工作流ID和版本号；返回版本对象。
     */
    AgentWorkflowVersionPO queryByWorkflowVersion(@Param("tenantId") String tenantId,
                                                  @Param("workflowId") String workflowId,
                                                  @Param("version") Integer version);

    /**
     * 查询当前草稿版本；参数是租户和工作流ID；返回草稿版本。
     */
    AgentWorkflowVersionPO queryLatestDraft(@Param("tenantId") String tenantId, @Param("workflowId") String workflowId);

    /**
     * 查询当前发布版本；参数是租户和工作流ID；返回发布版本。
     */
    AgentWorkflowVersionPO queryLatestPublished(@Param("tenantId") String tenantId, @Param("workflowId") String workflowId);

    /**
     * 查询最大版本号；参数是租户和工作流ID；返回最大版本号。
     */
    Integer queryMaxVersion(@Param("tenantId") String tenantId, @Param("workflowId") String workflowId);
}
