package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.workflow.WorkflowCreateRequestDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowDetailResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowDeleteResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowNodeOptionsResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowSaveDraftRequestDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * 工作流服务接口。
 */
public interface IWorkflowApiService {

    /**
     * 查询工作流列表；无参数；返回当前租户可见工作流。
     */
    Response<List<WorkflowResponseDTO>> queryWorkflowList();

    /**
     * 创建工作流；参数是名称、描述和默认模型；返回工作流摘要。
     */
    Response<WorkflowResponseDTO> createWorkflow(WorkflowCreateRequestDTO requestDTO);

    /**
     * 查询工作流详情；参数是工作流ID；返回工作流版本和画布数据。
     */
    Response<WorkflowDetailResponseDTO> queryWorkflowDetail(String workflowId);

    /**
     * 保存工作流草稿；参数是工作流ID和画布数据；返回最新详情。
     */
    Response<WorkflowDetailResponseDTO> saveDraft(String workflowId, WorkflowSaveDraftRequestDTO requestDTO);

    /**
     * 发布工作流；参数是工作流ID；返回已发布详情。
     */
    Response<WorkflowDetailResponseDTO> publishWorkflow(String workflowId);

    /** 删除工作流；参数是工作流ID；返回幂等删除结果。 */
    Response<WorkflowDeleteResponseDTO> deleteWorkflow(String workflowId);

    /**
     * 查询节点选项；无参数；返回节点类型、模型、MCP 和 Skill 下拉数据。
     */
    Response<WorkflowNodeOptionsResponseDTO> queryNodeOptions();
}
