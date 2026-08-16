<template>
  <div class="page workflow-page">
    <SectionHeader
      title="工作流编排"
      description="用拖拽节点和箭头描述执行关系：自循环代表循环，多入度或多出度代表并行，其余链路代表串行。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" :disabled="workflowStore.writing || workflowStore.loading" @click="reload">刷新</button>
          <button class="button button--primary" type="button" :disabled="workflowStore.writing || workflowStore.loading" @click="createNewWorkflow">新建工作流</button>
        </div>
      </template>
    </SectionHeader>

    <nav class="workflow-kind-tabs" aria-label="工作流类型">
      <button :class="{ active: graph.workflowKind !== 'INTELLIGENT' }" type="button" @click="setWorkflowKind('STATIC')">
        <strong>系统工作流</strong><span>固定 DAG，执行更可预测</span>
      </button>
      <button :class="{ active: graph.workflowKind === 'INTELLIGENT' }" type="button" @click="setWorkflowKind('INTELLIGENT')">
        <strong>智能工作流</strong><span>每个 Agent 可动态选路，但会消耗更多 Token</span>
      </button>
    </nav>

    <section class="workflow-template-library card" aria-labelledby="workflow-template-title">
      <div class="template-library__intro">
        <div>
          <span class="template-library__eyebrow">TEMPLATE LIBRARY</span>
          <h2 id="workflow-template-title">工作流模板</h2>
          <p>载入只会替换当前页面的草稿画布，不会自动保存或发布。</p>
        </div>
        <strong>{{ WORKFLOW_TEMPLATES.length }} 个</strong>
      </div>
      <div class="template-library__controls">
        <label>
          <span>用途</span>
          <select v-model="templateCategoryFilter" class="select">
            <option value="ALL">全部用途</option>
            <option value="PRODUCTION">生产参考</option>
            <option value="TEST">测试验证</option>
          </select>
        </label>
        <label>
          <span>类型</span>
          <select v-model="templateKindFilter" class="select">
            <option value="ALL">全部类型</option>
            <option value="STATIC">系统工作流</option>
            <option value="INTELLIGENT">智能工作流</option>
          </select>
        </label>
        <label class="template-library__picker">
          <span>模板</span>
          <select v-model="selectedTemplateId" class="select">
            <option v-for="template in filteredTemplates" :key="template.id" :value="template.id">
              {{ template.name }} · {{ template.workflowKind === 'INTELLIGENT' ? '智能' : '系统' }}
            </option>
          </select>
        </label>
        <button class="button button--soft" type="button"
                :disabled="!selectedTemplate || workflowStore.loading || workflowStore.writing"
                @click="loadSelectedTemplate">
          {{ workflowStore.loading ? '正在恢复草稿…' : '载入当前草稿' }}
        </button>
      </div>
      <div v-if="selectedTemplate" class="template-library__detail">
        <div>
          <span :class="['template-badge', `template-badge--${selectedTemplate.category.toLowerCase()}`]">
            {{ selectedTemplate.category === 'PRODUCTION' ? '生产参考' : '测试验证' }}
          </span>
          <strong>{{ selectedTemplate.name }}</strong>
          <p>{{ selectedTemplate.description }}</p>
        </div>
        <div class="template-library__meta">
          <span>{{ selectedTemplate.graph.nodes.length }} 节点 / {{ selectedTemplate.graph.edges.length }} 条边</span>
          <span v-for="tag in selectedTemplate.tags" :key="tag">{{ tag }}</span>
          <span v-for="dependency in selectedTemplate.dependencyHints" :key="dependency" class="template-dependency">依赖：{{ dependency }}</span>
          <span v-if="selectedTemplate.dependencyHints.length === 0" class="template-dependency">无必需外部依赖</span>
        </div>
      </div>
    </section>

    <section v-if="graph.workflowKind === 'INTELLIGENT'" class="intelligent-budget card">
      <label>最大执行步数 <input v-model.number="graph.maxSteps" class="input" type="number" min="1" max="200" /></label>
      <label>Token 总预算 <input v-model.number="graph.tokenBudget" class="input" type="number" min="1" max="10000000" /></label>
      <span>超过任一预算都会在下一次模型调用前终止，防止无界循环。</span>
    </section>

    <section class="workflow-shell">
      <aside class="workflow-list card">
        <div class="card__body">
          <div class="mini-title">
            <span>工作流库</span>
            <strong>{{ workflowStore.workflows.length }}</strong>
          </div>
          <div class="create-box">
            <input v-model="newWorkflowName" class="input" placeholder="工作流名称" />
            <button class="button button--primary" type="button" :disabled="workflowStore.loading || workflowStore.writing" @click="createNewWorkflow">
              创建并刷新
            </button>
          </div>
          <div class="workflow-items">
            <button
              v-for="workflow in workflowStore.workflows"
              :key="workflow.workflowId"
              :class="['workflow-item', { 'workflow-item--active': workflow.workflowId === workflowStore.activeWorkflowId }]"
              type="button"
              :disabled="workflowStore.writing"
              @click="selectWorkflow(workflow.workflowId)"
            >
              <strong>{{ workflow.workflowName }}</strong>
              <span>{{ statusLabel(workflow.status) }} · 草稿 v{{ workflow.currentVersion }} · 发布 v{{ workflow.publishedVersion }}</span>
            </button>
            <div v-if="workflowStore.workflows.length === 0" class="empty-card">
              还没有工作流，先创建一个工作流看板。
            </div>
          </div>
        </div>
      </aside>

      <main class="workflow-main card">
        <div class="workflow-toolbar">
          <div class="field workflow-name-field">
            <label>工作流名称</label>
            <input v-model="workflowName" class="input" placeholder="例如：企业知识问答工作流" />
          </div>
          <div class="field">
            <label>默认模型</label>
            <select v-model="defaultModelCode" class="select">
              <option v-for="model in modelOptions" :key="model.value" :value="model.value">
                {{ model.label }}
              </option>
            </select>
          </div>
          <div class="mode-summary">
            <span>自动识别</span>
            <strong>{{ modeLabel(derivedMode) }}</strong>
            <small>{{ modeReason }}</small>
          </div>
          <div class="toolbar-actions">
            <button class="button" type="button" @click="addLlmNode">添加节点</button>
            <button
              :class="['button', { 'button--soft': linkingSourceId }]"
              type="button"
              @click="toggleLinking"
            >
              {{ linkingSourceId ? '取消连线' : '开始连线' }}
            </button>
            <button class="button" type="button" @click="autoLayout">自动排布</button>
            <button class="button" type="button" :disabled="!activeNode || graph.nodes.length <= 1" @click="removeActiveNode">删除节点</button>
            <button class="button button--danger" type="button"
                    :disabled="!workflowStore.activeWorkflowId || workflowStore.writing || workflowStore.loading"
                    @click="removeWorkflow">
              {{ workflowStore.deletingWorkflowId ? '删除中...' : '删除工作流' }}
            </button>
            <button class="button button--primary" type="button" :disabled="workflowStore.writing || workflowStore.loading || !workflowStore.activeWorkflowId" @click="saveDraft">
              {{ workflowStore.saving ? '保存中...' : '保存草稿' }}
            </button>
            <button class="button button--dark" type="button" :disabled="workflowStore.writing || workflowStore.loading || !workflowStore.activeWorkflowId" @click="publish">
              {{ workflowStore.publishing ? '发布中...' : '发布运行' }}
            </button>
          </div>
        </div>

        <div class="canvas-help">
          <span v-if="linkingSourceId">正在从「{{ nodeName(linkingSourceId) }}」连线，点击目标节点完成箭头。</span>
          <span v-else>拖动节点调整布局；点击“连线”后选择起点和终点；点击“自循环”可设置循环次数。</span>
          <strong>{{ graph.nodes.length }} 节点 / {{ graph.edges.length }} 条边</strong>
        </div>

        <div ref="canvasRef" class="workflow-canvas" @pointerdown.self="clearLinking">
          <svg class="edge-layer" :viewBox="`0 0 ${canvasSize.width} ${canvasSize.height}`" aria-hidden="true">
            <defs>
              <marker id="workflow-arrow" markerHeight="10" markerWidth="10" orient="auto" refX="9" refY="3" viewBox="0 0 10 6">
                <path d="M0,0 L10,3 L0,6 Z" />
              </marker>
            </defs>
            <g v-for="edge in renderedEdges" :key="edge.edgeId" class="edge-group">
              <path :class="['edge-path', { 'edge-path--loop': edge.selfLoop }]" :d="edge.path" marker-end="url(#workflow-arrow)" />
              <text class="edge-label" :x="edge.labelX" :y="edge.labelY">{{ edge.label }}</text>
            </g>
          </svg>

          <div
            v-for="(node, index) in graph.nodes"
            :key="node.nodeId"
            :class="[
              'canvas-node',
              {
                'canvas-node--active': node.nodeId === selectedNodeId,
                'canvas-node--linking': node.nodeId === linkingSourceId,
                'canvas-node--loop': hasSelfLoop(node.nodeId),
              },
            ]"
            :style="nodeStyle(node)"
            role="group"
            :aria-label="`节点 ${node.name}，点击选中，回车或空格键确认`"
            tabindex="0"
            @click.stop="selectOrConnectNode(node)"
            @keydown.enter.prevent="selectOrConnectNode(node)"
            @keydown.space.prevent="selectOrConnectNode(node)"
            @pointerdown="startDrag($event, node)"
          >
            <div class="node-topline">
              <span class="node-index">{{ index + 1 }}</span>
              <small>{{ node.nodeType.toUpperCase() }}</small>
            </div>
            <strong>{{ node.name }}</strong>
            <span>{{ modelName(node.modelCode || defaultModelCode) }}</span>
            <em>{{ toolSummary(node) }}</em>
            <div class="node-actions">
              <button data-node-action type="button" @click.stop="startLinkFrom(node)">连线</button>
              <button data-node-action type="button" @click.stop="addSelfLoop(node)">自循环</button>
            </div>
          </div>
        </div>

        <div class="graph-footer">
          <div>
            <span>画布协议</span>
            <code>mode={{ graph.mode }} / root={{ graph.rootNodeId || '未识别' }}</code>
          </div>
          <div class="edge-list">
            <button
              v-for="edge in graph.edges"
              :key="edge.edgeId"
              class="edge-chip"
              type="button"
              title="删除这条边"
              @click="removeEdge(edge.edgeId)"
            >
              {{ nodeName(edge.sourceNodeId) }} → {{ nodeName(edge.targetNodeId) }}
            </button>
            <span v-if="graph.edges.length === 0">暂无箭头，单节点默认串行执行。</span>
          </div>
        </div>
      </main>

      <aside class="node-panel card">
        <div class="card__body" v-if="activeNode">
          <SectionHeader title="节点属性" description="节点级模型优先于工作流默认模型；工具由 ToolGateway 按当前用户权限自动加载。" :level="2" />
          <div class="form-grid">
            <div class="field">
              <label>节点名称</label>
              <input v-model="activeNode.name" class="input" />
            </div>
            <div class="field">
              <label>节点描述</label>
              <input v-model="activeNode.description" class="input" />
            </div>
            <div class="field">
              <label>节点模型</label>
              <select v-model="activeNode.modelCode" class="select">
                <option value="">跟随工作流默认</option>
                <option v-for="model in modelOptions" :key="model.value" :value="model.value">
                  {{ model.label }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>节点提示词</label>
              <textarea v-model="activeNode.instruction" class="textarea" />
            </div>
            <template v-if="graph.workflowKind === 'INTELLIGENT'">
              <div class="routing-tool-card">
                <strong>智能路由工具 · 锁定能力</strong>
                <code>select_workflow_route(routeKey, reason)</code>
                <span>运行时按当前业务出边动态生成 route key，不能作为普通工具删除。裁决后服务端仍追加兼容 marker，但前端只消费 ROUTE_DECIDED。</span>
                <div v-if="businessRoutePreview.length" class="routing-tool-card__routes">
                  <span v-for="route in businessRoutePreview" :key="route">{{ route }}</span>
                </div>
              </div>
              <label class="rag-tool-toggle">
                <input v-model="activeNode.ragToolEnabled" type="checkbox" />
                <span><strong>允许调用知识库工具</strong><small>运行时仍要求会话已开启 RAG 且目标存在有效知识库绑定。</small></span>
              </label>
              <div class="field">
                <label>路由指令</label>
                <textarea v-model="activeNode.routeInstruction" class="textarea" placeholder="说明何时调用 select_workflow_route" />
              </div>
              <div class="field">
                <label>单节点最大访问次数</label>
                <input v-model.number="activeNode.maxVisits" class="input" type="number" min="1" max="50" />
              </div>
              <div class="field">
                <label>允许路由目标</label>
                <div class="route-targets">
                  <label v-for="node in graph.nodes.filter(item => item.nodeId !== activeNode?.nodeId)" :key="node.nodeId">
                    <input :checked="activeNode.allowedTargetNodeIds?.includes(node.nodeId)" type="checkbox" @change="toggleAllowedTarget(node.nodeId)" /> {{ node.name }}
                  </label>
                  <label><input :checked="activeNode.allowedTargetNodeIds?.includes('END')" type="checkbox" @change="toggleAllowedTarget('END')" /> END</label>
                </div>
                <button v-if="!outgoingEdges.some(edge => edge.targetNodeId === 'END')" class="button button--soft" type="button" @click="addEndRoute">添加 END 终态路由</button>
              </div>
              <div v-for="edge in outgoingEdges" :key="edge.edgeId" class="route-edge-editor">
                <strong>→ {{ nodeName(edge.targetNodeId) }}</strong>
                <select v-model="edge.routeType" class="select">
                  <option value="DEFAULT">DEFAULT 兜底</option><option value="FIXED">FIXED</option><option value="SUCCESS">SUCCESS</option>
                  <option value="FAILURE">FAILURE</option><option value="EXPRESSION">EXPRESSION</option>
                  <option value="NODE_SUGGESTION">NODE_SUGGESTION</option><option value="AI_ROUTER">AI_ROUTER</option>
                </select>
                <template v-if="edge.routeType === 'NODE_SUGGESTION' || edge.routeType === 'AI_ROUTER'">
                  <input v-model="edge.routeKey" class="input" placeholder="路由键，例如：账务" />
                  <input :value="routeAliasesText(edge)" class="input" placeholder="受控别名，例如：billing, invoice" @input="updateRouteAliases(edge, $event)" />
                  <small>支持中文；别名仅做标准化后的精确匹配，不从回答正文猜测。</small>
                </template>
                <input v-if="edge.routeType === 'EXPRESSION'" v-model="edge.conditionExpression" class="input" placeholder="output contains '关键词'" />
                <input v-model.number="edge.priority" class="input" type="number" min="0" max="999" aria-label="路由优先级" />
              </div>
            </template>
            <div class="field">
              <label>自动加载工具</label>
              <div class="auto-tools">
                <div v-for="tool in toolStore.catalog" :key="`${tool.toolType}-${tool.toolId}`" class="auto-tool">
                  <strong>{{ tool.toolName }}</strong>
                  <span>{{ tool.toolType }} · {{ tool.version || '未发布' }}</span>
                </div>
                <small v-if="toolStore.catalog.length === 0">当前没有已发布且有权限的工具；后续发布后无需重建工作流，下轮运行自动可见。</small>
              </div>
            </div>
            <div class="field" v-if="hasSelfLoop(activeNode.nodeId)">
              <label>最大循环次数</label>
              <input v-model.number="activeNode.maxIterations" class="input" min="1" max="20" type="number" />
              <small>这个节点存在指向自身的箭头，会按该次数作为循环上限。</small>
            </div>
            <div class="field">
              <label>工作流说明</label>
              <textarea v-model="workflowDescription" class="textarea textarea--compact" />
            </div>
          </div>
        </div>
        <div class="card__body" v-else>
          <SectionHeader title="节点属性" description="请选择一个节点进行编辑。" :level="2" />
        </div>
      </aside>
    </section>

    <p
      v-if="workflowStore.errorMessage || workflowStore.operationMessage"
      :class="['operation-notice', { 'operation-notice--error': workflowStore.errorMessage }]"
      role="status"
      aria-live="polite"
    >
      {{ workflowStore.errorMessage || workflowStore.operationMessage }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import {
  WORKFLOW_TEMPLATES,
  cloneWorkflowTemplateGraph,
  workflowTemplateById,
} from '@/domain/workflow-templates';
import { useChatStore } from '@/stores/chat';
import {
  createDefaultLlmNode,
  createDefaultWorkflowGraph,
  useWorkflowStore,
} from '@/stores/workflow';
import { useToolStore } from '@/stores/tools';
import type { WorkflowTemplateCategory, WorkflowTemplateKind } from '@/domain/workflow-templates';
import type { WorkflowEdge, WorkflowGraph, WorkflowNode } from '@/types/api';

interface DragState {
  nodeId: string;
  offsetX: number;
  offsetY: number;
}

interface RenderedEdge extends WorkflowEdge {
  path: string;
  label: string;
  labelX: number;
  labelY: number;
  selfLoop: boolean;
}

const NODE_WIDTH = 236;
const NODE_HEIGHT = 150;
const CANVAS_WIDTH = 1160;
const CANVAS_HEIGHT = 660;
const workflowStore = useWorkflowStore();
const chatStore = useChatStore();
const toolStore = useToolStore();
const canvasRef = ref<HTMLElement | null>(null);
const newWorkflowName = ref('企业智能体工作流');
const workflowName = ref('');
const workflowDescription = ref('');
const defaultModelCode = ref('deepseek-v4-flash');
const visibility = ref('private');
const selectedNodeId = ref('');
const linkingSourceId = ref('');
const dragState = ref<DragState | null>(null);
const dragMoved = ref(false);
const graph = ref<WorkflowGraph>(createDefaultWorkflowGraph());
const templateCategoryFilter = ref<'ALL' | WorkflowTemplateCategory>('ALL');
const templateKindFilter = ref<'ALL' | WorkflowTemplateKind>('ALL');
const selectedTemplateId = ref(WORKFLOW_TEMPLATES[0]?.id || '');
const savedDraftFingerprint = ref('');
const canvasSize = { width: CANVAS_WIDTH, height: CANVAS_HEIGHT };

const modelOptions = computed(() => {
  return workflowStore.options.models.length > 0
    ? workflowStore.options.models
    : [
        { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
        { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
      ];
});

const activeNode = computed(() => graph.value.nodes.find((node) => node.nodeId === selectedNodeId.value));
const outgoingEdges = computed(() => graph.value.edges.filter((edge) => edge.sourceNodeId === selectedNodeId.value));
const businessRoutePreview = computed(() => outgoingEdges.value
  .filter((edge) => edge.routeType === 'AI_ROUTER' || edge.routeType === 'NODE_SUGGESTION')
  .map((edge) => `${edge.routeKey || '未设置 route key'} → ${nodeName(edge.targetNodeId)}`));
const derivedMode = computed(() => inferGraphMode(graph.value));
const modeReason = computed(() => inferModeReason(graph.value));
const renderedEdges = computed(() => graph.value.edges.map(toRenderedEdge).filter(Boolean) as RenderedEdge[]);
const filteredTemplates = computed(() => WORKFLOW_TEMPLATES.filter((template) => {
  const categoryMatches = templateCategoryFilter.value === 'ALL' || template.category === templateCategoryFilter.value;
  const kindMatches = templateKindFilter.value === 'ALL' || template.workflowKind === templateKindFilter.value;
  return categoryMatches && kindMatches;
}));
const selectedTemplate = computed(() => workflowTemplateById(selectedTemplateId.value));
const hasUnsavedChanges = computed(() => Boolean(savedDraftFingerprint.value)
  && savedDraftFingerprint.value !== currentDraftFingerprint());

watch(
  () => workflowStore.detail,
  (detail) => {
    if (detail) {
      syncFromDetail();
    }
  },
);

watch(filteredTemplates, (templates) => {
  if (!templates.some((template) => template.id === selectedTemplateId.value)) {
    selectedTemplateId.value = templates[0]?.id || '';
  }
});

onMounted(async () => {
  // 以初始画布为基线，新建态下的未保存修改也能在载入模板前被识别。
  savedDraftFingerprint.value = currentDraftFingerprint();
  await Promise.all([workflowStore.loadOptions(), workflowStore.loadWorkflows(), toolStore.loadCatalog()]);
  if (workflowStore.activeWorkflowId) {
    await workflowStore.loadDetail(workflowStore.activeWorkflowId);
  }
  if (!selectedNodeId.value && graph.value.nodes.length > 0) {
    selectedNodeId.value = graph.value.nodes[0].nodeId;
  }
});

onBeforeUnmount(() => {
  stopDrag();
});

/**
 * 重新加载页面数据；无参数；刷新选项、列表和当前详情。
 */
async function reload() {
  await Promise.all([workflowStore.loadOptions(), workflowStore.loadWorkflows(), toolStore.loadCatalog()]);
  if (workflowStore.activeWorkflowId) {
    await workflowStore.loadDetail(workflowStore.activeWorkflowId);
  }
}

/**
 * 创建新工作流；无参数；创建后刷新列表并加载新看板。
 */
async function createNewWorkflow() {
  const name = newWorkflowName.value.trim() || '企业智能体工作流';
  const detail = await workflowStore.create({
    workflowName: name,
    description: '通过可视化编排创建的数据库工作流',
    defaultModelCode: defaultModelCode.value,
    visibility: visibility.value,
  });
  if (detail) {
    syncFromDetail();
  }
}

/**
 * 选择工作流；参数是工作流 ID；加载对应详情。
 */
async function selectWorkflow(workflowId: string) {
  if (workflowStore.writing) {
    return;
  }
  await workflowStore.loadDetail(workflowId);
}

/**
 * 软删除当前工作流；无参数；确认成功后切换到下一项并刷新运行列表。
 */
async function removeWorkflow() {
  const workflow = workflowStore.activeWorkflow;
  if (!workflow || !window.confirm(`确定删除工作流“${workflow.workflowName}”吗？这是可审计软删除，历史会话和运行记录会保留。`)) {
    return;
  }
  const removed = await workflowStore.remove(workflow.workflowId);
  if (!removed) {
    return;
  }
  if (workflowStore.detail) {
    syncFromDetail();
  } else {
    workflowName.value = '';
    workflowDescription.value = '';
    graph.value = createDefaultWorkflowGraph(defaultModelCode.value);
    selectedNodeId.value = graph.value.nodes[0]?.nodeId || '';
  }
  try {
    await chatStore.loadAgents();
  } catch {
    workflowStore.errorMessage = '工作流已删除，但运行目标缓存刷新失败，进入聊天页后请手动刷新';
  }
}

/**
 * 从后端详情同步表单；无参数；更新画布和当前节点。
 */
function syncFromDetail() {
  const detail = workflowStore.detail;
  if (!detail) {
    return;
  }
  workflowName.value = detail.workflow.workflowName;
  workflowDescription.value = detail.workflow.description || '';
  defaultModelCode.value = detail.workflow.defaultModelCode || 'deepseek-v4-flash';
  visibility.value = detail.workflow.visibility || 'private';
  graph.value = normalizeGraphLayout(detail.graph || createDefaultWorkflowGraph(defaultModelCode.value));
  selectedNodeId.value = graph.value.nodes[0]?.nodeId || '';
  applyGraphShape();
  savedDraftFingerprint.value = currentDraftFingerprint();
}

/**
 * 添加 LLM 节点；无参数；在画布空白位置插入新节点。
 */
function addLlmNode() {
  const node = createDefaultLlmNode(defaultModelCode.value, graph.value.nodes.length + 1);
  const index = graph.value.nodes.length;
  node.x = 120 + (index % 3) * 300;
  node.y = 120 + Math.floor(index / 3) * 190;
  if (graph.value.workflowKind === 'INTELLIGENT') initializeIntelligentNode(node);
  graph.value.nodes.push(node);
  selectedNodeId.value = node.nodeId;
  applyGraphShape();
}

/**
 * 删除当前节点；无参数；至少保留一个节点并清理相关连线。
 */
function removeActiveNode() {
  if (!activeNode.value || graph.value.nodes.length <= 1) {
    return;
  }
  const nodeId = activeNode.value.nodeId;
  graph.value.nodes = graph.value.nodes.filter((node) => node.nodeId !== nodeId);
  graph.value.edges = graph.value.edges.filter((edge) => edge.sourceNodeId !== nodeId && edge.targetNodeId !== nodeId);
  selectedNodeId.value = graph.value.nodes[0]?.nodeId || '';
  applyGraphShape();
}

/**
 * 保存草稿；无参数；把当前画布提交给后端。
 */
async function saveDraft() {
  applyGraphShape();
  const result = await workflowStore.saveDraft({
    workflowName: workflowName.value.trim() || '未命名工作流',
    description: workflowDescription.value,
    defaultModelCode: defaultModelCode.value,
    visibility: visibility.value,
    graph: cloneGraph(graph.value),
  });
  if (result) savedDraftFingerprint.value = currentDraftFingerprint();
  return result;
}

/** 将选中模板深拷贝到本地草稿，不触发后端写入。 */
function loadSelectedTemplate() {
  if (workflowStore.loading || workflowStore.writing) return;
  const template = selectedTemplate.value;
  if (!template) return;
  if (hasUnsavedChanges.value && !window.confirm('当前画布有未保存改动。继续载入模板将覆盖这些本地改动，是否继续？')) {
    return;
  }
  graph.value = normalizeGraphLayout(cloneWorkflowTemplateGraph(template.id));
  selectedNodeId.value = graph.value.rootNodeId || graph.value.nodes[0]?.nodeId || '';
  linkingSourceId.value = '';
  workflowDescription.value = template.description;
  workflowStore.errorMessage = '';
  workflowStore.operationMessage = `已载入模板“${template.name}”，当前仅是未保存草稿。`;
}

/** 生成用于判定未保存改动的稳定草稿快照。 */
function currentDraftFingerprint() {
  return JSON.stringify({
    workflowName: workflowName.value.trim(),
    workflowDescription: workflowDescription.value,
    defaultModelCode: defaultModelCode.value,
    visibility: visibility.value,
    graph: graph.value,
  });
}

/**
 * 发布工作流；无参数；先保存草稿再发布。
 */
async function publish() {
  const saved = await saveDraft();
  if (saved) {
    await workflowStore.publish();
  }
}

/**
 * 切换连线模式；无参数；进入或退出选择起点状态。
 */
function toggleLinking() {
  linkingSourceId.value = linkingSourceId.value ? '' : selectedNodeId.value;
}

/**
 * 从节点开始连线；参数是起点节点；等待用户点击目标节点。
 */
function startLinkFrom(node: WorkflowNode) {
  selectedNodeId.value = node.nodeId;
  linkingSourceId.value = node.nodeId;
}

/**
 * 选择节点或完成连线；参数是节点；根据当前模式更新选中或新增边。
 */
function selectOrConnectNode(node: WorkflowNode) {
  if (dragMoved.value) {
    dragMoved.value = false;
    return;
  }
  if (linkingSourceId.value) {
    createEdge(linkingSourceId.value, node.nodeId);
    linkingSourceId.value = '';
  }
  selectedNodeId.value = node.nodeId;
}

/**
 * 清理连线模式；无参数；点击画布空白时取消连线。
 */
function clearLinking() {
  linkingSourceId.value = '';
}

/**
 * 新增自循环；参数是节点；创建指向自己的边并打开循环次数。
 */
function addSelfLoop(node: WorkflowNode) {
  selectedNodeId.value = node.nodeId;
  createEdge(node.nodeId, node.nodeId);
  node.maxIterations = node.maxIterations || 3;
}

/**
 * 创建连线；参数是起点和终点节点ID；重复边会被忽略。
 */
function createEdge(sourceNodeId: string, targetNodeId: string) {
  if (!sourceNodeId || !targetNodeId) {
    return;
  }
  const exists = graph.value.edges.some((edge) => edge.sourceNodeId === sourceNodeId && edge.targetNodeId === targetNodeId);
  if (exists) {
    applyGraphShape();
    return;
  }
  const existingOutgoing = graph.value.edges.filter((edge) => edge.sourceNodeId === sourceNodeId);
  graph.value.edges.push({
    edgeId: `edge_${sourceNodeId}_${targetNodeId}_${Date.now()}`,
    sourceNodeId,
    targetNodeId,
    routeType: graph.value.workflowKind === 'INTELLIGENT' ? (existingOutgoing.length === 0 ? 'DEFAULT' : 'AI_ROUTER') : undefined,
    routeAliases: [],
    routeKey: graph.value.workflowKind === 'INTELLIGENT' && existingOutgoing.length > 0 ? `route_${existingOutgoing.length + 1}` : undefined,
    priority: existingOutgoing.length,
  });
  const source = findNode(sourceNodeId);
  if (graph.value.workflowKind === 'INTELLIGENT' && source) {
    source.allowedTargetNodeIds = [...new Set([...(source.allowedTargetNodeIds || []), targetNodeId])];
    source.defaultTargetNodeId ||= targetNodeId;
  }
  applyGraphShape();
}

/**
 * 删除连线；参数是连线ID；删除后重新推导模式。
 */
function removeEdge(edgeId: string) {
  graph.value.edges = graph.value.edges.filter((edge) => edge.edgeId !== edgeId);
  applyGraphShape();
}

/**
 * 开始拖动节点；参数是指针事件和节点；记录拖动偏移。
 */
function startDrag(event: PointerEvent, node: WorkflowNode) {
  if ((event.target as HTMLElement).closest('[data-node-action]')) {
    return;
  }
  selectedNodeId.value = node.nodeId;
  const rect = canvasRef.value?.getBoundingClientRect();
  if (!rect) {
    return;
  }
  dragMoved.value = false;
  dragState.value = {
    nodeId: node.nodeId,
    offsetX: event.clientX - rect.left - safeNumber(node.x),
    offsetY: event.clientY - rect.top - safeNumber(node.y),
  };
  window.addEventListener('pointermove', onDragMove);
  window.addEventListener('pointerup', stopDrag);
  window.addEventListener('pointercancel', stopDrag);
}

/**
 * 拖动节点中；参数是指针事件；实时更新节点坐标。
 */
function onDragMove(event: PointerEvent) {
  const state = dragState.value;
  const rect = canvasRef.value?.getBoundingClientRect();
  if (!state || !rect) {
    return;
  }
  const node = graph.value.nodes.find((item) => item.nodeId === state.nodeId);
  if (!node) {
    return;
  }
  const nextX = event.clientX - rect.left - state.offsetX;
  const nextY = event.clientY - rect.top - state.offsetY;
  node.x = clamp(nextX, 24, CANVAS_WIDTH - NODE_WIDTH - 24);
  node.y = clamp(nextY, 24, CANVAS_HEIGHT - NODE_HEIGHT - 24);
  dragMoved.value = true;
}

/**
 * 停止拖动节点；无参数；释放全局事件监听。
 */
function stopDrag() {
  dragState.value = null;
  window.removeEventListener('pointermove', onDragMove);
  window.removeEventListener('pointerup', stopDrag);
  window.removeEventListener('pointercancel', stopDrag);
  window.setTimeout(() => {
    dragMoved.value = false;
  }, 0);
}

/**
 * 自动排布画布；无参数；把节点按阅读顺序重新摆放。
 */
function autoLayout() {
  graph.value.nodes.forEach((node, index) => {
    node.x = 120 + (index % 3) * 310;
    node.y = 120 + Math.floor(index / 3) * 200;
  });
  applyGraphShape();
}

/**
 * 应用画布结构；无参数；按箭头推导模式和根节点。
 */
function applyGraphShape() {
  graph.value.edges = graph.value.edges.filter((edge) => nodeExists(edge.sourceNodeId) && (edge.targetNodeId === 'END' || nodeExists(edge.targetNodeId)));
  graph.value.mode = inferGraphMode(graph.value);
  graph.value.rootNodeId = inferRootNodeId(graph.value);
}

function setWorkflowKind(kind: 'STATIC' | 'INTELLIGENT') {
  graph.value.workflowKind = kind;
  if (kind === 'INTELLIGENT') {
    graph.value.routingProtocolVersion = 'TOOL_V2';
    graph.value.maxSteps ||= 40;
    graph.value.tokenBudget ||= 128000;
    graph.value.nodes.forEach(initializeIntelligentNode);
    const sources = new Set<string>();
    graph.value.edges.forEach((edge, index) => {
      edge.routeType ||= sources.has(edge.sourceNodeId) ? 'AI_ROUTER' : 'DEFAULT';
      edge.priority ??= index;
      sources.add(edge.sourceNodeId);
      const source = findNode(edge.sourceNodeId);
      if (source) {
        source.allowedTargetNodeIds = [...new Set([...(source.allowedTargetNodeIds || []), edge.targetNodeId])];
        if (edge.routeType === 'DEFAULT') source.defaultTargetNodeId = edge.targetNodeId;
      }
    });
  } else {
    graph.value.routingProtocolVersion = 'MARKER_V1';
  }
}

function initializeIntelligentNode(node: WorkflowNode) {
  node.enabledStrategies ||= ['FIXED', 'SUCCESS', 'EXPRESSION', 'NODE_SUGGESTION', 'AI_ROUTER', 'DEFAULT'];
  node.allowedTargetNodeIds ||= [];
  node.routeInstruction ||= '完成任务后，如需指定下一节点，调用 select_workflow_route 并传入当前出边允许的 routeKey 和简短 reason。';
  node.maxVisits ||= 3;
}

function routeAliasesText(edge: WorkflowEdge) {
  return (edge.routeAliases || []).join(', ');
}

function updateRouteAliases(edge: WorkflowEdge, event: Event) {
  const input = event.target as HTMLInputElement;
  edge.routeAliases = [...new Set(input.value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean))];
}

function toggleAllowedTarget(targetNodeId: string) {
  if (!activeNode.value) return;
  const targets = new Set(activeNode.value.allowedTargetNodeIds || []);
  targets.has(targetNodeId) ? targets.delete(targetNodeId) : targets.add(targetNodeId);
  activeNode.value.allowedTargetNodeIds = [...targets];
}

function addEndRoute() {
  if (!activeNode.value) return;
  createEdge(activeNode.value.nodeId, 'END');
}

/**
 * 规范化画布布局；参数是画布；返回带坐标和有效边的画布。
 */
function normalizeGraphLayout(value: WorkflowGraph) {
  const next = cloneGraph(value);
  next.routingProtocolVersion ||= 'MARKER_V1';
  next.nodes = (next.nodes || []).map((node, index) => ({
    ...node,
    nodeType: node.nodeType || 'llm',
    mcpIds: node.mcpIds || [],
    skillIds: node.skillIds || [],
    maxIterations: node.maxIterations || 3,
    x: typeof node.x === 'number' ? node.x : 120 + (index % 3) * 300,
    y: typeof node.y === 'number' ? node.y : 120 + Math.floor(index / 3) * 190,
  }));
  next.edges = (next.edges || []).filter((edge) => next.nodes.some((node) => node.nodeId === edge.sourceNodeId)
    && (edge.targetNodeId === 'END' || next.nodes.some((node) => node.nodeId === edge.targetNodeId)))
    .map((edge) => ({ ...edge, routeAliases: edge.routeAliases || [] }));
  next.mode = inferGraphMode(next);
  next.rootNodeId = inferRootNodeId(next);
  return next;
}

/**
 * 推导编排模式；参数是画布；返回 sequential/parallel/loop。
 */
function inferGraphMode(value: WorkflowGraph): WorkflowGraph['mode'] {
  if (value.edges.some((edge) => edge.sourceNodeId === edge.targetNodeId)) {
    return 'loop';
  }
  const degree = degreeMap(value);
  const hasParallelShape = [...degree.in.values()].some((count) => count > 1) || [...degree.out.values()].some((count) => count > 1);
  return hasParallelShape ? 'parallel' : 'sequential';
}

/**
 * 解释模式来源；参数是画布；返回用户可读说明。
 */
function inferModeReason(value: WorkflowGraph) {
  if (value.edges.some((edge) => edge.sourceNodeId === edge.targetNodeId)) {
    return '发现自循环箭头';
  }
  const degree = degreeMap(value);
  if ([...degree.in.values()].some((count) => count > 1)) {
    return '发现多个入度';
  }
  if ([...degree.out.values()].some((count) => count > 1)) {
    return '发现多个出度';
  }
  return value.edges.length > 0 ? '普通有向链路' : '单节点默认串行';
}

/**
 * 推导根节点；参数是画布；返回第一个入度为 0 的节点ID。
 */
function inferRootNodeId(value: WorkflowGraph) {
  const degree = degreeMap(value);
  const root = value.nodes.find((node) => (degree.in.get(node.nodeId) || 0) === 0);
  return root?.nodeId || value.nodes[0]?.nodeId || '';
}

/**
 * 计算节点入度和出度；参数是画布；返回度数 Map。
 */
function degreeMap(value: WorkflowGraph) {
  const inDegree = new Map<string, number>();
  const outDegree = new Map<string, number>();
  value.nodes.forEach((node) => {
    inDegree.set(node.nodeId, 0);
    outDegree.set(node.nodeId, 0);
  });
  value.edges.forEach((edge) => {
    if (edge.sourceNodeId === edge.targetNodeId) {
      return;
    }
    inDegree.set(edge.targetNodeId, (inDegree.get(edge.targetNodeId) || 0) + 1);
    outDegree.set(edge.sourceNodeId, (outDegree.get(edge.sourceNodeId) || 0) + 1);
  });
  return { in: inDegree, out: outDegree };
}

/**
 * 转成渲染边；参数是边；返回 SVG 路径信息。
 */
function toRenderedEdge(edge: WorkflowEdge): RenderedEdge | null {
  const source = findNode(edge.sourceNodeId);
  const target = findNode(edge.targetNodeId);
  if (!source || !target) {
    return null;
  }
  const sourceX = safeNumber(source.x);
  const sourceY = safeNumber(source.y);
  const targetX = safeNumber(target.x);
  const targetY = safeNumber(target.y);
  if (edge.sourceNodeId === edge.targetNodeId) {
    const startX = sourceX + NODE_WIDTH - 16;
    const startY = sourceY + 42;
    return {
      ...edge,
      selfLoop: true,
      path: `M ${startX} ${startY} C ${startX + 118} ${startY - 72}, ${startX + 118} ${startY + 112}, ${startX - 6} ${startY + 86}`,
      label: '循环',
      labelX: startX + 62,
      labelY: startY + 10,
    };
  }
  const startX = sourceX + NODE_WIDTH;
  const startY = sourceY + NODE_HEIGHT / 2;
  const endX = targetX;
  const endY = targetY + NODE_HEIGHT / 2;
  const curve = Math.max(90, Math.abs(endX - startX) / 2);
  return {
    ...edge,
    selfLoop: false,
    path: `M ${startX} ${startY} C ${startX + curve} ${startY}, ${endX - curve} ${endY}, ${endX} ${endY}`,
    label: graph.value.mode === 'parallel' ? '并行' : '下一步',
    labelX: (startX + endX) / 2,
    labelY: (startY + endY) / 2 - 10,
  };
}

/**
 * 节点样式；参数是节点；返回定位样式。
 */
function nodeStyle(node: WorkflowNode) {
  return {
    width: `${NODE_WIDTH}px`,
    transform: `translate(${safeNumber(node.x)}px, ${safeNumber(node.y)}px)`,
  };
}

/**
 * 判断节点是否存在；参数是节点ID；返回布尔值。
 */
function nodeExists(nodeId: string) {
  return graph.value.nodes.some((node) => node.nodeId === nodeId);
}

/**
 * 查询节点；参数是节点ID；返回节点对象。
 */
function findNode(nodeId: string) {
  return graph.value.nodes.find((node) => node.nodeId === nodeId);
}

/**
 * 判断节点是否有自循环；参数是节点ID；返回布尔值。
 */
function hasSelfLoop(nodeId: string) {
  return graph.value.edges.some((edge) => edge.sourceNodeId === nodeId && edge.targetNodeId === nodeId);
}

/**
 * 深拷贝画布；参数是画布；返回新对象。
 */
function cloneGraph(value: WorkflowGraph) {
  return JSON.parse(JSON.stringify(value)) as WorkflowGraph;
}

/**
 * 模型展示名；参数是模型编码；返回展示标签。
 */
function modelName(modelCode: string) {
  return modelOptions.value.find((model) => model.value === modelCode)?.label || modelCode;
}

/**
 * 节点展示名；参数是节点ID；返回节点名称。
 */
function nodeName(nodeId: string) {
  return graph.value.nodes.find((node) => node.nodeId === nodeId)?.name || nodeId;
}

/**
 * 模式展示名；参数是模式；返回中文名称。
 */
function modeLabel(mode: WorkflowGraph['mode']) {
  const labels = {
    sequential: '串行编排',
    parallel: '并行编排',
    loop: '循环编排',
  };
  return labels[mode];
}

/**
 * 状态展示名；参数是状态；返回中文名称。
 */
function statusLabel(status: string) {
  const labels: Record<string, string> = {
    draft: '草稿',
    published: '已发布',
    disabled: '已停用',
    archived: '已归档',
  };
  return labels[status] || status;
}

/**
 * 工具摘要；参数是节点；返回工具数量说明。
 */
function toolSummary(node: WorkflowNode) {
  const legacyCount = (node.mcpIds?.length || 0) + (node.skillIds?.length || 0);
  if (toolStore.catalog.length > 0) {
    return `${toolStore.catalog.length} 个自动工具`;
  }
  return legacyCount > 0 ? `${legacyCount} 个旧绑定` : '自动工具';
}

/**
 * 安全数字；参数是候选值；返回可用于坐标的数字。
 */
function safeNumber(value: number | undefined) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * 限制数值范围；参数是值、最小值和最大值；返回范围内数值。
 */
function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}
</script>

<style scoped>
.workflow-kind-tabs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-bottom:14px}.workflow-kind-tabs button{display:grid;gap:4px;text-align:left;padding:14px 16px;border:1px solid var(--line);border-radius:14px;background:var(--surface);cursor:pointer}.workflow-kind-tabs button.active{border-color:#6366f1;box-shadow:0 0 0 2px rgb(99 102 241/.12)}.workflow-kind-tabs span{color:var(--muted);font-size:12px}.intelligent-budget{display:flex;align-items:end;gap:16px;padding:14px;margin-bottom:14px}.intelligent-budget label{display:grid;gap:6px;font-size:12px}.intelligent-budget span{color:var(--muted);font-size:12px}.routing-tool-card{display:grid;gap:7px;padding:12px;border:1px solid rgb(99 102 241/.35);border-radius:12px;background:rgb(99 102 241/.06)}.routing-tool-card code{font-size:12px}.routing-tool-card span,.rag-tool-toggle small{color:var(--muted);font-size:12px}.routing-tool-card__routes{display:flex;flex-wrap:wrap;gap:6px}.routing-tool-card__routes span{padding:4px 7px;border-radius:999px;background:var(--surface)}.rag-tool-toggle{display:flex;align-items:flex-start;gap:9px;padding:10px;border:1px solid var(--line);border-radius:10px}.rag-tool-toggle span{display:grid;gap:3px}.route-targets{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.route-edge-editor{display:grid;gap:7px;padding:10px;border:1px solid var(--line);border-radius:10px}.route-edge-editor strong{font-size:12px}@media(max-width:800px){.workflow-kind-tabs{grid-template-columns:1fr}.intelligent-budget{align-items:stretch;flex-direction:column}}
.workflow-kind-tabs {
  gap: 1px;
  overflow: hidden;
  padding: 1px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--line);
}

.workflow-kind-tabs button {
  min-height: 58px;
  padding: 10px 12px;
  border: 0;
  border-radius: 6px;
  background: var(--surface);
}

.workflow-kind-tabs button.active {
  color: var(--accent-deep);
  border: 0;
  background: var(--accent-soft);
  box-shadow: inset 3px 0 0 var(--accent);
}

.workflow-kind-tabs span { font-size: 10px; }

.routing-tool-card {
  border-color: rgba(214, 83, 50, .28);
  border-radius: 7px;
  background: rgba(214, 83, 50, .06);
}

.routing-tool-card__routes span {
  border-radius: 4px;
}
.workflow-template-library {
  display: grid;
  gap: 14px;
  padding: 16px;
}

.template-library__intro,
.template-library__controls,
.template-library__detail,
.template-library__meta {
  display: flex;
  align-items: center;
}

.template-library__intro,
.template-library__detail {
  justify-content: space-between;
  gap: 18px;
}

.template-library__intro h2,
.template-library__intro p,
.template-library__detail p {
  margin: 0;
}

.template-library__intro h2 {
  margin-top: 2px;
  font-size: 17px;
}

.template-library__intro p,
.template-library__detail p,
.template-library__meta {
  color: var(--muted);
  font-size: 12px;
}

.template-library__eyebrow {
  color: var(--accent);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.template-library__intro > strong {
  min-width: max-content;
  padding: 6px 9px;
  color: var(--accent-deep);
  border-radius: 999px;
  background: var(--accent-soft);
  font-size: 12px;
}

.template-library__controls {
  align-items: end;
  gap: 10px;
}

.template-library__controls label {
  display: grid;
  gap: 5px;
  min-width: 150px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
}

.template-library__picker {
  flex: 1;
}

.template-library__detail {
  align-items: flex-start;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.template-library__detail > div:first-child {
  display: grid;
  gap: 5px;
}

.template-library__meta {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 48%;
}

.template-library__meta span,
.template-badge {
  padding: 4px 7px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-muted);
}

.template-badge {
  width: max-content;
  color: var(--accent-deep);
  font-size: 10px;
  font-weight: 900;
}

.template-badge--test {
  color: #8a5a10;
  background: #fff7df;
}

.template-library__meta .template-dependency {
  color: #73551e;
  border-color: rgba(173, 117, 37, 0.24);
  background: rgba(255, 247, 223, 0.72);
}

.workflow-page {
  display: grid;
  gap: 16px;
}

.operation-notice {
  margin: 0;
  padding: 10px 12px;
  color: var(--success);
  border: 1px solid rgba(45, 107, 79, 0.18);
  border-radius: var(--radius-sm);
  background: var(--success-soft);
  font-size: 13px;
}

.operation-notice--error {
  color: var(--danger);
  border-color: rgba(155, 62, 62, 0.18);
  background: var(--danger-soft);
}

.workflow-shell {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}

.mini-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 900;
}

.mini-title strong {
  color: var(--accent);
}

.create-box {
  display: grid;
  gap: 8px;
  margin-bottom: 10px;
}

.workflow-items {
  display: grid;
  gap: 4px;
}

.workflow-item {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 10px;
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface-muted);
  cursor: pointer;
}

.workflow-item--active {
  border-color: rgba(214, 83, 50, 0.42);
  background: var(--accent-soft);
}

.workflow-item span,
.empty-card,
.field small {
  color: var(--muted);
  font-size: 12px;
}

.workflow-main {
  overflow: hidden;
}

.workflow-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 190px 210px;
  gap: 10px;
  padding: 14px;
  border-bottom: 1px solid var(--line);
}

.workflow-name-field {
  min-width: 0;
}

.mode-summary {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid rgba(214, 83, 50, 0.2);
  border-radius: 9px;
  background: var(--accent-soft);
}

.mode-summary span,
.mode-summary small {
  color: var(--muted);
  font-size: 12px;
}

.mode-summary strong {
  color: var(--accent-deep);
}

.toolbar-actions {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.button--dark {
  color: #fffaf0;
  border-color: var(--accent-deep);
  background: var(--accent-deep);
}

.canvas-help {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  color: var(--muted);
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}

.canvas-help strong {
  flex: 0 0 auto;
  color: var(--ink-soft);
}

.workflow-canvas {
  position: relative;
  min-height: 660px;
  overflow: auto;
  background-color: var(--surface);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24'%3E%3Ccircle cx='1' cy='1' r='.75' fill='%23c8cec7'/%3E%3C/svg%3E");
  background-size: 24px 24px;
}

.workflow-canvas::after {
  position: absolute;
  right: 16px;
  bottom: 14px;
  color: rgba(24, 32, 42, 0.18);
  content: "WORKFLOW CANVAS";
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.18em;
  pointer-events: none;
}

.edge-layer {
  position: absolute;
  inset: 0;
  width: 1160px;
  height: 660px;
  overflow: visible;
  pointer-events: none;
}

.edge-layer marker path {
  fill: var(--accent);
}

.edge-path {
  fill: none;
  stroke: rgba(214, 83, 50, 0.78);
  stroke-linecap: round;
  stroke-width: 2.5;
}

.edge-path--loop {
  stroke: rgba(173, 117, 37, 0.84);
  stroke-dasharray: 8 6;
}

.edge-label {
  fill: rgba(24, 32, 42, 0.54);
  font-size: 12px;
  font-weight: 900;
  paint-order: stroke;
  stroke: rgba(255, 253, 248, 0.92);
  stroke-width: 5px;
  text-anchor: middle;
}

.canvas-node {
  position: absolute;
  top: 0;
  left: 0;
  display: grid;
  gap: 6px;
  min-height: 150px;
  padding: 12px;
  border: 1px solid rgba(24, 32, 42, 0.14);
  border-radius: 12px;
  background: var(--surface);
  box-shadow: none;
  cursor: grab;
  touch-action: none;
  user-select: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.canvas-node:active {
  cursor: grabbing;
}

.canvas-node--active {
  border-color: rgba(214, 83, 50, 0.72);
  box-shadow: 0 0 0 3px rgba(214, 83, 50, 0.1);
}

.canvas-node--linking {
  background: rgba(220, 233, 234, 0.94);
}

.canvas-node--loop {
  border-color: rgba(173, 117, 37, 0.5);
}

.node-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.node-topline small {
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.node-index {
  display: grid;
  width: 30px;
  height: 30px;
  color: #fffaf0;
  place-items: center;
  border-radius: 7px;
  background: var(--accent);
  font-weight: 900;
}

.canvas-node strong {
  color: var(--ink);
  font-size: 15px;
}

.canvas-node span {
  color: var(--accent);
  font-size: 12px;
  font-weight: 900;
}

.canvas-node em {
  color: var(--muted);
  font-size: 12px;
  font-style: normal;
}

.node-actions {
  display: flex;
  gap: 6px;
  margin-top: 2px;
}

.node-actions button {
  flex: 1;
  padding: 6px 7px;
  color: var(--accent-deep);
  border: 1px solid rgba(214, 83, 50, 0.2);
  border-radius: 7px;
  background: var(--accent-soft);
  cursor: pointer;
  font-size: 12px;
  font-weight: 900;
}

.graph-footer {
  display: grid;
  gap: 8px;
  padding: 10px 14px;
  color: var(--muted);
  border-top: 1px solid var(--line);
  font-size: 12px;
}

.graph-footer > div:first-child {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.graph-footer code {
  color: var(--ink-soft);
}

.edge-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.edge-chip {
  padding: 5px 8px;
  color: var(--accent-deep);
  border: 1px solid rgba(214, 83, 50, 0.2);
  border-radius: 6px;
  background: var(--accent-soft);
  cursor: pointer;
  font-size: 12px;
  font-weight: 900;
}

.select--multi {
  min-height: 92px;
  padding: 10px 12px;
}

.auto-tools {
  display: grid;
  gap: 4px;
}

.auto-tool {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-muted);
}

.auto-tool span,
.auto-tools small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.textarea--compact {
  min-height: 86px;
}

@media (max-width: 1280px) {
  .workflow-shell {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .template-library__controls,
  .template-library__detail {
    align-items: stretch;
    flex-direction: column;
  }

  .template-library__controls label,
  .template-library__meta {
    width: 100%;
    max-width: none;
  }

  .template-library__meta {
    justify-content: flex-start;
  }

  .workflow-toolbar {
    grid-template-columns: 1fr;
  }

  .canvas-help,
  .graph-footer > div:first-child {
    display: grid;
  }
}
</style>
