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
     * 新增工作流。
     */
    int insertWorkflow(WorkflowEntity workflow);

    /**
     * 更新工作流。
     */
    int updateWorkflow(WorkflowEntity workflow);

    /**
     * 查询工作流。
     */
    WorkflowEntity queryWorkflow(String tenantId, String workflowId);

    /**
     * 查询租户工作流列表。
     */
    List<WorkflowEntity> queryWorkflowList(String tenantId);

    /** 软删除工作流。 */
    int softDeleteWorkflow(String tenantId, String workflowId, String deletedBy);

    /**
     * 新增版本。
     */
    int insertVersion(WorkflowVersionEntity version);

    /**
     * 更新版本。
     */
    int updateVersion(WorkflowVersionEntity version);

    /**
     * 查询指定版本。
     */
    WorkflowVersionEntity queryVersion(String tenantId, String workflowId, Integer version);

    /**
     * 查询最新草稿版本。
     */
    WorkflowVersionEntity queryLatestDraft(String tenantId, String workflowId);

    /**
     * 查询最新发布版本。
     */
    WorkflowVersionEntity queryLatestPublished(String tenantId, String workflowId);

    /**
     * 查询最大版本号。
     */
    Integer queryMaxVersion(String tenantId, String workflowId);

    /**
     * 查询可用 MCP 选项。
     */
    List<WorkflowOptionEntity> queryMcpOptions(String tenantId);

    /**
     * 查询可用 Skill 选项。
     */
    List<WorkflowOptionEntity> querySkillOptions(String tenantId);

    /**
     * 查询 MCP 工具配置。
     */
    List<WorkflowMcpToolEntity> queryMcpTools(String tenantId, List<String> mcpIds);

    /**
     * 查询 Skill 工具配置。
     */
    List<WorkflowSkillToolEntity> querySkillTools(String tenantId, List<String> skillIds);
}
