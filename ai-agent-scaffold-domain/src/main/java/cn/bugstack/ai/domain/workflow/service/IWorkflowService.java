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
     * 查询工作流列表；参数是租户ID；返回工作流列表。
     */
    List<WorkflowEntity> queryWorkflowList(String tenantId);

    /**
     * 创建工作流；参数是创建命令；返回工作流摘要。
     */
    WorkflowEntity createWorkflow(WorkflowCreateCommandEntity command);

    /**
     * 查询工作流详情；参数是租户和工作流ID；返回工作流详情。
     */
    WorkflowDetailEntity queryWorkflowDetail(String tenantId, String workflowId);

    /**
     * 保存工作流草稿；参数是保存命令；返回工作流详情。
     */
    WorkflowDetailEntity saveDraft(WorkflowSaveDraftCommandEntity command);

    /**
     * 发布工作流；参数是租户、用户和工作流ID；返回工作流详情。
     */
    WorkflowDetailEntity publishWorkflow(String tenantId, String userId, String roleCode, String workflowId);

    /** 软删除工作流；参数是可信租户、用户和工作流；无返回值。 */
    void deleteWorkflow(String tenantId, String userId, String roleCode, String workflowId);

    /**
     * 查询节点选项；参数是租户ID；返回节点、模型和工具选项。
     */
    WorkflowNodeOptionsEntity queryNodeOptions(String tenantId);

    /**
     * 加载运行时；参数是租户、用户、工作流、版本和请求模型；返回运行时信息。
     */
    WorkflowRuntimeEntity loadRuntime(String tenantId, String userId, String workflowId, Integer workflowVersion, String requestModelCode);
}
