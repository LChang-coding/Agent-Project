package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowCreateCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDetailEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeOptionsEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSaveDraftCommandEntity;

import java.util.List;

/**
 * 工作流领域服务接口。
 */
public interface IWorkflowService {

    /**
     * 查询工作流列表。
     */
    List<WorkflowEntity> queryWorkflowList(String tenantId, String userId, String roleCode);

    /**
     * 创建工作流。
     */
    WorkflowEntity createWorkflow(WorkflowCreateCommandEntity command);

    /**
     * 查询工作流详情。
     */
    WorkflowDetailEntity queryWorkflowDetail(String tenantId, String userId, String roleCode, String workflowId);

    /**
     * 保存工作流草稿。
     */
    WorkflowDetailEntity saveDraft(WorkflowSaveDraftCommandEntity command);

    /**
     * 发布工作流。
     */
    WorkflowDetailEntity publishWorkflow(String tenantId, String userId, String roleCode, String workflowId);

    /** 软删除工作流。 */
    void deleteWorkflow(String tenantId, String userId, String roleCode, String workflowId);

    /**
     * 查询节点选项。
     */
    WorkflowNodeOptionsEntity queryNodeOptions(String tenantId);

    /**
     * 加载运行时。
     */
    WorkflowRuntimeEntity loadRuntime(String tenantId, String userId, String roleCode, String workflowId,
                                      Integer workflowVersion, String requestModelCode);
}
