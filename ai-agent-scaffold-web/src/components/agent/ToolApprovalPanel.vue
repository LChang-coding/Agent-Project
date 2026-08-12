<template>
  <button v-if="pending.length && !open" ref="launcherRef" class="approval-launcher" type="button" @click="show">
    <span>{{ pending.length }}</span> 项操作等待确认
  </button>

  <div v-if="open && current" class="approval-backdrop" @click.self="dismiss">
    <section ref="dialogRef" class="approval-dialog" role="dialog" aria-modal="true"
             aria-labelledby="approval-title" aria-describedby="approval-description" tabindex="-1"
             @keydown.esc.prevent="dismiss" @keydown.tab="trapFocus">
      <header class="approval-head">
        <div>
          <span class="approval-kicker">HUMAN CHECKPOINT · {{ activeIndex + 1 }}/{{ pending.length }}</span>
          <h2 id="approval-title">{{ approvalTitle }}</h2>
        </div>
        <button type="button" class="approval-close" aria-label="稍后处理审批" @click="dismiss">×</button>
      </header>

      <div class="approval-context">
        <span class="risk">{{ riskLabel }}</span>
        <p id="approval-description"><strong>{{ agentName(current.parentAgentId) }}</strong> 请求调用
          <code>{{ current.toolCode }}</code>。{{ approvalDescription }}</p>
      </div>

      <div class="approval-body">
        <section v-if="tasks.length" class="requested-tasks" aria-label="待创建子任务">
          <article v-for="(task, index) in tasks" :key="String(task.taskId || index)">
            <span class="task-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div><strong>{{ agentName(String(task.agentTemplateId || task.agentId || '')) }}</strong>
              <p>{{ task.instruction || task.input || task.fullContext || '未提供任务说明' }}</p>
              <small v-if="task.taskId">TASK · {{ task.taskId }}</small></div>
          </article>
        </section>
        <section v-else class="parameter-summary">
          <span>调用参数</span><dl><div v-for="(value, key) in current.requestedInput" :key="key"><dt>{{ key }}</dt><dd>{{ compactValue(value) }}</dd></div></dl>
        </section>

        <div class="approval-meta">
          <span>将在 <strong>{{ countdown }}</strong> 后按策略{{ current.timeoutDecision === 'APPROVE' ? '自动同意' : '自动拒绝' }}</span>
          <button v-if="current.traceId" type="button" @click="copyTrace">{{ traceCopied ? 'Trace 已复制' : `Trace ${compactTrace(current.traceId)}` }}</button>
        </div>

        <div v-if="mode === 'replan'" class="approval-editor">
          <label for="approval-comment">告诉 Agent 如何重新规划</label>
          <textarea id="approval-comment" v-model="draft.comment" placeholder="例如：只保留编码任务，研究任务暂不执行" />
        </div>
        <div v-if="mode === 'edit'" class="approval-editor">
          <label for="approval-json">修改调用参数 <span>高级 JSON</span></label>
          <textarea id="approval-json" v-model="draft.amendedInput" class="json-editor" spellcheck="false" />
        </div>
        <button v-if="mode === 'default'" type="button" class="advanced-toggle" @click="mode = 'edit'">需要修改任务参数？</button>
        <p v-if="errorMessage" class="approval-error" role="alert">{{ errorMessage }}</p>
      </div>

      <footer class="approval-actions">
        <button type="button" class="button button--ghost" :disabled="submitting" @click="reject">拒绝</button>
        <button type="button" class="button button--soft" :disabled="submitting" @click="requestReplan">重新规划</button>
        <button v-if="mode === 'edit'" type="button" class="button button--primary" :disabled="submitting" @click="submit(current, 'APPROVE_WITH_CHANGES')">
          {{ submitting ? '提交中…' : '按修改后参数批准' }}
        </button>
        <button v-else type="button" class="button button--primary" :disabled="submitting" @click="submit(current, 'APPROVE')">
          {{ submitting ? '提交中…' : `批准 ${tasks.length || 1} 项操作` }}
        </button>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { decideToolApproval, queryAgentConfigs, streamToolApprovals } from '@/api/agent';
import type { AiAgentConfig, ToolApprovalDecision, ToolApprovalRequest } from '@/types/api';
import { copyText } from '@/utils/clipboard';

type Mode = 'default' | 'edit' | 'replan';
const pending = ref<ToolApprovalRequest[]>([]); const agents = ref<AiAgentConfig[]>([]);
const open = ref(false); const activeIndex = ref(0); const mode = ref<Mode>('default');
const submitting = ref(false); const errorMessage = ref(''); const now = ref(Date.now()); const traceCopied = ref(false);
const dialogRef = ref<HTMLElement | null>(null); const launcherRef = ref<HTMLButtonElement | null>(null);
const draft = reactive({ comment: '', amendedInput: '' });
let cursor = 0; let controller: AbortController | undefined; let reconnectTimer: number | undefined; let clockTimer: number | undefined;
const current = computed(() => pending.value[activeIndex.value]);
const tasks = computed<Array<Record<string, unknown>>>(() => Array.isArray(current.value?.requestedInput?.tasks)
  ? current.value.requestedInput.tasks as Array<Record<string, unknown>> : []);
const isSubagentCreation = computed(() => current.value?.toolCode === 'create_subagent_instances');
const approvalTitle = computed(() => isSubagentCreation.value ? '允许 Agent 启动这些任务？' : '允许 Agent 调用该工具？');
const approvalDescription = computed(() => isSubagentCreation.value
  ? '批准后会创建独立子会话并消耗模型额度。'
  : '批准后将使用当前参数执行一次调用，结果会写入本次运行审计。');
const riskLabel = computed(() => isSubagentCreation.value ? '中风险' : toolRisk(current.value?.toolCode));
const countdown = computed(() => {
  const millis = Math.max(0, Date.parse(current.value?.expiresAt || '') - now.value);
  const seconds = Math.ceil(millis / 1000); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
});

onMounted(async () => {
  try { agents.value = await queryAgentConfigs(); } catch { agents.value = []; }
  connect(); clockTimer = window.setInterval(() => { now.value = Date.now(); expireItems(); }, 1000);
});
onBeforeUnmount(() => { controller?.abort(); if (reconnectTimer) clearTimeout(reconnectTimer); if (clockTimer) clearInterval(clockTimer); });
watch(current, (value) => { if (value) resetDraft(value); });

async function connect() {
  controller = new AbortController();
  try {
    await streamToolApprovals(cursor, controller.signal, (item) => {
      cursor = Math.max(cursor, item.sequence);
      const index = pending.value.findIndex((value) => value.approvalId === item.approvalId);
      if (index >= 0) pending.value[index] = item; else pending.value.push(item);
      if (pending.value.length === 1) { resetDraft(item); show(); }
    });
    if (!controller.signal.aborted) reconnectTimer = window.setTimeout(connect, 500);
  } catch (error) {
    if (!controller.signal.aborted) { errorMessage.value = error instanceof Error ? error.message : '审批事件流已断开'; reconnectTimer = window.setTimeout(connect, 2000); }
  }
}
function resetDraft(item: ToolApprovalRequest) { mode.value = 'default'; draft.comment = ''; draft.amendedInput = JSON.stringify(item.requestedInput, null, 2); errorMessage.value = ''; }
function show() { open.value = true; void nextTick(() => dialogRef.value?.focus()); }
function dismiss() { open.value = false; void nextTick(() => launcherRef.value?.focus()); }
function expireItems() { pending.value = pending.value.filter((item) => Date.parse(item.expiresAt) > now.value); if (!pending.value.length) open.value = false; activeIndex.value = Math.min(activeIndex.value, Math.max(0, pending.value.length - 1)); }
function agentName(id: string) { return agents.value.find((agent) => agent.agentId === id)?.agentName || (id ? `Agent ${id}` : '未指定 Agent'); }
function compactValue(value: unknown) { const text = typeof value === 'string' ? value : JSON.stringify(value); return text.length > 180 ? `${text.slice(0, 180)}…` : text; }
function compactTrace(value: string) { return `${value.slice(0, 8)}…${value.slice(-8)}`; }
function toolRisk(toolCode?: string) {
  if (toolCode?.startsWith('mcp:')) return '外部调用';
  if (toolCode?.startsWith('skill:')) return '指令加载';
  return '平台工具';
}
async function copyTrace() { if (!current.value?.traceId) return; await copyText(current.value.traceId); traceCopied.value = true; window.setTimeout(() => { traceCopied.value = false; }, 1200); }
function requestReplan() { if (mode.value !== 'replan') { mode.value = 'replan'; void nextTick(() => document.querySelector<HTMLTextAreaElement>('#approval-comment')?.focus()); return; } void submit(current.value!, 'REPLAN'); }
function reject() { void submit(current.value!, 'REJECT'); }
async function submit(item: ToolApprovalRequest, decision: ToolApprovalDecision) {
  errorMessage.value = ''; submitting.value = true;
  try {
    const amendedInput = decision === 'APPROVE_WITH_CHANGES' ? JSON.parse(draft.amendedInput) : undefined;
    await decideToolApproval(item.approvalId, { decision, comment: draft.comment, amendedInput, expectedRevision: item.revision });
    pending.value = pending.value.filter((value) => value.approvalId !== item.approvalId); activeIndex.value = 0;
    if (!pending.value.length) open.value = false;
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '审批提交失败'; }
  finally { submitting.value = false; }
}
function trapFocus(event: KeyboardEvent) {
  const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>('button:not(:disabled),textarea:not(:disabled),[tabindex]:not([tabindex="-1"])') || []);
  if (!focusable.length) return; const first = focusable[0], last = focusable.at(-1)!;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
</script>

<style scoped>
.approval-launcher{position:fixed;right:24px;bottom:24px;z-index:45;min-height:46px;padding:0 16px;border:1px solid color-mix(in srgb,var(--accent) 38%,var(--line));background:var(--ink);color:#fff;box-shadow:0 12px 32px #17201b35;cursor:pointer}.approval-launcher span{display:inline-grid;width:22px;height:22px;place-items:center;margin-right:7px;background:var(--accent);font-weight:900}.approval-backdrop{position:fixed;inset:0;z-index:70;display:grid;place-items:center;padding:20px;background:#16201980;backdrop-filter:blur(4px)}.approval-dialog{width:min(720px,100%);max-height:min(760px,calc(100dvh - 40px));overflow:auto;border:1px solid #ffffff35;background:var(--surface);box-shadow:0 30px 80px #101a1460;outline:none}.approval-head{display:flex;justify-content:space-between;align-items:start;padding:24px 26px 18px;background:var(--ink);color:#fff}.approval-kicker{font-size:10px;letter-spacing:.18em;color:#ffffff9c}.approval-head h2{margin:7px 0 0;font:600 clamp(24px,4vw,34px)/1.05 var(--font-display)}.approval-close{width:40px;height:40px;border:1px solid #ffffff38;background:transparent;color:#fff;font-size:22px;cursor:pointer}.approval-context{display:flex;gap:12px;align-items:flex-start;padding:16px 26px;border-bottom:1px solid var(--line);background:var(--surface-soft)}.approval-context p{margin:0;line-height:1.55;color:var(--ink-soft)}.risk{flex:0 0 auto;padding:4px 7px;background:#f4e8d0;color:#8a5d20;font-size:10px;font-weight:900}.approval-body{padding:20px 26px}.requested-tasks{display:grid;gap:8px}.requested-tasks article{display:grid;grid-template-columns:42px 1fr;gap:12px;padding:14px;border:1px solid var(--line);background:#fff}.task-index{font:600 22px/1 var(--font-display);color:var(--accent)}.requested-tasks p{margin:5px 0;line-height:1.55}.requested-tasks small{color:var(--muted);font:10px ui-monospace,SFMono-Regular,monospace}.approval-meta{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-top:14px;color:var(--muted);font-size:12px}.approval-meta button,.advanced-toggle{border:0;background:transparent;color:var(--accent-deep);cursor:pointer}.parameter-summary>span,.approval-editor label{font-size:11px;font-weight:800;color:var(--muted)}.parameter-summary dl{display:grid;gap:7px}.parameter-summary div{display:grid;grid-template-columns:120px 1fr;gap:12px}.parameter-summary dt{color:var(--muted)}.parameter-summary dd{margin:0;word-break:break-word}.approval-editor{display:grid;gap:8px;margin-top:16px}.approval-editor label span{float:right}.approval-editor textarea{box-sizing:border-box;width:100%;min-height:92px;padding:12px;border:1px solid var(--line);background:#fff;color:var(--ink);resize:vertical}.json-editor{font:12px/1.6 ui-monospace,SFMono-Regular,monospace}.advanced-toggle{margin-top:14px;padding:6px 0}.approval-error{color:var(--danger)}.approval-actions{position:sticky;bottom:0;display:flex;justify-content:flex-end;gap:8px;padding:16px 26px;border-top:1px solid var(--line);background:color-mix(in srgb,var(--surface) 95%,transparent);backdrop-filter:blur(10px)}.approval-actions .button{min-height:44px}@media(max-width:620px){.approval-backdrop{padding:0;place-items:end}.approval-dialog{max-height:92dvh}.approval-head,.approval-body,.approval-actions,.approval-context{padding-left:18px;padding-right:18px}.approval-actions{display:grid;grid-template-columns:1fr 1fr}.approval-actions .button--primary{grid-column:1/-1}.approval-meta{align-items:flex-start;flex-direction:column}}
</style>
