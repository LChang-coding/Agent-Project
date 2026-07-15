import { request } from '@/api/http';
import type {
  WorkflowCreateRequest,
  WorkflowDeleteResponse,
  WorkflowDetail,
  WorkflowNodeOptions,
  WorkflowSaveDraftRequest,
  WorkflowSummary,
} from '@/types/api';

/**
 * 查询工作流列表；无参数；返回当前租户可见工作流。
 */
export async function queryWorkflows() {
  return request<WorkflowSummary[]>({
    url: '/v1/workflows',
    method: 'GET',
  });
}

/**
 * 创建工作流；参数是名称、描述和默认模型；返回工作流摘要。
 */
export async function createWorkflow(payload: WorkflowCreateRequest) {
  return request<WorkflowSummary>({
    url: '/v1/workflows',
    method: 'POST',
    data: payload,
  });
}

/**
 * 查询工作流详情；参数是工作流 ID；返回版本和画布数据。
 */
export async function queryWorkflowDetail(workflowId: string) {
  return request<WorkflowDetail>({
    url: `/v1/workflows/${workflowId}`,
    method: 'GET',
  });
}

/**
 * 保存工作流草稿；参数是工作流 ID 和草稿内容；返回详情。
 */
export async function saveWorkflowDraft(workflowId: string, payload: WorkflowSaveDraftRequest) {
  return request<WorkflowDetail>({
    url: `/v1/workflows/${workflowId}/draft`,
    method: 'POST',
    data: payload,
  });
}

/**
 * 发布工作流；参数是工作流 ID；返回详情。
 */
export async function publishWorkflow(workflowId: string) {
  return request<WorkflowDetail>({
    url: `/v1/workflows/${workflowId}/publish`,
    method: 'POST',
  });
}

/**
 * 软删除工作流；参数是工作流 ID；返回可审计删除结果。
 */
export async function deleteWorkflow(workflowId: string) {
  return request<WorkflowDeleteResponse>({
    url: `/v1/workflows/${encodeURIComponent(workflowId)}`,
    method: 'DELETE',
  });
}

/**
 * 查询工作流节点选项；无参数；返回节点、模型、MCP 和 Skill 选项。
 */
export async function queryWorkflowNodeOptions() {
  return request<WorkflowNodeOptions>({
    url: '/v1/workflows/node-options',
    method: 'GET',
  });
}
