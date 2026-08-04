import type { WorkflowEdge, WorkflowGraph, WorkflowNode } from '@/types/api';

export type WorkflowTemplateCategory = 'PRODUCTION' | 'TEST';
export type WorkflowTemplateKind = 'STATIC' | 'INTELLIGENT';

/** 可在工作流画布中载入的稳定模板。 */
export interface WorkflowTemplate {
  id: string;
  name: string;
  description: string;
  category: WorkflowTemplateCategory;
  workflowKind: WorkflowTemplateKind;
  tags: string[];
  dependencyHints: string[];
  graph: WorkflowGraph;
}

type EdgeInput = [sourceNodeId: string, targetNodeId: string];
type IntelligentEdgeInput = [
  sourceNodeId: string,
  targetNodeId: string,
  routeType: NonNullable<WorkflowEdge['routeType']>,
  routeKey?: string,
  conditionExpression?: string,
  routeAliases?: string[],
];

const MODEL_CODE = 'deepseek-v4-flash';
const INTELLIGENT_STRATEGIES: NonNullable<WorkflowNode['enabledStrategies']> = [
  'FIXED',
  'SUCCESS',
  'EXPRESSION',
  'NODE_SUGGESTION',
  'AI_ROUTER',
  'DEFAULT',
];

/** 生产参考与确定性测试模板清单。 */
export const WORKFLOW_TEMPLATES: readonly WorkflowTemplate[] = [
  productionStatic('prod-static-customer-support', '客服工单闭环', '从工单理解到回复质检的三步串行链路。',
    ['客服', '串行'], ['客服知识库（可选）'],
    nodes([
      ['receive', '理解工单', '提取用户诉求、产品、紧急度和已知事实。'],
      ['draft', '生成回复', '基于上游事实生成可执行、不夸大的客服回复。'],
      ['quality', '回复质检', '检查准确性、语气和是否包含明确下一步。'],
    ]), [['receive', 'draft'], ['draft', 'quality']]),
  productionStatic('prod-static-knowledge-report', '知识报告汇聚', '并行提取事实与风险，再汇聚为管理摘要。',
    ['知识库', '并行', '汇聚'], ['RAG 绑定（可选）'],
    nodes([
      ['plan', '报告提纲', '根据问题确定报告范围和证据维度。'],
      ['facts', '事实提取', '提取可引用的事实、数据和时间点。'],
      ['risks', '风险分析', '识别矛盾证据、缺失数据和结论边界。'],
      ['report', '汇聚报告', '合并事实和风险，输出带优先级的管理摘要。'],
    ]), [['plan', 'facts'], ['plan', 'risks'], ['facts', 'report'], ['risks', 'report']]),
  productionStatic('prod-static-contract-review', '合同审阅流水线', '条款抽取、风险分级和建议收口的标准流程。',
    ['合同', '审阅', '串行'], ['合同文档附件'],
    nodes([
      ['extract', '条款抽取', '按主体、付款、交付、违约和解约结构化条款。'],
      ['grade', '风险分级', '对每项条款给出风险等级、依据和影响。'],
      ['advice', '修订建议', '给出可直接用于谈判的修订文案与优先级。'],
    ]), [['extract', 'grade'], ['grade', 'advice']]),
  productionStatic('prod-static-incident-analysis', '故障并行分析', '并行排查变更、资源与依赖，统一生成处置方案。',
    ['故障', '并行', '汇聚'], ['日志或监控数据'],
    nodes([
      ['scope', '影响定界', '明确故障影响范围、开始时间和优先级。'],
      ['change', '变更排查', '核对故障窗口内的发布、配置和数据变更。'],
      ['capacity', '资源排查', '分析 CPU、内存、连接池、队列与拒绝指标。'],
      ['dependency', '依赖排查', '检查数据库、消息、第三方和网络依赖。'],
      ['action', '处置方案', '合并证据，输出止损、验证、回滚和后续行动。'],
    ]), [['scope', 'change'], ['scope', 'capacity'], ['scope', 'dependency'], ['change', 'action'], ['capacity', 'action'], ['dependency', 'action']]),
  productionStatic('prod-static-content-release', '内容发布流程', '把原始素材转换为已校对的多渠道发布稿。',
    ['内容', '发布', '串行'], ['品牌规范（可选）'],
    nodes([
      ['brief', '素材整理', '归纳目标受众、核心事实、渠道和禁用表述。'],
      ['write', '内容撰写', '按品牌语气输出标题、正文和行动号召。'],
      ['proof', '事实校对', '检查数据、名词、时间和不当承诺。'],
      ['adapt', '渠道改写', '在不改变事实的前提下生成各渠道版本。'],
    ]), [['brief', 'write'], ['write', 'proof'], ['proof', 'adapt']]),
  productionStatic('prod-static-research-synthesis', '多视角研究综述', '并行分析技术、市场和风险，产出一份综合结论。',
    ['研究', '并行', '汇聚'], ['研究语料或 RAG 绑定'],
    nodes([
      ['question', '研究问题', '把目标拆成可验证的研究问题和证据标准。'],
      ['tech', '技术分析', '分析技术可行性、依赖和实施成本。'],
      ['market', '市场分析', '分析用户、竞品、差异化和进入时机。'],
      ['risk', '风险分析', '列出证据缺口、前提条件和可逆决策点。'],
      ['synthesis', '综合结论', '整合三路分析，区分事实、推断和建议。'],
    ]), [['question', 'tech'], ['question', 'market'], ['question', 'risk'], ['tech', 'synthesis'], ['market', 'synthesis'], ['risk', 'synthesis']]),

  productionIntelligent('prod-intelligent-audit-revision', '审核与返工', '审核不通过时有界返工，通过后显式结束。',
    ['审核', '返工', '有限回路'], [],
    nodes([
      ['draft', '生成草稿', '根据任务与审核反馈生成新草稿。'],
      ['review', '审核草稿', '审核准确性和完整性；通过时输出 [route:approve]，否则输出 [route:revise]。'],
    ]), [
      ['draft', 'review', 'DEFAULT'],
      ['review', 'END', 'NODE_SUGGESTION', 'approve'],
      ['review', 'draft', 'NODE_SUGGESTION', 'revise'],
      ['review', 'draft', 'DEFAULT'],
    ], 12, 48000),
  productionIntelligent('prod-intelligent-customer-router', '客服意图路由', '根据问题意图动态选择账务、技术或人工升级。',
    ['客服', '多分支', 'AI Router'], [],
    nodes([
      ['classify', '意图判定', '识别账务、技术或无法确定的诉求。'],
      ['billing', '账务处理', '回答订阅、发票、退款和费用问题。'],
      ['technical', '技术支持', '输出可验证的故障排查步骤。'],
      ['manual', '人工升级摘要', '生成交给人工的事实摘要与已尝试动作。'],
    ]), [
      ['classify', 'billing', 'AI_ROUTER', '账务', undefined, ['billing']],
      ['classify', 'technical', 'AI_ROUTER', '技术', undefined, ['technical']],
      ['classify', 'manual', 'DEFAULT'],
      ['billing', 'END', 'DEFAULT'],
      ['technical', 'END', 'DEFAULT'],
      ['manual', 'END', 'DEFAULT'],
    ]),
  productionIntelligent('prod-intelligent-rag-retry', 'RAG 质量自修复', '检索证据不足时改写问题并有界重试。',
    ['RAG', '重试', '有限回路'], ['已绑定知识库'],
    nodes([
      ['retrieve', '证据检查', '评估检索结果是否足以回答；足够输出 [route:answer]，不足输出 [route:rewrite]。'],
      ['rewrite', '查询改写', '补充别名、时间和业务约束，生成新查询。'],
      ['answer', '有证据回答', '只使用已验证证据回答，缺失时明确说明。'],
    ]), [
      ['retrieve', 'answer', 'NODE_SUGGESTION', 'answer'],
      ['retrieve', 'rewrite', 'DEFAULT'],
      ['rewrite', 'retrieve', 'DEFAULT'],
      ['answer', 'END', 'DEFAULT'],
    ], 10, 64000),
  productionIntelligent('prod-intelligent-tool-recovery', '工具失败自恢复', '工具参数错误时进入修复节点，无法恢复时安全收口。',
    ['工具', '异常路由', '恢复'], ['受权工具（可选）'],
    nodes([
      ['invoke', '工具任务', '根据已知参数执行任务，保留可恢复的错误摘要。'],
      ['repair', '参数修复', '仅修复可确定的参数格式与缺失字段。'],
      ['fallback', '安全降级', '说明未完成的动作、原因和人工处理建议。'],
    ]), [
      ['invoke', 'repair', 'FAILURE'],
      ['invoke', 'END', 'DEFAULT'],
      ['repair', 'invoke', 'NODE_SUGGESTION', 'retry'],
      ['repair', 'fallback', 'DEFAULT'],
      ['fallback', 'END', 'DEFAULT'],
    ], 10, 48000),
  productionIntelligent('prod-intelligent-human-escalation', '风险升级决策', '低风险自动收口，高风险生成人工审批材料。',
    ['风险', '升级', '人工'], [],
    nodes([
      ['assess', '风险评估', '按影响、不可逆性和证据完整度评估风险。'],
      ['automatic', '自动结论', '生成低风险结论与可回滚步骤。'],
      ['escalate', '审批材料', '整理决策、证据、风险和建议审批人。'],
    ]), [
      ['assess', 'automatic', 'EXPRESSION', undefined, "output contains '低风险'"],
      ['assess', 'escalate', 'DEFAULT'],
      ['automatic', 'END', 'DEFAULT'],
      ['escalate', 'END', 'DEFAULT'],
    ]),
  productionIntelligent('prod-intelligent-content-risk', '内容风险门禁', '合规通过直接发布，需修订时返回创作节点。',
    ['内容', '合规', '有限回路'], ['合规规则（可选）'],
    nodes([
      ['create', '创作内容', '根据任务与上次风险反馈生成内容。'],
      ['guard', '合规审核', '通过时输出“通过”，否则输出具体风险和修订要求。'],
      ['publish', '发布摘要', '生成已通过审核的内容摘要与发布清单。'],
    ]), [
      ['create', 'guard', 'DEFAULT'],
      ['guard', 'publish', 'EXPRESSION', undefined, "output contains '通过'"],
      ['guard', 'create', 'DEFAULT'],
      ['publish', 'END', 'DEFAULT'],
    ], 12, 64000),

  testStatic('test-static-single-node', '测试：单节点', '最小静态图，用于验证 Run 与终态事件。', ['单节点'],
    nodes([['only', '唯一节点', '原样返回输入，便于确定性断言。']]), []),
  testStatic('test-static-serial-three', '测试：三节点串行', '验证节点开始和完成的严格先后关系。', ['串行'],
    nodes([
      ['a', '串行 A', '输出 A。'], ['b', '串行 B', '读取 A 并输出 B。'], ['c', '串行 C', '读取 B 并输出 C。'],
    ]), [['a', 'b'], ['b', 'c']]),
  testStatic('test-static-fanout-join', '测试：扇出汇聚', '验证同层并行与 Join 等待。', ['并行', '汇聚'],
    nodes([
      ['start', '起点', '输出固定起点文本。'],
      ['left', '左分支', '输出 LEFT。'],
      ['right', '右分支', '输出 RIGHT。'],
      ['join', '汇聚', '同时读取 LEFT 与 RIGHT 后输出 JOIN。'],
    ]), [['start', 'left'], ['start', 'right'], ['left', 'join'], ['right', 'join']]),
  testStatic('test-static-two-level-join', '测试：两层汇聚', '验证多层拓扑分层与下游屏障。', ['多层', '并行', '汇聚'],
    nodes([
      ['root', '根节点', '输出 ROOT。'],
      ['left', '第一层左', '输出 L1。'],
      ['right', '第一层右', '输出 R1。'],
      ['join1', '第一汇聚', '合并 L1 与 R1。'],
      ['detail', '细化分支', '输出 DETAIL。'],
      ['summary', '第二汇聚', '合并第一汇聚与 DETAIL。'],
    ]), [['root', 'left'], ['root', 'right'], ['left', 'join1'], ['right', 'join1'], ['join1', 'summary'], ['root', 'detail'], ['detail', 'summary']]),
  testStatic('test-static-bounded-self-loop', '测试：有限自循环', '验证自循环实例、次数上限和后继节点。', ['自循环', '有界'],
    nodes([
      ['iterate', '迭代节点', '每次输出当前迭代次数。', { maxIterations: 3 }],
      ['finish', '迭代收口', '汇总迭代结果。'],
    ]), [['iterate', 'iterate'], ['iterate', 'finish']]),
  testStatic('test-static-node-failure', '测试：节点失败', '使用可控模型或 Stub 触发中间节点失败。', ['失败', '终态'],
    nodes([
      ['before', '失败前', '输出 BEFORE。'],
      ['fail', '故障注入点', '在测试环境请 Stub 返回预期错误。'],
      ['after', '不应执行', '中间节点失败时不应进入本节点。'],
    ]), [['before', 'fail'], ['fail', 'after']]),

  testIntelligent('test-intelligent-binary-router', '测试：AI 二分支', '验证 AI Router 选择合法目标与 DEFAULT 兜底。', ['AI Router', '二分支'],
    nodes([['route', '二分路由', '输入 alpha 时选择 A，其余由兜底处理。'], ['a', 'A 分支', '输出 A。'], ['b', 'B 分支', '输出 B。']]), [
      ['route', 'a', 'AI_ROUTER', 'alpha'], ['route', 'b', 'DEFAULT'], ['a', 'END', 'DEFAULT'], ['b', 'END', 'DEFAULT'],
    ]),
  testIntelligent('test-intelligent-multi-router', '测试：AI 多分支', '验证三个候选目标、路由键和优先级。', ['AI Router', '多分支'],
    nodes([['route', '多分路由', '根据 red、green 或其他输入选路。'], ['red', '红色分支', '输出 RED。'], ['green', '绿色分支', '输出 GREEN。'], ['other', '其他分支', '输出 OTHER。']]), [
      ['route', 'red', 'AI_ROUTER', 'red'], ['route', 'green', 'AI_ROUTER', 'green'], ['route', 'other', 'DEFAULT'],
      ['red', 'END', 'DEFAULT'], ['green', 'END', 'DEFAULT'], ['other', 'END', 'DEFAULT'],
    ]),
  testIntelligent('test-intelligent-default-fallback', '测试：DEFAULT 兜底', '验证节点建议不可用时进入确定兜底。', ['DEFAULT', '兜底'],
    nodes([['decide', '建议节点', '输出一个不在允许集合的路由键。'], ['accepted', '合法分支', '不应被非法建议选中。'], ['fallback', '兜底分支', '输出 FALLBACK。']]), [
      ['decide', 'accepted', 'NODE_SUGGESTION', 'accepted'], ['decide', 'fallback', 'DEFAULT'],
      ['accepted', 'END', 'DEFAULT'], ['fallback', 'END', 'DEFAULT'],
    ]),
  testIntelligent('test-intelligent-bounded-cycle', '测试：有限回路', '验证节点 maxVisits 和运行 maxSteps 双重上限。', ['回路', '有界'],
    nodes([['generate', '生成', '输出版本内容。'], ['review', '审核', '前两次输出 [route:retry]，之后输出 [route:done]。']]), [
      ['generate', 'review', 'DEFAULT'], ['review', 'END', 'NODE_SUGGESTION', 'done'], ['review', 'generate', 'DEFAULT'],
    ], 8, 16000),
  testIntelligent('test-intelligent-expression', '测试：表达式路由', '验证受限 contains 表达式与 DEFAULT 的确定顺序。', ['EXPRESSION', 'DEFAULT'],
    nodes([['check', '表达式判定', '输入包含 pass 时输出“通过”。'], ['pass', '通过分支', '输出 PASS。'], ['fallback', '兜底分支', '输出 FALLBACK。']]), [
      ['check', 'pass', 'EXPRESSION', undefined, "output contains '通过'"], ['check', 'fallback', 'DEFAULT'],
      ['pass', 'END', 'DEFAULT'], ['fallback', 'END', 'DEFAULT'],
    ]),
  testIntelligent('test-intelligent-node-suggestion', '测试：节点建议', '验证 route key 命中允许目标与无效建议兜底。', ['NODE_SUGGESTION', 'DEFAULT'],
    nodes([['suggest', '路由建议', '输入 next 时输出 [route:next]。'], ['next', '建议目标', '输出 NEXT。'], ['fallback', '兜底目标', '输出 FALLBACK。']]), [
      ['suggest', 'next', 'NODE_SUGGESTION', 'next'], ['suggest', 'fallback', 'DEFAULT'],
      ['next', 'END', 'DEFAULT'], ['fallback', 'END', 'DEFAULT'],
    ]),
];

/** 按 ID 获取模板定义；返回值不可用于直接修改清单。 */
export function workflowTemplateById(templateId: string) {
  return WORKFLOW_TEMPLATES.find((template) => template.id === templateId);
}

/** 创建可安全编辑的模板图副本。 */
export function cloneWorkflowTemplateGraph(templateId: string): WorkflowGraph {
  const template = workflowTemplateById(templateId);
  if (!template) throw new Error(`工作流模板不存在: ${templateId}`);
  return JSON.parse(JSON.stringify(template.graph)) as WorkflowGraph;
}

function productionStatic(
  id: string,
  name: string,
  description: string,
  tags: string[],
  dependencyHints: string[],
  templateNodes: WorkflowNode[],
  templateEdges: EdgeInput[],
) {
  return template(id, name, description, 'PRODUCTION', 'STATIC', tags, dependencyHints,
    staticGraph(templateNodes, templateEdges));
}

function testStatic(
  id: string,
  name: string,
  description: string,
  tags: string[],
  templateNodes: WorkflowNode[],
  templateEdges: EdgeInput[],
) {
  return template(id, name, description, 'TEST', 'STATIC', tags, [], staticGraph(templateNodes, templateEdges));
}

function productionIntelligent(
  id: string,
  name: string,
  description: string,
  tags: string[],
  dependencyHints: string[],
  templateNodes: WorkflowNode[],
  templateEdges: IntelligentEdgeInput[],
  maxSteps = 20,
  tokenBudget = 64000,
) {
  return template(id, name, description, 'PRODUCTION', 'INTELLIGENT', tags, dependencyHints,
    intelligentGraph(templateNodes, templateEdges, maxSteps, tokenBudget));
}

function testIntelligent(
  id: string,
  name: string,
  description: string,
  tags: string[],
  templateNodes: WorkflowNode[],
  templateEdges: IntelligentEdgeInput[],
  maxSteps = 12,
  tokenBudget = 32000,
) {
  return template(id, name, description, 'TEST', 'INTELLIGENT', tags, [],
    intelligentGraph(templateNodes, templateEdges, maxSteps, tokenBudget));
}

function template(
  id: string,
  name: string,
  description: string,
  category: WorkflowTemplateCategory,
  workflowKind: WorkflowTemplateKind,
  tags: string[],
  dependencyHints: string[],
  graph: WorkflowGraph,
): WorkflowTemplate {
  return { id, name, description, category, workflowKind, tags, dependencyHints, graph };
}

function nodes(inputs: Array<[
  nodeId: string,
  name: string,
  instruction: string,
  overrides?: Partial<WorkflowNode>,
]>): WorkflowNode[] {
  return inputs.map(([nodeId, name, instruction, overrides], index) => ({
    nodeId,
    nodeType: 'llm',
    name,
    description: instruction,
    instruction,
    modelCode: MODEL_CODE,
    mcpIds: [],
    skillIds: [],
    maxIterations: 3,
    x: 80 + (index % 4) * 275,
    y: 90 + Math.floor(index / 4) * 190,
    ...overrides,
  }));
}

function staticGraph(templateNodes: WorkflowNode[], inputs: EdgeInput[]): WorkflowGraph {
  const edges = inputs.map(([sourceNodeId, targetNodeId], index) => edge(sourceNodeId, targetNodeId, index));
  return {
    workflowKind: 'STATIC',
    mode: inferMode(edges),
    rootNodeId: rootNodeId(templateNodes, edges),
    nodes: templateNodes,
    edges,
  };
}

function intelligentGraph(
  templateNodes: WorkflowNode[],
  inputs: IntelligentEdgeInput[],
  maxSteps: number,
  tokenBudget: number,
): WorkflowGraph {
  const edges = inputs.map(([sourceNodeId, targetNodeId, routeType, routeKey, conditionExpression, routeAliases], index) => ({
    ...edge(sourceNodeId, targetNodeId, index),
    routeType,
    routeKey,
    routeAliases: routeAliases || [],
    conditionExpression,
    priority: index,
  }));
  const nodesWithRouting = templateNodes.map((node) => {
    const outgoing = edges.filter((candidate) => candidate.sourceNodeId === node.nodeId);
    const fallback = outgoing.find((candidate) => candidate.routeType === 'DEFAULT');
    return {
      ...node,
      enabledStrategies: [...INTELLIGENT_STRATEGIES],
      allowedTargetNodeIds: [...new Set(outgoing.map((candidate) => candidate.targetNodeId))],
      defaultTargetNodeId: fallback?.targetNodeId,
      routeInstruction: routeInstruction(outgoing, templateNodes),
      maxVisits: 4,
    } satisfies WorkflowNode;
  });
  return {
    workflowKind: 'INTELLIGENT',
    maxSteps,
    tokenBudget,
    mode: inferMode(edges),
    rootNodeId: rootNodeId(nodesWithRouting, edges),
    nodes: nodesWithRouting,
    edges,
  };
}

/** 由模板边生成可执行的路由协议，避免节点提示词和 route key 分别维护。 */
function routeInstruction(edges: WorkflowEdge[], templateNodes: WorkflowNode[]): string {
  const lines = edges.flatMap((candidate) => {
    const target = candidate.targetNodeId === 'END'
      ? '结束工作流'
      : templateNodes.find((node) => node.nodeId === candidate.targetNodeId)?.name || candidate.targetNodeId;
    if (candidate.routeType === 'AI_ROUTER' || candidate.routeType === 'NODE_SUGGESTION') {
      const aliases = candidate.routeAliases?.length ? `；兼容别名：${candidate.routeAliases.join('、')}` : '';
      return [`需要进入“${target}”时，在正文末尾独立一行精确输出 [route:${candidate.routeKey}]${aliases}。`];
    }
    if (candidate.routeType === 'DEFAULT') {
      return [`没有任何路由键适用时，由 DEFAULT 进入“${target}”；不要编造路由键。`];
    }
    return [];
  });
  return lines.length ? lines.join('\n') : '当前节点没有可建议的路由键。';
}

function edge(sourceNodeId: string, targetNodeId: string, index: number): WorkflowEdge {
  return {
    edgeId: `edge_${sourceNodeId}_${targetNodeId}_${index + 1}`,
    sourceNodeId,
    targetNodeId,
  };
}

function inferMode(edges: WorkflowEdge[]): WorkflowGraph['mode'] {
  if (edges.some((candidate) => candidate.sourceNodeId === candidate.targetNodeId)) return 'loop';
  const inDegree = new Map<string, number>();
  const outDegree = new Map<string, number>();
  edges.forEach((candidate) => {
    if (candidate.targetNodeId !== 'END') {
      inDegree.set(candidate.targetNodeId, (inDegree.get(candidate.targetNodeId) || 0) + 1);
    }
    outDegree.set(candidate.sourceNodeId, (outDegree.get(candidate.sourceNodeId) || 0) + 1);
  });
  return [...inDegree.values(), ...outDegree.values()].some((count) => count > 1) ? 'parallel' : 'sequential';
}

function rootNodeId(templateNodes: WorkflowNode[], edges: WorkflowEdge[]) {
  const incoming = new Set(edges
    .filter((candidate) => candidate.sourceNodeId !== candidate.targetNodeId && candidate.targetNodeId !== 'END')
    .map((candidate) => candidate.targetNodeId));
  return templateNodes.find((node) => !incoming.has(node.nodeId))?.nodeId || templateNodes[0]?.nodeId || '';
}
