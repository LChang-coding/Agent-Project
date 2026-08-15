<template>
  <section class="task-detail" aria-labelledby="subagent-title">
    <header>
      <button ref="backButtonRef" type="button" class="back" @click="$emit('back')">← 返回主 Agent</button>
      <div class="identity">
        <span :class="['status-mark', `status-mark--${tone}`]" />
        <div><span>SUB-AGENT / {{ task.taskId.slice(0, 8) }}</span><h1 id="subagent-title">{{ agentName }}</h1></div>
        <div class="task-actions"><strong>{{ statusLabel }}</strong><button v-if="cancellable" type="button" :disabled="cancelling" @click="$emit('cancel')">{{ cancelling ? '取消中…' : '取消任务' }}</button></div>
      </div>
    </header>
    <div class="detail-grid">
      <article class="brief"><span>任务指令</span><p>{{ task.instruction }}</p></article>
      <dl class="facts">
        <div><dt>状态</dt><dd>{{ statusLabel }}</dd></div>
        <div><dt>尝试次数</dt><dd>{{ task.attempt || 0 }}</dd></div>
        <div><dt>开始时间</dt><dd>{{ format(task.createdAt) }}</dd></div>
        <div><dt>完成时间</dt><dd>{{ format(task.completedAt) }}</dd></div>
        <div><dt>Callback</dt><dd>{{ task.callbackStatus || '尚未进入' }}</dd></div>
        <div><dt>Trace ID</dt><dd><button v-if="task.traceId" type="button" :title="task.traceId" @click="copyTrace">{{ traceCopied ? '已复制' : compact(task.traceId) }}</button><span v-else>—</span></dd></div>
      </dl>
      <ol class="execution-flow" aria-label="子 Agent 执行进度" aria-live="polite">
        <li v-for="stage in executionStages" :key="stage.label" :class="`execution-flow__item--${stage.state}`">
          <span aria-hidden="true" />
          <div><strong>{{ stage.label }}</strong><small>{{ stage.detail }}</small></div>
        </li>
      </ol>
      <WorkflowNodeExecutionPanel v-if="childRun" class="child-execution" :run="childRun" />
      <p v-else class="stream-boundary">子 Agent 运行建立后，这里会独立展示思考、工具调用和执行状态。</p>
      <p v-if="copyError" class="copy-error" role="status" aria-live="polite">{{ copyError }}</p>
      <article v-if="task.errorCode" class="result result--error"><span>失败原因</span><pre>{{ task.errorCode }}</pre></article>
      <article class="result">
        <header class="result__header">
          <span>{{ outputLabel }}</span>
          <button v-if="outputAvailable" type="button" :aria-label="resultCopied ? '子任务结果已复制' : '复制子任务结果'" @click="copyResult">
            <Check v-if="resultCopied" :size="15" aria-hidden="true" />
            <Copy v-else :size="15" aria-hidden="true" />
            {{ resultCopied ? '已复制' : '复制结果' }}
          </button>
        </header>
        <pre>{{ outputText }}</pre>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Check, Copy } from '@lucide/vue';
import type { SubagentTaskView } from '@/types/api';
import { copyText } from '@/utils/clipboard';
import { subagentTaskLabel, subagentTaskTone } from '@/domain/chat-orchestration-state';
import { createWorkflowRunState } from '@/domain/workflow-event-reducer';
import { useChatStore } from '@/stores/chat';
import WorkflowNodeExecutionPanel from '@/components/chat/WorkflowNodeExecutionPanel.vue';
const props = defineProps<{ task: SubagentTaskView; agentName: string; cancelling?: boolean }>();
defineEmits<{ back: []; cancel: [] }>();
const chatStore = useChatStore();
const backButtonRef = ref<HTMLButtonElement | null>(null);
const traceCopied = ref(false);
const resultCopied = ref(false);
const copyError = ref('');
const tone = computed(() => subagentTaskTone(props.task));
const statusLabel = computed(() => subagentTaskLabel(props.task));
const cancellable = computed(() => ['READY', 'RUNNING'].includes(props.task.status));
const outputAvailable = computed(() => Boolean(props.task.fullContext || props.task.resultSummary));
const outputText = computed(() => props.task.fullContext || props.task.resultSummary
  || (['READY', 'RUNNING'].includes(props.task.status)
    ? '执行中；最终结果会在子 Agent 回调后展示。'
    : '子任务未返回可展示的结果。'));
const outputLabel = computed(() => ['READY', 'RUNNING'].includes(props.task.status) ? '执行输出' : '最终输出');
const childRun = computed(() => props.task.childRunId ? chatStore.workflowRuns[props.task.childRunId] : undefined);
watch(() => [props.task.childRunId, props.task.childRunTraceId] as const, ([runId, traceId]) => {
  if (!runId || !traceId) return;
  if (!chatStore.workflowRuns[runId]) chatStore.workflowRuns[runId] = createWorkflowRunState(runId, traceId);
  void chatStore.connectAgentRunEvents(chatStore.sessionId, runId, traceId);
}, { immediate: true });
const executionStages = computed(() => {
  const executionFinished = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'ACKED'].includes(props.task.status);
  const callbackFinished = props.task.status === 'ACKED' || props.task.callbackStatus === 'DELIVERED';
  const failed = props.task.status === 'FAILED' || Boolean(props.task.errorCode);
  return [
    { label: '任务已创建', detail: '已进入子 Agent 调度队列', state: 'done' },
    {
      label: '子 Agent 执行',
      detail: props.task.status === 'READY' ? '等待开始' : failed ? '执行失败' : executionFinished ? '执行已结束' : '正在执行',
      state: failed ? 'error' : executionFinished ? 'done' : 'active',
    },
    {
      label: '结果回调',
      detail: callbackFinished ? '结果已送达主 Agent' : executionFinished ? '等待回调收口' : '执行完成后开始',
      state: callbackFinished ? (failed ? 'error' : 'done') : executionFinished ? 'active' : 'pending',
    },
  ];
});
function format(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function compact(value: string) { return `${value.slice(0, 8)}…${value.slice(-8)}`; }
async function copyTrace() {
  if (!props.task.traceId) return;
  copyError.value = '';
  try {
    await copyText(props.task.traceId);
    traceCopied.value = true;
    window.setTimeout(() => { traceCopied.value = false; }, 1200);
  } catch {
    traceCopied.value = false;
    copyError.value = '复制失败，请手动选择 Trace ID';
  }
}
async function copyResult() {
  if (!outputAvailable.value) return;
  copyError.value = '';
  try {
    await copyText(outputText.value);
    resultCopied.value = true;
    window.setTimeout(() => { resultCopied.value = false; }, 1200);
  } catch {
    resultCopied.value = false;
    copyError.value = '复制失败，请手动选择子任务结果';
  }
}
defineExpose({ focus: () => backButtonRef.value?.focus() });
</script>

<style scoped>
.task-detail{overflow:auto;min-height:0;padding:28px clamp(20px,4vw,56px);background:linear-gradient(135deg,color-mix(in srgb,var(--surface-soft) 84%,transparent),var(--surface))}.task-detail>header,.detail-grid{width:min(920px,100%);margin:auto}.back{border:0;background:transparent;color:var(--muted);min-height:40px;padding:0;cursor:pointer}.identity{display:grid;grid-template-columns:auto 1fr auto;gap:14px;align-items:center;padding:22px 0;border-bottom:1px solid var(--line)}.identity>div:nth-child(2){min-width:0}.identity span{font-size:11px;letter-spacing:.16em;color:var(--muted)}.identity h1{margin:4px 0 0;overflow-wrap:anywhere;font:600 clamp(26px,4vw,40px)/1 var(--font-display)}.task-actions{display:flex;align-items:flex-end;gap:8px;flex-direction:column}.task-actions button{min-height:34px;padding:0 10px;border:1px solid color-mix(in srgb,var(--danger) 35%,var(--line));background:transparent;color:var(--danger);cursor:pointer}.status-mark{width:12px;height:44px;background:#999}.status-mark--active{background:var(--accent)}.status-mark--done{background:#4d765e}.status-mark--error{background:var(--danger)}.detail-grid{display:grid;grid-template-columns:1.3fr .7fr;gap:16px;padding-top:20px}.brief,.facts,.result,.execution-flow{margin:0;border:1px solid var(--line);background:var(--surface);padding:20px}.brief span,.result span,dt{font-size:10px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}.brief p{font-size:17px;line-height:1.7}.facts{display:grid;gap:13px}.facts div{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid var(--line);padding-bottom:10px}.facts dd{margin:0;text-align:right;font-size:12px}.facts button{min-width:24px;min-height:24px;padding:2px 4px;border:0;background:none;color:var(--accent);cursor:pointer}.execution-flow{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));list-style:none}.execution-flow li{position:relative;display:grid;grid-template-columns:auto minmax(0,1fr);gap:10px;min-width:0;padding-right:18px}.execution-flow li>span{width:10px;height:10px;margin-top:3px;border:2px solid var(--line-strong);background:var(--surface)}.execution-flow li:not(:last-child)::after{position:absolute;top:7px;right:8px;left:22px;height:1px;content:"";background:var(--line)}.execution-flow li div{position:relative;z-index:1;background:var(--surface)}.execution-flow strong,.execution-flow small{display:block}.execution-flow strong{font-size:12px}.execution-flow small{margin-top:5px;color:var(--muted);font-size:10px;line-height:1.45}.execution-flow__item--active>span{border-color:var(--accent);background:var(--accent)}.execution-flow__item--done>span{border-color:var(--success);background:var(--success)}.execution-flow__item--error>span{border-color:var(--danger);background:var(--danger)}.stream-boundary{grid-column:1/-1;margin:0;padding:10px 12px;border-left:3px solid var(--line-strong);background:var(--surface-soft);color:var(--muted);font-size:11px;line-height:1.55}.copy-error{grid-column:1/-1;margin:0;color:var(--danger);font-size:11px;line-height:1.4}.result{grid-column:1/-1}.result__header{display:flex;align-items:center;justify-content:space-between;gap:12px}.result__header button{display:inline-flex;align-items:center;gap:5px;min-height:28px;padding:0 8px;border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer;font-size:11px}.result pre{white-space:pre-wrap;word-break:break-word;font:13px/1.75 ui-monospace,SFMono-Regular,Menlo,monospace;max-height:46vh;overflow:auto}.result--error{border-color:color-mix(in srgb,var(--danger) 40%,var(--line))}@media(max-width:760px){.detail-grid{grid-template-columns:1fr}.identity{grid-template-columns:auto minmax(0,1fr);align-items:start}.status-mark{grid-row:1/span 2}.task-actions{grid-column:2;align-items:center;justify-content:space-between;flex-direction:row}.result{grid-column:auto}.execution-flow{grid-column:auto;grid-template-columns:1fr;gap:14px}.execution-flow li:not(:last-child)::after{top:14px;bottom:-14px;left:4px;width:1px;height:auto}.stream-boundary{grid-column:auto}}
.child-execution{grid-column:1/-1}@media(max-width:760px){.child-execution{grid-column:auto}}
</style>
