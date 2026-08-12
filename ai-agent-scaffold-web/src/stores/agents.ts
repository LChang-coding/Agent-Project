import { defineStore } from 'pinia';

import { deleteAgentConfig, queryAgentConfigManagement, updateAgentConfigStatus, updateAgentToolPermission } from '@/api/agent';
import type { AgentConfigItem, AgentToolPermission } from '@/types/api';

interface AgentManagementState {
  agents: AgentConfigItem[];
  loading: boolean;
  mutatingAgentId: string;
  mutatingPermissionAgentId: string;
  errorMessage: string;
}

/**
 * Agent 管理 Store。
 * <p>管理列表包含禁用项，不与聊天和定时任务的运行态列表混用。</p>
 */
export const useAgentManagementStore = defineStore('agent-management', {
  state: (): AgentManagementState => ({
    agents: [],
    loading: false,
    mutatingAgentId: '',
    mutatingPermissionAgentId: '',
    errorMessage: '',
  }),
  getters: {
    enabledCount: (state) => state.agents.filter((agent) => agent.status === 'enabled').length,
    disabledCount: (state) => state.agents.filter((agent) => agent.status === 'disabled').length,
  },
  actions: {
    /**
     * 加载 Agent 管理列表；无参数；包含当前作用域已禁用配置。
     */
    async loadAgents() {
      this.loading = true;
      this.errorMessage = '';
      try {
        this.agents = await queryAgentConfigManagement(true);
        return this.agents;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Agent 管理列表加载失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 启用或禁用 Agent；参数是 Agent 和目标状态；失败时保留原列表。
     */
    async setEnabled(agent: AgentConfigItem, enabled: boolean) {
      if (this.mutatingAgentId || !agent.manageable) {
        return false;
      }
      this.mutatingAgentId = agent.agentId;
      this.errorMessage = '';
      try {
        const result = await updateAgentConfigStatus(agent.agentId, {
          enabled,
          reason: enabled ? '用户从控制台启用' : '用户从控制台禁用',
          expectedRevision: agent.revision,
        });
        this.applyMutation(agent.agentId, result.status, result.revision);
        return true;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : `Agent ${enabled ? '启用' : '禁用'}失败`;
        return false;
      } finally {
        this.mutatingAgentId = '';
      }
    },

    /**
     * 删除 Agent；参数是 Agent；服务端语义为作用域内幂等禁用。
     */
    async remove(agent: AgentConfigItem) {
      if (this.mutatingAgentId || !agent.manageable) {
        return false;
      }
      this.mutatingAgentId = agent.agentId;
      this.errorMessage = '';
      try {
        const result = await deleteAgentConfig(agent.agentId, agent.revision);
        this.applyMutation(agent.agentId, result.status, result.revision);
        return true;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Agent 删除失败';
        return false;
      } finally {
        this.mutatingAgentId = '';
      }
    },

    async saveToolPermission(agent: AgentConfigItem, permission: AgentToolPermission) {
      if (this.mutatingPermissionAgentId || !agent.manageable) return false;
      this.mutatingPermissionAgentId = `${agent.agentId}:${permission.toolCode}`; this.errorMessage = '';
      try {
        const saved = await updateAgentToolPermission(agent.agentId, permission.toolCode, {
          mode: permission.mode,
          timeoutSeconds: permission.timeoutSeconds,
          timeoutDecision: permission.timeoutDecision,
          suggestions: permission.suggestions,
          expectedRevision: permission.revision,
        });
        const index = agent.toolPermissions?.findIndex((item) => item.toolCode === saved.toolCode) ?? -1;
        if (index >= 0 && agent.toolPermissions) agent.toolPermissions[index] = saved;
        return true;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '工具权限保存失败';
        return false;
      } finally { this.mutatingPermissionAgentId = ''; }
    },

    /**
     * 同步单条变更；参数是 Agent ID、状态和修订号；保留列表顺序。
     */
    applyMutation(agentId: string, status: AgentConfigItem['status'], revision: number) {
      const agent = this.agents.find((item) => item.agentId === agentId);
      if (agent) {
        agent.status = status;
        agent.revision = revision;
        agent.disabledAt = status === 'disabled' ? new Date().toISOString() : undefined;
      }
    },
  },
});
