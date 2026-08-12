<template>
  <section class="task-detail" aria-labelledby="subagent-title">
    <header>
      <button type="button" class="back" @click="$emit('back')">← 返回主 Agent</button>
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
        <div><dt>Trace ID</dt><dd><button v-if="task.traceId" type="button" @click="copyTrace">{{ copied ? '已复制' : compact(task.traceId) }}</button><span v-else>—</span></dd></div>
      </dl>
      <article v-if="task.errorCode" class="result result--error"><span>失败原因</span><pre>{{ task.errorCode }}</pre></article>
      <article class="result"><span>执行输出</span><pre>{{ task.fullContext || task.resultSummary || pendingText }}</pre></article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { SubagentTaskView } from '@/types/api';
import { copyText } from '@/utils/clipboard';
const props = defineProps<{ task: SubagentTaskView; agentName: string; cancelling?: boolean }>();
defineEmits<{ back: []; cancel: [] }>();
const copied = ref(false);
const tone = computed(() => props.task.status === 'FAILED' ? 'error' : ['READY', 'RUNNING'].includes(props.task.status) ? 'active' : 'done');
const statusLabel = computed(() => ({ READY: '等待执行', RUNNING: '正在执行', SUCCEEDED: '执行成功 · 等待回调', FAILED: '执行失败', CANCELLED: '已取消', ACKED: '已回调主 Agent' })[props.task.status]);
const cancellable = computed(() => ['READY', 'RUNNING'].includes(props.task.status));
const pendingText = computed(() => props.task.status === 'RUNNING' ? '子 Agent 正在生成结果，输出会自动出现在这里。' : '尚无输出。');
function format(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function compact(value: string) { return `${value.slice(0, 8)}…${value.slice(-8)}`; }
async function copyTrace() { if (!props.task.traceId) return; await copyText(props.task.traceId); copied.value = true; window.setTimeout(() => { copied.value = false; }, 1200); }
</script>

<style scoped>
.task-detail{overflow:auto;min-height:0;padding:28px clamp(20px,4vw,56px);background:linear-gradient(135deg,color-mix(in srgb,var(--surface-soft) 84%,transparent),var(--surface))}.task-detail>header,.detail-grid{width:min(920px,100%);margin:auto}.back{border:0;background:transparent;color:var(--muted);min-height:40px;padding:0;cursor:pointer}.identity{display:grid;grid-template-columns:auto 1fr auto;gap:14px;align-items:center;padding:22px 0;border-bottom:1px solid var(--line)}.identity span{font-size:11px;letter-spacing:.16em;color:var(--muted)}.identity h1{margin:4px 0 0;font:600 clamp(26px,4vw,40px)/1 var(--font-display)}.identity>strong{font-size:13px}.task-actions{display:flex;align-items:flex-end;gap:8px;flex-direction:column}.task-actions button{min-height:34px;padding:0 10px;border:1px solid color-mix(in srgb,var(--danger) 35%,var(--line));background:transparent;color:var(--danger);cursor:pointer}.status-mark{width:12px;height:44px;background:#999}.status-mark--active{background:var(--accent)}.status-mark--done{background:#4d765e}.status-mark--error{background:var(--danger)}.detail-grid{display:grid;grid-template-columns:1.3fr .7fr;gap:16px;padding-top:20px}.brief,.facts,.result{margin:0;border:1px solid var(--line);background:var(--surface);padding:20px}.brief span,.result span,dt{font-size:10px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}.brief p{font-size:17px;line-height:1.7}.facts{display:grid;gap:13px}.facts div{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid var(--line);padding-bottom:10px}.facts dd{margin:0;text-align:right;font-size:12px}.facts button{border:0;background:none;color:var(--accent);cursor:pointer}.result{grid-column:1/-1}.result pre{white-space:pre-wrap;word-break:break-word;font:13px/1.75 ui-monospace,SFMono-Regular,Menlo,monospace;max-height:46vh;overflow:auto}.result--error{border-color:color-mix(in srgb,var(--danger) 40%,var(--line))}@media(max-width:760px){.detail-grid{grid-template-columns:1fr}.identity{grid-template-columns:auto 1fr}.identity>strong{grid-column:2}.result{grid-column:auto}}
</style>
