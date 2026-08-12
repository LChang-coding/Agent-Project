<template>
  <details class="workflow-progress" :open="run.status === 'running'">
    <summary>
      <span class="workflow-progress__pulse" :class="`workflow-progress__pulse--${run.status}`" aria-hidden="true" />
      <strong>{{ statusLabel }}</strong>
      <span>{{ run.nodes.length }} 次节点执行 · #{{ run.lastSequence }}</span>
    </summary>
    <ol class="workflow-progress__nodes">
      <li v-for="node in run.nodes" :key="node.nodeExecutionId">
        <header>
          <span class="workflow-progress__spinner" :class="{ 'workflow-progress__spinner--active': node.status === 'running' }" aria-hidden="true" />
          <strong>{{ node.nodeName }}</strong><small>第 {{ node.executionIndex }} 次</small><em>{{ nodeStatus(node.status) }}</em>
        </header>
        <pre v-if="node.output">{{ node.output }}</pre>
        <p v-if="node.errorMessage" class="workflow-progress__error">{{ node.errorMessage }}</p>
        <details v-if="node.toolCalls.length" class="workflow-progress__tools">
          <summary>工具调用 · {{ node.toolCalls.length }}</summary>
          <div v-for="call in node.toolCalls" :key="call.functionCallId" :class="['workflow-progress__tool', `workflow-progress__tool--${call.status}`]">
            <strong>{{ call.displayName }}</strong><span>{{ toolStatus(call.status) }}</span>
            <small v-if="call.hits !== undefined">命中 {{ call.hits }} · 引用 {{ call.citations || 0 }} · {{ call.costMs || 0 }} ms<span v-if="call.degraded"> · 已降级</span></small>
            <small v-else-if="call.routeKey">routeKey: {{ call.routeKey }}<span v-if="call.reason"> · {{ call.reason }}</span></small>
            <small v-else-if="call.errorCode">{{ call.errorCode }} · {{ call.retryable ? '可重试' : '不可重试' }}</small>
          </div>
        </details>
        <p v-if="node.routeRepairStatus" class="workflow-progress__repair">路由修复：{{ node.routeRepairStatus === 'running' ? '进行中' : `已完成${node.routeRepairRouteKey ? ` · ${node.routeRepairRouteKey}` : ''}` }}</p>
        <footer v-if="node.routeTargetNodeId || node.totalTokens !== undefined">
          <span v-if="node.routeTargetNodeId" :class="[`workflow-progress__route--${(node.routeCategory || 'BUSINESS').toLowerCase()}`]">
            {{ routeLabel(node.routeCategory) }}：{{ node.routeKey || node.routeStrategy || '默认' }} → {{ node.routeTargetNodeName || node.routeTargetNodeId }}
            <template v-if="node.routeSource"> · {{ node.routeSource }}</template>
            <template v-if="node.routeReason"> · {{ node.routeReason }}</template>
          </span>
          <span v-if="node.totalTokens !== undefined">{{ node.totalTokens }} Token</span>
        </footer>
      </li>
    </ol>
    <p v-if="run.errorMessage" class="workflow-progress__error">{{ run.errorMessage }}</p>
    <div class="workflow-progress__trace"><code>{{ run.traceId }}</code><button type="button" @click="copyTrace">复制 Trace ID</button></div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowRunViewState } from '@/types/intelligent-workflow';
import { copyText } from '@/utils/clipboard';

const props = defineProps<{ run: WorkflowRunViewState }>();
const statusLabel = computed(() => ({ running: '工作流运行中', completed: '工作流已完成', failed: '工作流失败', cancelled: '工作流已取消' }[props.run.status]));
function nodeStatus(status: string) { return ({ running: '执行中', completed: '已完成', failed: '失败', cancelled: '已取消' } as Record<string, string>)[status] || status; }
function toolStatus(status: string) { return ({ running: '调用中', completed: '已完成', failed: '失败' } as Record<string, string>)[status] || status; }
function routeLabel(category?: string) { return category === 'FAILURE' ? '技术失败路由' : category === 'DEFAULT' ? '默认兜底' : '工具裁决'; }
async function copyTrace() { await copyText(props.run.traceId); }
</script>

<style scoped>
.workflow-progress{margin:0 0 10px;border:1px solid rgb(99 102 241/.24);border-radius:14px;background:rgb(99 102 241/.045);overflow:hidden}.workflow-progress summary{display:flex;align-items:center;gap:9px;padding:10px 12px;cursor:pointer;list-style:none}.workflow-progress summary span:last-child{margin-left:auto;color:#64748b;font-size:12px}.workflow-progress__pulse{width:9px;height:9px;border-radius:50%;background:#22c55e}.workflow-progress__pulse--running{background:#6366f1;animation:pulse 1.4s infinite}.workflow-progress__pulse--failed{background:#ef4444}.workflow-progress__nodes{display:grid;gap:8px;margin:0;padding:0 12px 12px;list-style:none}.workflow-progress__nodes li{border-radius:10px;background:rgb(15 23 42/.04);padding:10px}.workflow-progress__nodes header,.workflow-progress__nodes footer{display:flex;align-items:center;gap:8px}.workflow-progress__nodes header em{margin-left:auto;font-style:normal;font-size:12px}.workflow-progress__nodes pre{margin:8px 0;max-height:180px;overflow:auto;white-space:pre-wrap;font:inherit;color:inherit}.workflow-progress__nodes footer{color:#64748b;font-size:12px;justify-content:space-between}.workflow-progress__tools{margin:8px 0;border:1px solid rgb(148 163 184/.25);border-radius:8px}.workflow-progress__tools>summary{padding:7px 9px;font-size:12px}.workflow-progress__tool{display:grid;grid-template-columns:1fr auto;gap:3px 8px;padding:7px 9px;border-top:1px solid rgb(148 163 184/.18);font-size:12px}.workflow-progress__tool small{grid-column:1/-1;color:#64748b}.workflow-progress__tool--failed{border-left:3px solid #ef4444}.workflow-progress__repair{margin:7px 0;color:#7c3aed;font-size:12px}.workflow-progress__route--default{color:#b45309}.workflow-progress__route--failure{color:#dc2626;font-weight:700}.workflow-progress__spinner{width:12px;height:12px;border:2px solid #cbd5e1;border-radius:50%}.workflow-progress__spinner--active{border-top-color:#6366f1;animation:spin .8s linear infinite}.workflow-progress__error{color:#dc2626;padding:0 12px}.workflow-progress__trace{display:flex;gap:8px;padding:9px 12px;border-top:1px solid rgb(148 163 184/.2)}.workflow-progress__trace code{overflow:hidden;text-overflow:ellipsis}.workflow-progress__trace button{margin-left:auto;border:0;background:none;color:#4f46e5;cursor:pointer;white-space:nowrap}@keyframes spin{to{transform:rotate(360deg)}}@keyframes pulse{70%{box-shadow:0 0 0 8px rgb(99 102 241/0)}}@media(prefers-reduced-motion:reduce){.workflow-progress__pulse--running,.workflow-progress__spinner--active{animation:none}}
</style>
