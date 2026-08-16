<template>
  <button v-if="pending.length && !open" ref="launcherRef" class="approval-launcher" type="button" @click="show">
    <ShieldAlert :size="17" aria-hidden="true" />
    <span class="approval-launcher__count">{{ pending.length }}</span>
    <span>待审批操作</span>
  </button>

  <p v-if="feedbackMessage && !open" class="approval-toast" role="status" aria-live="polite">
    <CheckCircle2 :size="16" aria-hidden="true" />{{ feedbackMessage }}
  </p>

  <div v-if="open && current" class="approval-backdrop" @click.self="dismiss">
    <section ref="dialogRef" class="approval-dialog" role="dialog" aria-modal="true"
             aria-labelledby="approval-title" aria-describedby="approval-description" tabindex="-1"
             @keydown.esc.prevent="dismiss" @keydown.tab="trapFocus">
      <header class="approval-head">
        <div class="approval-head__identity">
          <span class="approval-emblem"><ShieldCheck :size="21" aria-hidden="true" /></span>
          <div>
            <span class="approval-kicker">工具调用审批 · {{ activeIndex + 1 }}/{{ pending.length }}</span>
            <h2 id="approval-title">{{ presentation.title }}</h2>
          </div>
        </div>
        <div class="approval-head__actions">
          <button type="button" :disabled="activeIndex === 0" aria-label="上一项审批" title="上一项" @click="previousApproval">
            <ChevronLeft :size="18" aria-hidden="true" />
          </button>
          <button type="button" :disabled="activeIndex >= pending.length - 1" aria-label="下一项审批" title="下一项" @click="nextApproval">
            <ChevronRight :size="18" aria-hidden="true" />
          </button>
          <button type="button" aria-label="稍后处理审批" title="稍后处理" @click="dismiss">
            <X :size="19" aria-hidden="true" />
          </button>
        </div>
      </header>

      <div class="approval-overview">
        <div class="approval-overview__tags">
          <span class="tool-category"><Wrench :size="13" aria-hidden="true" />{{ presentation.category }}</span>
          <span :class="['risk', `risk--${presentation.tone}`]"><AlertTriangle :size="13" aria-hidden="true" />{{ presentation.risk }}</span>
        </div>
        <p id="approval-description">{{ presentation.description }}</p>
        <dl class="approval-facts">
          <div><dt>申请 Agent</dt><dd>{{ agentName(current.parentAgentId) }}</dd></div>
          <div><dt>工具编码</dt><dd><code>{{ current.toolCode }}</code></dd></div>
        </dl>
      </div>

      <div class="approval-body">
        <section v-if="tasks.length" class="approval-section requested-tasks" aria-labelledby="approval-task-title">
          <div class="section-title">
            <div><span>REQUEST</span><h3 id="approval-task-title">待创建任务</h3></div>
            <strong>{{ tasks.length }} 项</strong>
          </div>
          <article v-for="(task, index) in tasks" :key="String(task.taskId || index)">
            <span class="task-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div>
              <strong>{{ agentName(String(task.agentTemplateId || task.agentId || '')) }}</strong>
              <p>{{ task.instruction || task.input || task.fullContext || '未提供任务说明' }}</p>
              <small v-if="task.taskId">TASK · {{ task.taskId }}</small>
            </div>
          </article>
        </section>

        <section v-else class="approval-section parameter-summary" aria-labelledby="approval-parameter-title">
          <div class="section-title">
            <div><span>INPUT</span><h3 id="approval-parameter-title">调用参数</h3></div>
            <strong>{{ inputSummary.length }} 项</strong>
          </div>
          <dl v-if="inputSummary.length">
            <div v-for="item in inputSummary" :key="item.key">
              <dt>{{ item.key }}<LockKeyhole v-if="item.sensitive" :size="12" aria-label="敏感字段已隐藏" /></dt>
              <dd>{{ item.value || '--' }}</dd>
            </div>
          </dl>
          <p v-else class="empty-parameters">本次调用不包含业务参数。</p>
        </section>

        <section v-if="current.suggestions?.length" class="approval-section approval-suggestions" aria-labelledby="approval-suggestion-title">
          <div class="section-title">
            <div><span>POLICY</span><h3 id="approval-suggestion-title">策略建议</h3></div>
          </div>
          <div class="suggestion-list">
            <button v-for="suggestion in current.suggestions" :key="suggestion" type="button"
                    :class="{ active: draft.comment === suggestion }" @click="draft.comment = suggestion">
              {{ suggestion }}
            </button>
          </div>
        </section>

        <div class="approval-meta">
          <span><Clock3 :size="14" aria-hidden="true" />剩余 <strong>{{ countdown }}</strong>，超时后{{ current.timeoutDecision === 'APPROVE' ? '自动同意' : '自动拒绝' }}</span>
          <button v-if="current.traceId" type="button" @click="copyTrace">
            <Check v-if="traceCopied" :size="14" aria-hidden="true" /><Copy v-else :size="14" aria-hidden="true" />
            {{ traceCopied ? 'Trace 已复制' : `Trace ${compactTrace(current.traceId)}` }}
          </button>
        </div>

        <div v-if="mode === 'replan'" class="approval-editor">
          <label for="approval-comment">重新规划要求</label>
          <textarea id="approval-comment" v-model="draft.comment" placeholder="例如：只保留编码任务，调查任务暂不执行" />
        </div>
        <div v-if="mode === 'edit'" class="approval-editor">
          <label for="approval-json">修改后的调用参数 <span>JSON</span></label>
          <textarea id="approval-json" v-model="draft.amendedInput" class="json-editor" spellcheck="false" />
        </div>
        <div v-if="mode === 'default'" class="advanced-actions">
          <button type="button" @click="openEditor"><SlidersHorizontal :size="14" aria-hidden="true" />修改参数</button>
        </div>
        <p v-if="errorMessage" class="approval-error" role="alert"><CircleAlert :size="15" aria-hidden="true" />{{ errorMessage }}</p>
      </div>

      <footer class="approval-actions">
        <span class="approval-actions__status">{{ submitting ? '正在提交决定…' : `修订 ${current.revision}` }}</span>
        <div>
          <button type="button" class="button button--ghost" :disabled="submitting" @click="reject">
            <X :size="15" aria-hidden="true" />拒绝
          </button>
          <button type="button" class="button button--soft" :disabled="submitting" @click="requestReplan">
            <RotateCcw :size="15" aria-hidden="true" />{{ mode === 'replan' ? '提交重新规划' : '重新规划' }}
          </button>
          <button v-if="mode === 'edit'" type="button" class="button button--primary" :disabled="submitting" @click="submit(current, 'APPROVE_WITH_CHANGES')">
            <Check :size="15" aria-hidden="true" />{{ submitting ? '提交中…' : '按修改后参数批准' }}
          </button>
          <button v-else type="button" class="button button--primary" :disabled="submitting" @click="submit(current, 'APPROVE')">
            <Check :size="15" aria-hidden="true" />{{ submitting ? '提交中…' : `批准 ${tasks.length || 1} 项操作` }}
          </button>
        </div>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import {
  AlertTriangle, Check, CheckCircle2, ChevronLeft, ChevronRight, CircleAlert, Clock3, Copy,
  LockKeyhole, RotateCcw, ShieldAlert, ShieldCheck, SlidersHorizontal, Wrench, X,
} from '@lucide/vue';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';

import { decideToolApproval, queryAgentConfigs, streamToolApprovals } from '@/api/agent';
import { approvalPresentation, summarizeApprovalInput } from '@/domain/tool-governance';
import type { AiAgentConfig, ToolApprovalDecision, ToolApprovalRequest } from '@/types/api';
import { copyText } from '@/utils/clipboard';

type Mode = 'default' | 'edit' | 'replan';

const pending = ref<ToolApprovalRequest[]>([]);
const agents = ref<AiAgentConfig[]>([]);
const open = ref(false);
const activeIndex = ref(0);
const mode = ref<Mode>('default');
const submitting = ref(false);
const errorMessage = ref('');
const feedbackMessage = ref('');
const now = ref(Date.now());
const traceCopied = ref(false);
const dialogRef = ref<HTMLElement | null>(null);
const launcherRef = ref<HTMLButtonElement | null>(null);
const draft = reactive({ comment: '', amendedInput: '' });
let cursor = 0;
let controller: AbortController | undefined;
let reconnectTimer: number | undefined;
let clockTimer: number | undefined;
let feedbackTimer: number | undefined;
let mounted = false;
const decidedApprovalIds = new Set<string>();

const current = computed(() => pending.value[activeIndex.value]);
const tasks = computed<Array<Record<string, unknown>>>(() => Array.isArray(current.value?.requestedInput?.tasks)
  ? current.value.requestedInput.tasks as Array<Record<string, unknown>> : []);
const presentation = computed(() => approvalPresentation(current.value?.toolCode));
const inputSummary = computed(() => summarizeApprovalInput(current.value?.requestedInput));
const countdown = computed(() => {
  const millis = Math.max(0, Date.parse(current.value?.expiresAt || '') - now.value);
  const seconds = Math.ceil(millis / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
});

onMounted(async () => {
  mounted = true;
  try { agents.value = await queryAgentConfigs(); } catch { agents.value = []; }
  connect();
  clockTimer = window.setInterval(() => { now.value = Date.now(); expireItems(); }, 1000);
});

onBeforeUnmount(() => {
  mounted = false;
  controller?.abort();
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (clockTimer) clearInterval(clockTimer);
  if (feedbackTimer) clearTimeout(feedbackTimer);
});

watch(current, (value) => { if (value) resetDraft(value); });

async function connect() {
  if (!mounted || controller && !controller.signal.aborted) return;
  controller = new AbortController();
  const currentController = controller;
  try {
    await streamToolApprovals(cursor, currentController.signal, (item) => {
      cursor = Math.max(cursor, item.sequence);
      if (decidedApprovalIds.has(item.approvalId)) return;
      const index = pending.value.findIndex((value) => value.approvalId === item.approvalId);
      if (index >= 0) pending.value[index] = item; else pending.value.push(item);
      if (pending.value.length === 1) { resetDraft(item); show(); }
    });
    if (!currentController.signal.aborted && mounted) reconnectTimer = window.setTimeout(connect, 500);
  } catch (error) {
    if (!currentController.signal.aborted && mounted) {
      errorMessage.value = error instanceof Error ? error.message : '审批事件流已断开';
      reconnectTimer = window.setTimeout(connect, 2000);
    }
  } finally {
    if (controller === currentController) controller = undefined;
  }
}

function pauseApprovalStream() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = undefined;
  controller?.abort();
  controller = undefined;
}

function resetDraft(item: ToolApprovalRequest) {
  mode.value = 'default';
  draft.comment = '';
  draft.amendedInput = JSON.stringify(item.requestedInput, null, 2);
  errorMessage.value = '';
}

function show() { open.value = true; void nextTick(() => dialogRef.value?.focus()); }
function dismiss() { open.value = false; void nextTick(() => launcherRef.value?.focus()); }
function previousApproval() { if (activeIndex.value > 0) activeIndex.value -= 1; }
function nextApproval() { if (activeIndex.value < pending.value.length - 1) activeIndex.value += 1; }

function expireItems() {
  pending.value = pending.value.filter((item) => Date.parse(item.expiresAt) > now.value);
  if (!pending.value.length) open.value = false;
  activeIndex.value = Math.min(activeIndex.value, Math.max(0, pending.value.length - 1));
}

function agentName(id: string) {
  return agents.value.find((agent) => agent.agentId === id)?.agentName || (id ? `Agent ${id}` : '未指定 Agent');
}

function compactTrace(value: string) { return `${value.slice(0, 8)}…${value.slice(-8)}`; }

async function copyTrace() {
  if (!current.value?.traceId) return;
  try {
    await copyText(current.value.traceId);
    traceCopied.value = true;
    window.setTimeout(() => { traceCopied.value = false; }, 1200);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Trace 复制失败';
  }
}

function openEditor() {
  mode.value = 'edit';
  void nextTick(() => document.querySelector<HTMLTextAreaElement>('#approval-json')?.focus());
}

function requestReplan() {
  if (mode.value !== 'replan') {
    mode.value = 'replan';
    void nextTick(() => document.querySelector<HTMLTextAreaElement>('#approval-comment')?.focus());
    return;
  }
  void submit(current.value!, 'REPLAN');
}

function reject() { void submit(current.value!, 'REJECT'); }

async function submit(item: ToolApprovalRequest, decision: ToolApprovalDecision) {
  if (submitting.value) return;
  errorMessage.value = '';
  submitting.value = true;
  // 先释放长连接，避免 HTTP/1 同域连接槽被 SSE 占满后审批 POST 一直排队。
  pauseApprovalStream();
  try {
    const amendedInput = decision === 'APPROVE_WITH_CHANGES' ? JSON.parse(draft.amendedInput) : undefined;
    await decideToolApproval(item.approvalId, {
      decision, comment: draft.comment, amendedInput, expectedRevision: item.revision,
    });
    decidedApprovalIds.add(item.approvalId);
    pending.value = pending.value.filter((value) => value.approvalId !== item.approvalId);
    activeIndex.value = Math.min(activeIndex.value, Math.max(0, pending.value.length - 1));
    showFeedback(decisionLabel(decision));
    if (!pending.value.length) open.value = false;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '审批提交失败';
  } finally {
    submitting.value = false;
    if (mounted) void connect();
  }
}

function showFeedback(message: string) {
  feedbackMessage.value = message;
  if (feedbackTimer) clearTimeout(feedbackTimer);
  feedbackTimer = window.setTimeout(() => { feedbackMessage.value = ''; }, 2400);
}

function decisionLabel(decision: ToolApprovalDecision) {
  if (decision === 'REJECT') return '已拒绝本次工具调用';
  if (decision === 'REPLAN') return '已请求 Agent 重新规划';
  return decision === 'APPROVE_WITH_CHANGES' ? '已按修改后参数批准' : '已批准本次工具调用';
}

function trapFocus(event: KeyboardEvent) {
  const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled),textarea:not(:disabled),[tabindex]:not([tabindex="-1"])',
  ) || []);
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1)!;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
</script>

<style scoped>
.approval-launcher {
  position: fixed; right: 24px; bottom: 24px; z-index: 45; display: inline-flex; align-items: center; gap: 8px;
  min-height: 46px; padding: 0 15px; border: 1px solid color-mix(in srgb, var(--accent) 38%, var(--line));
  border-radius: 7px; background: var(--ink); color: #fff; box-shadow: 0 12px 32px #17201b35; cursor: pointer;
}
.approval-launcher__count { display: inline-grid; width: 22px; height: 22px; place-items: center; background: var(--accent); font-weight: 900; }
.approval-toast { position: fixed; right: 24px; bottom: 82px; z-index: 45; display: flex; align-items: center; gap: 8px; margin: 0;
  padding: 11px 14px; border: 1px solid color-mix(in srgb, var(--success) 30%, var(--line)); border-radius: 6px;
  background: var(--surface); color: var(--success); box-shadow: 0 10px 28px #17201b20; font-size: 12px; font-weight: 750; }
.approval-backdrop { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px; background: #16201980; backdrop-filter: blur(4px); }
.approval-dialog { width: min(760px, 100%); max-height: min(820px, calc(100dvh - 40px)); overflow: auto; border: 1px solid #ffffff35;
  border-radius: 8px; background: var(--surface); box-shadow: 0 30px 80px #101a1460; outline: none; }
.approval-head { position: sticky; top: 0; z-index: 2; display: flex; justify-content: space-between; align-items: flex-start; gap: 18px;
  padding: 20px 22px; background: var(--ink); color: #fff; }
.approval-head__identity { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.approval-emblem { display: grid; flex: 0 0 38px; width: 38px; height: 38px; place-items: center; border: 1px solid #ffffff38; background: #ffffff10; }
.approval-kicker { color: #ffffffa3; font-size: 10px; font-weight: 750; letter-spacing: .12em; }
.approval-head h2 { margin: 6px 0 0; font: 600 26px/1.15 var(--font-display); letter-spacing: 0; }
.approval-head__actions { display: flex; gap: 5px; }
.approval-head__actions button { display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid #ffffff30; border-radius: 5px; background: transparent; color: #fff; cursor: pointer; }
.approval-head__actions button:disabled { opacity: .35; cursor: default; }
.approval-overview { display: grid; gap: 12px; padding: 17px 22px; border-bottom: 1px solid var(--line); background: var(--surface-soft); }
.approval-overview__tags { display: flex; flex-wrap: wrap; gap: 7px; }
.tool-category, .risk { display: inline-flex; align-items: center; gap: 5px; padding: 4px 7px; border: 1px solid var(--line); border-radius: 4px; font-size: 10px; font-weight: 850; }
.tool-category { color: var(--accent-deep); background: var(--surface); }
.risk--neutral { color: var(--ink-soft); background: var(--surface); }
.risk--warning { color: #81581e; border-color: #d7b97d75; background: #f4e8d0; }
.risk--danger { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 28%, var(--line)); background: var(--danger-soft); }
.approval-overview > p { margin: 0; color: var(--ink-soft); font-size: 13px; line-height: 1.6; }
.approval-facts { display: grid; grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr); gap: 8px; margin: 0; }
.approval-facts div { display: grid; gap: 3px; min-width: 0; }
.approval-facts dt { color: var(--muted); font-size: 10px; font-weight: 750; }
.approval-facts dd { margin: 0; min-width: 0; overflow: hidden; color: var(--ink); font-size: 12px; text-overflow: ellipsis; }
.approval-facts code { font-size: 11px; }
.approval-body { display: grid; gap: 16px; padding: 18px 22px 22px; }
.approval-section { display: grid; gap: 10px; }
.section-title { display: flex; justify-content: space-between; align-items: end; gap: 12px; }
.section-title > div { display: grid; gap: 2px; }
.section-title span { color: var(--muted); font-size: 9px; font-weight: 850; letter-spacing: .12em; }
.section-title h3 { margin: 0; color: var(--ink); font-size: 14px; letter-spacing: 0; }
.section-title > strong { color: var(--muted); font-size: 11px; }
.requested-tasks article { display: grid; grid-template-columns: 38px minmax(0, 1fr); gap: 11px; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.task-index { font: 600 19px/1 var(--font-display); color: var(--accent); }
.requested-tasks p { margin: 4px 0; color: var(--ink-soft); font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.requested-tasks small { color: var(--muted); font: 10px ui-monospace, SFMono-Regular, monospace; }
.parameter-summary dl { display: grid; gap: 1px; margin: 0; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; background: var(--line); }
.parameter-summary dl div { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 12px; padding: 9px 11px; background: #fff; }
.parameter-summary dt { display: flex; align-items: center; gap: 5px; color: var(--muted); font: 11px ui-monospace, SFMono-Regular, monospace; }
.parameter-summary dd { margin: 0; overflow-wrap: anywhere; color: var(--ink-soft); font: 11px/1.55 ui-monospace, SFMono-Regular, monospace; white-space: pre-wrap; }
.empty-parameters { margin: 0; padding: 12px; border: 1px dashed var(--line); color: var(--muted); font-size: 12px; }
.suggestion-list { display: flex; flex-wrap: wrap; gap: 7px; }
.suggestion-list button, .advanced-actions button { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 5px 9px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); color: var(--ink-soft); font-size: 11px; cursor: pointer; }
.suggestion-list button.active { border-color: color-mix(in srgb, var(--accent) 48%, var(--line)); background: var(--accent-soft); color: var(--accent-deep); }
.approval-meta { display: flex; justify-content: space-between; gap: 12px; align-items: center; padding: 10px 11px; border: 1px solid var(--line); background: var(--surface-soft); color: var(--muted); font-size: 11px; }
.approval-meta span, .approval-meta button { display: inline-flex; align-items: center; gap: 6px; }
.approval-meta button { min-height: 28px; border: 0; background: transparent; color: var(--accent-deep); cursor: pointer; }
.approval-editor { display: grid; gap: 7px; }
.approval-editor label { color: var(--muted); font-size: 11px; font-weight: 800; }
.approval-editor label span { float: right; }
.approval-editor textarea { box-sizing: border-box; width: 100%; min-height: 94px; padding: 11px; border: 1px solid var(--line); border-radius: 5px; background: #fff; color: var(--ink); resize: vertical; }
.json-editor { font: 12px/1.6 ui-monospace, SFMono-Regular, monospace; }
.advanced-actions { display: flex; justify-content: flex-end; }
.approval-error { display: flex; align-items: flex-start; gap: 7px; margin: 0; color: var(--danger); font-size: 12px; }
.approval-actions { position: sticky; bottom: 0; z-index: 2; display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 14px 22px; border-top: 1px solid var(--line); background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(10px); }
.approval-actions > div { display: flex; justify-content: flex-end; gap: 7px; }
.approval-actions__status { color: var(--muted); font-size: 10px; }
.approval-actions .button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 40px; }

@media (max-width: 620px) {
  .approval-launcher { right: 14px; bottom: 14px; }
  .approval-toast { right: 14px; bottom: 72px; max-width: calc(100vw - 28px); }
  .approval-backdrop { padding: 0; place-items: end; }
  .approval-dialog { width: 100%; max-height: 94dvh; border-radius: 8px 8px 0 0; }
  .approval-head { padding: 16px; }
  .approval-emblem { display: none; }
  .approval-head h2 { font-size: 21px; }
  .approval-overview, .approval-body, .approval-actions { padding-left: 16px; padding-right: 16px; }
  .approval-facts { grid-template-columns: 1fr; }
  .parameter-summary dl div { grid-template-columns: 1fr; gap: 4px; }
  .approval-meta { align-items: flex-start; flex-direction: column; }
  .approval-actions { align-items: stretch; flex-direction: column; }
  .approval-actions__status { display: none; }
  .approval-actions > div { display: grid; grid-template-columns: 1fr 1fr; }
  .approval-actions .button--primary { grid-column: 1 / -1; }
}
</style>
