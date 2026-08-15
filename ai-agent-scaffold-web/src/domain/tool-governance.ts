export type ApprovalTone = 'neutral' | 'warning' | 'danger';

export interface ApprovalPresentation {
  category: string;
  risk: string;
  tone: ApprovalTone;
  title: string;
  description: string;
}

export interface ApprovalInputSummary {
  key: string;
  value: string;
  sensitive: boolean;
}

export interface BatchOperationResult {
  succeededIds: string[];
  failedIds: string[];
  message: string;
}

const SENSITIVE_KEY = /(api[-_]?key|authorization|cookie|credential|env|password|secret|token)/i;

/** 将工具编码转换为审批界面的类型、风险和后果摘要。 */
export function approvalPresentation(toolCode = ''): ApprovalPresentation {
  if (toolCode === 'create_subagent_instances') {
    return {
      category: '子 Agent 编排', risk: '资源消耗', tone: 'warning',
      title: '批准创建子 Agent 任务？',
      description: '将创建独立子会话并消耗模型额度，全部完成后由主 Agent 统一汇总。',
    };
  }
  if (toolCode === 'cancel_subagent_instances') {
    return {
      category: '子 Agent 编排', risk: '中止任务', tone: 'danger', title: '批准取消子 Agent 任务？',
      description: '尚未终结的子任务将被取消，已产生的运行审计会保留。',
    };
  }
  if (toolCode === 'select_workflow_route') {
    return {
      category: '工作流路由', risk: '流程变更', tone: 'warning', title: '批准选择后续路由？',
      description: '将从当前节点已配置的合法路径中选择一条，并改变本次工作流走向。',
    };
  }
  if (toolCode.startsWith('mcp:')) {
    return {
      category: 'MCP 外部工具', risk: '外部调用', tone: 'warning', title: '批准调用外部 MCP？',
      description: '将按展示的参数访问外部工具，调用结果与耗时会记入运行审计。',
    };
  }
  if (toolCode.startsWith('skill:')) {
    return {
      category: 'Skill 指令包', risk: '指令加载', tone: 'neutral', title: '批准加载该 Skill？',
      description: '将读取已发布的 SKILL.md 指令并加入当前运行，不会执行包内代码。',
    };
  }
  return {
    category: '平台工具', risk: '平台操作', tone: 'neutral', title: '批准调用该平台工具？',
    description: '将使用当前参数执行一次平台工具调用，结果会写入本次运行审计。',
  };
}

/** 生成可阅读的调用参数摘要，并在界面展示前隐藏密钥类字段。 */
export function summarizeApprovalInput(input: Record<string, unknown> = {}): ApprovalInputSummary[] {
  return Object.entries(input).map(([key, value]) => {
    const sensitive = SENSITIVE_KEY.test(key) || containsSensitiveKey(value);
    return { key, value: compactValue(maskSensitive(value, key)), sensitive };
  });
}

/** 顺序执行批量操作，避免同一 Store 并发刷新产生覆盖，并保留失败项供重试。 */
export async function executeBatchOperation(ids: string[], operation: (id: string) => Promise<unknown>): Promise<BatchOperationResult> {
  const succeededIds: string[] = [];
  const failedIds: string[] = [];
  for (const id of ids) {
    try {
      await operation(id);
      succeededIds.push(id);
    } catch {
      failedIds.push(id);
    }
  }
  const message = failedIds.length
    ? `${succeededIds.length} 项已删除，${failedIds.length} 项失败；已保留失败项便于重试`
    : `${succeededIds.length} 项已删除`;
  return { succeededIds, failedIds, message };
}

function containsSensitiveKey(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsSensitiveKey);
  if (!value || typeof value !== 'object') return false;
  return Object.entries(value as Record<string, unknown>)
    .some(([key, child]) => SENSITIVE_KEY.test(key) || containsSensitiveKey(child));
}

function maskSensitive(value: unknown, key = ''): unknown {
  if (SENSITIVE_KEY.test(key)) return '已隐藏';
  if (Array.isArray(value)) return value.map((item) => maskSensitive(item));
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value as Record<string, unknown>)
    .map(([childKey, child]) => [childKey, maskSensitive(child, childKey)]));
}

function compactValue(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (text == null) return '';
  return text.length > 240 ? `${text.slice(0, 240)}…` : text;
}
