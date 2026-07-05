import { request } from '@/api/http';
import type {
  McpCreateRequest,
  McpDefinition,
  SkillCreateRequest,
  SkillDefinition,
  SkillPackageUploadResponse,
  SkillVersionCreateRequest,
  ToolCallLog,
  ToolCatalogItem,
  ToolPublishRequest,
} from '@/types/api';

/**
 * 上传 Skill 包；参数是 zip 文件；返回后端资产信息。
 */
export async function uploadSkillPackage(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request<SkillPackageUploadResponse>({
    url: '/v1/tools/skills/packages',
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/**
 * 创建 Skill 草稿；参数是 Skill 信息和 assetId；返回 Skill 定义。
 */
export async function createSkill(payload: SkillCreateRequest) {
  return request<SkillDefinition>({
    url: '/v1/tools/skills',
    method: 'POST',
    data: payload,
  });
}

/**
 * 查询 Skill；参数是范围；返回 Skill 列表。
 */
export async function querySkills(scope = 'available') {
  return request<SkillDefinition[]>({
    url: '/v1/tools/skills',
    method: 'GET',
    params: { scope },
  });
}

/**
 * 创建 Skill 新版本；参数是 Skill ID 和版本请求；返回 Skill 定义。
 */
export async function createSkillVersion(skillId: string, payload: SkillVersionCreateRequest) {
  return request<SkillDefinition>({
    url: `/v1/tools/skills/${skillId}/versions`,
    method: 'POST',
    data: payload,
  });
}

/**
 * 发布 Skill；参数是 Skill ID 和版本；返回 Skill 定义。
 */
export async function publishSkill(skillId: string, payload: ToolPublishRequest = {}) {
  return request<SkillDefinition>({
    url: `/v1/tools/skills/${skillId}/publish`,
    method: 'POST',
    data: payload,
  });
}

/**
 * 禁用 Skill；参数是 Skill ID；返回 Skill 定义。
 */
export async function disableSkill(skillId: string) {
  return request<SkillDefinition>({
    url: `/v1/tools/skills/${skillId}/disable`,
    method: 'POST',
  });
}

/**
 * 创建 MCP 草稿；参数是 MCP 配置；返回 MCP 定义。
 */
export async function createMcp(payload: McpCreateRequest) {
  return request<McpDefinition>({
    url: '/v1/tools/mcps',
    method: 'POST',
    data: payload,
  });
}

/**
 * 查询 MCP；参数是范围；返回 MCP 列表。
 */
export async function queryMcps(scope = 'available') {
  return request<McpDefinition[]>({
    url: '/v1/tools/mcps',
    method: 'GET',
    params: { scope },
  });
}

/**
 * 测试 MCP；参数是 MCP ID；返回 MCP 定义。
 */
export async function testMcp(mcpId: string) {
  return request<McpDefinition>({
    url: `/v1/tools/mcps/${mcpId}/test`,
    method: 'POST',
  });
}

/**
 * 发布 MCP；参数是 MCP ID 和版本；返回 MCP 定义。
 */
export async function publishMcp(mcpId: string, payload: ToolPublishRequest = {}) {
  return request<McpDefinition>({
    url: `/v1/tools/mcps/${mcpId}/publish`,
    method: 'POST',
    data: payload,
  });
}

/**
 * 禁用 MCP；参数是 MCP ID；返回 MCP 定义。
 */
export async function disableMcp(mcpId: string) {
  return request<McpDefinition>({
    url: `/v1/tools/mcps/${mcpId}/disable`,
    method: 'POST',
  });
}

/**
 * 查询工具目录；无参数；返回当前用户可被 Agent 加载的工具。
 */
export async function queryToolCatalog() {
  return request<ToolCatalogItem[]>({
    url: '/v1/tools/catalog',
    method: 'GET',
  });
}

/**
 * 查询工具调用日志；参数是会话ID；返回调用日志。
 */
export async function queryToolCalls(sessionId: string) {
  return request<ToolCallLog[]>({
    url: '/v1/tools/calls',
    method: 'GET',
    params: { sessionId },
  });
}
