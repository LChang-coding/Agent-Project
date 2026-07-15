import { defineStore } from 'pinia';

import {
  createWorkflow,
  deleteWorkflow,
  publishWorkflow,
  queryWorkflowDetail,
  queryWorkflowNodeOptions,
  queryWorkflows,
  saveWorkflowDraft,
} from '@/api/workflow';
import type {
  WorkflowCreateRequest,
  WorkflowDetail,
  WorkflowGraph,
  WorkflowNode,
  WorkflowNodeOptions,
  WorkflowSaveDraftRequest,
  WorkflowSummary,
} from '@/types/api';

interface WorkflowState {
  workflows: WorkflowSummary[];
  activeWorkflowId: string;
  detail: WorkflowDetail | null;
  options: WorkflowNodeOptions;
  loading: boolean;
  saving: boolean;
  publishing: boolean;
  deletingWorkflowId: string;
  errorMessage: string;
}

export const useWorkflowStore = defineStore('workflow', {
  state: (): WorkflowState => ({
    workflows: [],
    activeWorkflowId: '',
    detail: null,
    options: {
      nodeTypes: [],
      models: [],
      mcpServers: [],
      skills: [],
    },
    loading: false,
    saving: false,
    publishing: false,
    deletingWorkflowId: '',
    errorMessage: '',
  }),
  getters: {
    activeWorkflow: (state) => state.workflows.find((item) => item.workflowId === state.activeWorkflowId),
    publishedWorkflows: (state) => state.workflows.filter((item) => item.publishedVersion > 0 && item.status === 'published'),
  },
  actions: {
    /**
     * 加载工作流列表；无参数；返回当前租户工作流。
     */
    async loadWorkflows() {
      this.loading = true;
      this.errorMessage = '';
      try {
        this.workflows = await queryWorkflows();
        if (!this.workflows.some((workflow) => workflow.workflowId === this.activeWorkflowId)) {
          this.activeWorkflowId = this.workflows[0]?.workflowId || '';
          if (!this.activeWorkflowId) {
            this.detail = null;
          }
        }
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流列表加载失败';
      } finally {
        this.loading = false;
      }
    },

    /**
     * 加载节点选项；无参数；返回模型、MCP 和 Skill 下拉数据。
     */
    async loadOptions() {
      this.options = await queryWorkflowNodeOptions();
    },

    /**
     * 创建工作流；参数是创建请求；返回新工作流详情。
     */
    async create(payload: WorkflowCreateRequest) {
      this.loading = true;
      this.errorMessage = '';
      try {
        const workflow = await createWorkflow(payload);
        this.activeWorkflowId = workflow.workflowId;
        await this.loadWorkflows();
        this.activeWorkflowId = workflow.workflowId;
        await this.loadDetail(workflow.workflowId);
        return this.detail;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流创建失败';
        return null;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 加载工作流详情；参数是工作流ID；返回详情。
     */
    async loadDetail(workflowId?: string) {
      const id = workflowId || this.activeWorkflowId;
      if (!id) {
        return null;
      }
      this.loading = true;
      this.errorMessage = '';
      try {
        this.detail = await queryWorkflowDetail(id);
        this.activeWorkflowId = id;
        return this.detail;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流详情加载失败';
        return null;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 保存草稿；参数是保存请求；返回保存后的详情。
     */
    async saveDraft(payload: WorkflowSaveDraftRequest) {
      if (!this.activeWorkflowId) {
        return null;
      }
      this.saving = true;
      this.errorMessage = '';
      try {
        this.detail = await saveWorkflowDraft(this.activeWorkflowId, payload);
        await this.loadWorkflows();
        this.activeWorkflowId = this.detail.workflow.workflowId;
        return this.detail;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流草稿保存失败';
        return null;
      } finally {
        this.saving = false;
      }
    },

    /**
     * 发布当前工作流；无参数；返回发布后的详情。
     */
    async publish() {
      if (!this.activeWorkflowId) {
        return null;
      }
      this.publishing = true;
      this.errorMessage = '';
      try {
        this.detail = await publishWorkflow(this.activeWorkflowId);
        await this.loadWorkflows();
        this.activeWorkflowId = this.detail.workflow.workflowId;
        return this.detail;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流发布失败';
        return null;
      } finally {
        this.publishing = false;
      }
    },

    /**
     * 软删除工作流；参数是工作流ID；请求成功后才收口本地列表。
     */
    async remove(workflowId?: string) {
      const id = workflowId || this.activeWorkflowId;
      if (!id || this.deletingWorkflowId || this.saving || this.publishing) {
        return false;
      }
      this.deletingWorkflowId = id;
      this.errorMessage = '';
      try {
        await deleteWorkflow(id);
        const removedIndex = this.workflows.findIndex((workflow) => workflow.workflowId === id);
        this.workflows = this.workflows.filter((workflow) => workflow.workflowId !== id);
        if (this.activeWorkflowId === id) {
          const nextIndex = Math.min(Math.max(removedIndex, 0), Math.max(0, this.workflows.length - 1));
          this.activeWorkflowId = this.workflows[nextIndex]?.workflowId || '';
          this.detail = null;
          if (this.activeWorkflowId) {
            await this.loadDetail(this.activeWorkflowId);
          }
        }
        return true;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工作流删除失败';
        return false;
      } finally {
        this.deletingWorkflowId = '';
      }
    },
  },
});

/**
 * 创建前端默认画布；参数是模型编码；返回单节点工作流。
 */
export function createDefaultWorkflowGraph(modelCode = 'deepseek-v4-flash'): WorkflowGraph {
  const node = createDefaultLlmNode(modelCode, 1);
  return {
    mode: 'sequential',
    rootNodeId: node.nodeId,
    nodes: [node],
    edges: [],
  };
}

/**
 * 创建默认 LLM 节点；参数是模型编码和序号；返回节点。
 */
export function createDefaultLlmNode(modelCode: string, index: number): WorkflowNode {
  return {
    nodeId: `node_${Date.now()}_${index}`,
    nodeType: 'llm',
    name: `LLM 节点 ${index}`,
    description: '执行一次模型思考，可挂载 MCP 或 Skill',
    instruction: '请根据当前上下文完成这个步骤，并输出清晰结果。',
    modelCode,
    mcpIds: [],
    skillIds: [],
    maxIterations: 3,
    x: 120 + (index - 1) * 280,
    y: 160,
  };
}
