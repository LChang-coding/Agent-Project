import { defineStore } from 'pinia';

import {
  createMcp,
  createSkill,
  createSkillVersion,
  disableMcp,
  disableSkill,
  publishMcp,
  publishSkill,
  queryMcps,
  querySkills,
  queryToolCalls,
  queryToolCatalog,
  testMcp,
  uploadSkillPackage,
} from '@/api/tools';
import type {
  McpCreateRequest,
  McpDefinition,
  SkillCreateRequest,
  SkillDefinition,
  SkillPackageUploadResponse,
  SkillVersionCreateRequest,
  ToolCallLog,
  ToolCatalogItem,
} from '@/types/api';

let callsRequestGeneration = 0;

type ToolResourceType = 'skill' | 'mcp';
type ToolOperationType = 'publish' | 'disable' | 'test';

interface ToolResourceOperation {
  operationKey: string;
  type: ToolOperationType;
  pending: boolean;
  errorMessage: string;
  successMessage: string;
}

interface ToolState {
  skills: SkillDefinition[];
  mcps: McpDefinition[];
  catalog: ToolCatalogItem[];
  calls: ToolCallLog[];
  skillScope: string;
  mcpScope: string;
  loading: boolean;
  saving: boolean;
  errorMessage: string;
  resourceOperations: Record<string, ToolResourceOperation>;
  lastUploadedSkillPackage: SkillPackageUploadResponse | null;
}

export const useToolStore = defineStore('tools', {
  state: (): ToolState => ({
    skills: [],
    mcps: [],
    catalog: [],
    calls: [],
    skillScope: 'available',
    mcpScope: 'available',
    loading: false,
    saving: false,
    errorMessage: '',
    resourceOperations: {},
    lastUploadedSkillPackage: null,
  }),
  actions: {
    /**
     * 查询资源行操作状态；参数是资源类型和 ID；返回最近一次操作反馈。
     */
    resourceOperation(resourceType: ToolResourceType, resourceId: string) {
      return this.resourceOperations[`${resourceType}:${resourceId}`];
    },

    /**
     * 执行资源行操作；同一资源任一写操作进行中时拒绝重复或冲突请求。
     */
    async runResourceOperation<T>(
      resourceType: ToolResourceType,
      resourceId: string,
      operationType: ToolOperationType,
      action: () => Promise<T>,
      successMessage: string,
    ) {
      const resourceKey = `${resourceType}:${resourceId}`;
      if (this.resourceOperations[resourceKey]?.pending) {
        return null;
      }
      this.resourceOperations[resourceKey] = {
        operationKey: `${operationType}:${resourceId}`,
        type: operationType,
        pending: true,
        errorMessage: '',
        successMessage: '',
      };
      this.errorMessage = '';
      try {
        const result = await action();
        this.resourceOperations[resourceKey] = {
          ...this.resourceOperations[resourceKey],
          pending: false,
          successMessage,
        };
        return result;
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : '工具资源操作失败';
        this.errorMessage = errorMessage;
        this.resourceOperations[resourceKey] = {
          ...this.resourceOperations[resourceKey],
          pending: false,
          errorMessage,
        };
        throw error;
      }
    },

    /**
     * 上传 Skill 包；参数是文件；返回上传资产。
     */
    async uploadSkillPackage(file: File) {
      this.saving = true;
      this.errorMessage = '';
      try {
        const result = await uploadSkillPackage(file);
        this.lastUploadedSkillPackage = result;
        return result;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Skill 包上传失败';
        throw error;
      } finally {
        this.saving = false;
      }
    },

    /**
     * 创建 Skill；参数是创建请求；返回 Skill 定义。
     */
    async createSkill(payload: SkillCreateRequest) {
      this.saving = true;
      this.errorMessage = '';
      try {
        const result = await createSkill(payload);
        await this.loadSkills(this.skillScope);
        await this.loadCatalog();
        return result;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Skill 创建失败';
        throw error;
      } finally {
        this.saving = false;
      }
    },

    /**
     * 创建 Skill 版本；参数是 Skill ID 和版本请求；返回 Skill 定义。
     */
    async createSkillVersion(skillId: string, payload: SkillVersionCreateRequest) {
      this.saving = true;
      this.errorMessage = '';
      try {
        const result = await createSkillVersion(skillId, payload);
        await this.loadSkills(this.skillScope);
        return result;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Skill 版本创建失败';
        throw error;
      } finally {
        this.saving = false;
      }
    },

    /**
     * 加载 Skill 列表；参数是范围；返回列表。
     */
    async loadSkills(scope?: string) {
      this.loading = true;
      this.errorMessage = '';
      try {
        const actualScope = scope || this.skillScope;
        this.skillScope = actualScope;
        this.skills = await querySkills(actualScope);
        return this.skills;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'Skill 列表加载失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 发布 Skill；参数是 Skill ID 和版本；返回 Skill 定义。
     */
    async publishSkill(skillId: string, version?: string) {
      return this.runResourceOperation('skill', skillId, 'publish', async () => {
        const result = await publishSkill(skillId, { version });
        await this.loadSkills(this.skillScope);
        await this.loadCatalog();
        return result;
      }, 'Skill 已发布。');
    },

    /**
     * 禁用 Skill；参数是 Skill ID；返回 Skill 定义。
     */
    async disableSkill(skillId: string) {
      return this.runResourceOperation('skill', skillId, 'disable', async () => {
        const result = await disableSkill(skillId);
        await this.loadSkills(this.skillScope);
        await this.loadCatalog();
        return result;
      }, 'Skill 已禁用。');
    },

    /**
     * 创建 MCP；参数是创建请求；返回 MCP 定义。
     */
    async createMcp(payload: McpCreateRequest) {
      this.saving = true;
      this.errorMessage = '';
      try {
        const result = await createMcp(payload);
        await this.loadMcps(this.mcpScope);
        return result;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'MCP 创建失败';
        throw error;
      } finally {
        this.saving = false;
      }
    },

    /**
     * 加载 MCP 列表；参数是范围；返回列表。
     */
    async loadMcps(scope?: string) {
      this.loading = true;
      this.errorMessage = '';
      try {
        const actualScope = scope || this.mcpScope;
        this.mcpScope = actualScope;
        this.mcps = await queryMcps(actualScope);
        return this.mcps;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : 'MCP 列表加载失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 测试 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    async testMcp(mcpId: string) {
      return this.runResourceOperation('mcp', mcpId, 'test', async () => {
        const result = await testMcp(mcpId);
        await this.loadMcps(this.mcpScope);
        return result;
      }, 'MCP 测试完成。');
    },

    /**
     * 发布 MCP；参数是 MCP ID 和版本；返回 MCP 定义。
     */
    async publishMcp(mcpId: string, version?: string) {
      return this.runResourceOperation('mcp', mcpId, 'publish', async () => {
        const result = await publishMcp(mcpId, { version });
        await this.loadMcps(this.mcpScope);
        await this.loadCatalog();
        return result;
      }, 'MCP 已发布。');
    },

    /**
     * 禁用 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    async disableMcp(mcpId: string) {
      return this.runResourceOperation('mcp', mcpId, 'disable', async () => {
        const result = await disableMcp(mcpId);
        await this.loadMcps(this.mcpScope);
        await this.loadCatalog();
        return result;
      }, 'MCP 已禁用。');
    },

    /**
     * 加载工具目录；无参数；返回当前 Agent 可加载工具。
     */
    async loadCatalog() {
      this.catalog = await queryToolCatalog();
      return this.catalog;
    },

    /**
     * 加载工具调用日志；参数是会话ID；返回调用日志。
     */
    async loadCalls(sessionId: string) {
      const generation = ++callsRequestGeneration;
      if (!sessionId) {
        this.calls = [];
        return this.calls;
      }
      const calls = await queryToolCalls(sessionId);
      if (generation === callsRequestGeneration) {
        this.calls = calls;
      }
      return this.calls;
    },
  },
});
