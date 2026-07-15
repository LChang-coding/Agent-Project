package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowMcpToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSkillToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;

import java.util.List;

/**
 * 工作流仓储接口。
 */
public interface IWorkflowRepository {

    /**
     * 新增工作流；参数是工作流实体；返回影响行数。
     */
    int insertWorkflow(WorkflowEntity workflow);

    /**
     * 更新工作流；参数是工作流实体；返回影响行数。
     */
    int updateWorkflow(WorkflowEntity workflow);

    /**
     * 查询工作流；参数是租户和工作流ID；返回工作流实体。
     */
    WorkflowEntity queryWorkflow(String tenantId, String workflowId);

    /**
     * 查询租户工作流列表；参数是租户ID；返回工作流实体列表。
     */
    List<WorkflowEntity> queryWorkflowList(String tenantId);

    /** 软删除工作流；参数是租户、工作流和删除人；返回影响行数。 */
    int softDeleteWorkflow(String tenantId, String workflowId, String deletedBy);

    /**
     * 新增版本；参数是版本实体；返回影响行数。
     */
    int insertVersion(WorkflowVersionEntity version);

    /**
     * 更新版本；参数是版本实体；返回影响行数。
     */
    int updateVersion(WorkflowVersionEntity version);

    /**
     * 查询指定版本；参数是租户、工作流ID和版本；返回版本实体。
     */
    WorkflowVersionEntity queryVersion(String tenantId, String workflowId, Integer version);

    /**
     * 查询最新草稿版本；参数是租户和工作流ID；返回版本实体。
     */
    WorkflowVersionEntity queryLatestDraft(String tenantId, String workflowId);

    /**
     * 查询最新发布版本；参数是租户和工作流ID；返回版本实体。
     */
    WorkflowVersionEntity queryLatestPublished(String tenantId, String workflowId);

    /**
     * 查询最大版本号；参数是租户和工作流ID；返回版本号。
     */
    Integer queryMaxVersion(String tenantId, String workflowId);

    /**
     * 查询可用 MCP 选项；参数是租户ID；返回选项列表。
     */
    List<WorkflowOptionEntity> queryMcpOptions(String tenantId);

    /**
     * 查询可用 Skill 选项；参数是租户ID；返回选项列表。
     */
    List<WorkflowOptionEntity> querySkillOptions(String tenantId);

    /**
     * 查询 MCP 工具配置；参数是租户ID和 MCP ID；返回工具配置列表。
     */
    List<WorkflowMcpToolEntity> queryMcpTools(String tenantId, List<String> mcpIds);

    /**
     * 查询 Skill 工具配置；参数是租户ID和 Skill ID；返回工具配置列表。
     */
    List<WorkflowSkillToolEntity> querySkillTools(String tenantId, List<String> skillIds);
}
