<template>
  <section v-if="snapshot?.runs.length" class="run-card" aria-live="polite">
    <header>
      <div>
        <span class="eyebrow">MULTI-AGENT RUN</span>
        <h2>{{ phaseLabel(snapshot.phase) }}</h2>
      </div>
      <span :class="['phase-pill', `phase-pill--${tone(snapshot.phase)}`]">{{ completedCount }}/{{ taskCount }} 完成</span>
    </header>
    <div class="run-track" aria-hidden="true"><span :style="{ width: `${progress}%` }" /></div>
    <div class="task-grid">
      <button v-for="task in currentTasks" :key="task.taskId" type="button" @click="$emit('select-task', task.taskId)">
        <span :class="['task-dot', `task-dot--${taskTone(task.status)}`]" />
        <span><strong>{{ agentName(task.childAgentId) }}</strong><small>{{ taskLabel(task.status, task.callbackStatus) }}</small></span>
        <span aria-hidden="true">↗</span>
      </button>
    </div>
    <footer v-if="snapshot.inputLocked">
      <span>当前会话暂时锁定发送；可以继续编辑草稿，汇总完成后即可发送。</span>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { AiAgentConfig, SessionOrchestrationPhase, SessionOrchestrationSnapshot } from '@/types/api';

const props = defineProps<{ snapshot?: SessionOrchestrationSnapshot; agents: AiAgentConfig[] }>();
defineEmits<{ 'select-task': [taskId: string] }>();
const currentRun = computed(() => props.snapshot?.runs.find((run) => run.parentRunId === props.snapshot?.currentRunId)
  || props.snapshot?.runs[0]);
const currentTasks = computed(() => currentRun.value?.tasks || []);
const taskCount = computed(() => currentTasks.value.length);
const completedCount = computed(() => currentTasks.value.filter((task) => ['ACKED', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status)).length);
const progress = computed(() => taskCount.value ? Math.round((completedCount.value / taskCount.value) * 100) : 0);
function agentName(id: string) { return props.agents.find((agent) => agent.agentId === id)?.agentName || `Agent ${id}`; }
function phaseLabel(phase: SessionOrchestrationPhase) {
  return ({ WAITING_APPROVAL: '等待你的确认', EXECUTING: '子 Agent 正在协作', SUMMARIZING: '主 Agent 正在汇总', COMPLETED: '协作已完成', COMPLETED_WITH_ERRORS: '协作已完成，部分失败', CANCELLED: '协作已取消', IDLE: '暂无运行' })[phase];
}
function tone(phase: SessionOrchestrationPhase) { return ['WAITING_APPROVAL', 'EXECUTING', 'SUMMARIZING'].includes(phase) ? 'active' : phase === 'COMPLETED_WITH_ERRORS' ? 'error' : 'done'; }
function taskTone(status: string) { return ['READY', 'RUNNING'].includes(status) ? 'active' : status === 'FAILED' ? 'error' : status === 'CANCELLED' ? 'muted' : 'done'; }
function taskLabel(status: string, callback?: string) {
  if (status === 'READY') return '等待执行'; if (status === 'RUNNING') return '执行中';
  if (status === 'FAILED') return '执行失败'; if (status === 'CANCELLED') return '已取消';
  return callback === 'DELIVERED' ? '已回调' : '等待汇总';
}
</script>

<style scoped>
.run-card{margin:0 auto 18px;width:min(880px,calc(100% - 36px));border:1px solid color-mix(in srgb,var(--ink) 16%,transparent);background:color-mix(in srgb,var(--surface) 94%,#e9eee5);box-shadow:0 14px 32px #19241d12}.run-card header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px 14px}.eyebrow{font-size:10px;letter-spacing:.18em;color:var(--muted)}h2{margin:4px 0 0;font:600 22px/1.1 var(--font-display)}.phase-pill{border:1px solid var(--line);padding:6px 9px;font-size:12px}.phase-pill--active{color:#365b48;background:#e5eee7}.phase-pill--error{color:var(--danger);background:#f7e9e5}.run-track{height:3px;background:var(--line)}.run-track span{display:block;height:100%;background:var(--accent);transition:width .25s ease}.task-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:1px;background:var(--line);border-top:1px solid var(--line)}.task-grid button{min-height:62px;border:0;background:var(--surface);padding:11px 14px;display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:center;text-align:left;color:var(--ink);cursor:pointer}.task-grid button:hover{background:var(--surface-soft)}.task-grid small{display:block;margin-top:3px;color:var(--muted)}.task-dot{width:8px;height:8px;border-radius:50%;background:#9ba09a}.task-dot--active{background:var(--accent);box-shadow:0 0 0 4px color-mix(in srgb,var(--accent) 14%,transparent)}.task-dot--done{background:#4d765e}.task-dot--error{background:var(--danger)}footer{padding:10px 20px;color:var(--muted);font-size:12px;border-top:1px solid var(--line)}
</style>
