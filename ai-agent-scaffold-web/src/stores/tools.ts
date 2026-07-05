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
    lastUploadedSkillPackage: null,
  }),
  actions: {
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
      const result = await publishSkill(skillId, { version });
      await this.loadSkills(this.skillScope);
      await this.loadCatalog();
      return result;
    },

    /**
     * 禁用 Skill；参数是 Skill ID；返回 Skill 定义。
     */
    async disableSkill(skillId: string) {
      const result = await disableSkill(skillId);
      await this.loadSkills(this.skillScope);
      await this.loadCatalog();
      return result;
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
      const result = await testMcp(mcpId);
      await this.loadMcps(this.mcpScope);
      return result;
    },

    /**
     * 发布 MCP；参数是 MCP ID 和版本；返回 MCP 定义。
     */
    async publishMcp(mcpId: string, version?: string) {
      const result = await publishMcp(mcpId, { version });
      await this.loadMcps(this.mcpScope);
      await this.loadCatalog();
      return result;
    },

    /**
     * 禁用 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    async disableMcp(mcpId: string) {
      const result = await disableMcp(mcpId);
      await this.loadMcps(this.mcpScope);
      await this.loadCatalog();
      return result;
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
      if (!sessionId) {
        this.calls = [];
        return this.calls;
      }
      this.calls = await queryToolCalls(sessionId);
      return this.calls;
    },
  },
});
