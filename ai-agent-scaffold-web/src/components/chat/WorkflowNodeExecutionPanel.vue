<template>
  <details class="execution-panel" :open="run.status === 'running'">
    <summary>
      <span :class="['execution-orb', `execution-orb--${run.status}`]" aria-hidden="true" />
      <strong>{{ statusLabel }}</strong>
      <span>{{ eventSummary }}</span>
    </summary>
    <div class="execution-body">
      <ol v-if="run.activities.length" class="activity-list" aria-label="Agent 运行动态">
        <li v-for="activity in run.activities" :key="activity.id" :class="`activity--${activity.status}`">
          <span class="activity-mark" aria-hidden="true" />
          <div><strong>{{ activity.label }}</strong><small v-if="activity.detail">{{ activity.detail }}</small></div>
          <em>{{ activityStatus(activity.status) }}<template v-if="activity.costMs !== undefined"> · {{ activity.costMs }}ms</template></em>
        </li>
      </ol>
      <ol v-if="run.reactTurns.length" class="react-timeline" aria-label="Agent 思考与工具调用时间线">
        <li v-for="(turn, index) in run.reactTurns" :key="turn.id" class="react-turn">
          <details v-if="turn.thinking" class="thinking-block" :open="run.status === 'running' && index === run.reactTurns.length - 1">
            <summary>
              <span class="thinking-wave" aria-hidden="true"><i /><i /><i /></span>
              <strong>{{ run.status === 'running' && index === run.reactTurns.length - 1 ? '正在思考' : `思考 ${index + 1}` }}</strong>
              <small>{{ turn.thinking.length }} 字</small>
            </summary>
            <pre>{{ turn.thinking }}</pre>
          </details>
          <div v-for="call in turn.tools" :key="call.functionCallId" :class="['react-tool', `react-tool--${call.status}`]">
            <span class="activity-mark" aria-hidden="true" />
            <div><strong>{{ call.displayName }}</strong><small v-if="call.errorCode">{{ call.errorCode }}<template v-if="call.reason"> · {{ call.reason }}</template></small></div>
            <em>{{ toolStatus(call.status) }}<template v-if="call.costMs !== undefined"> · {{ call.costMs }}ms</template></em>
          </div>
        </li>
      </ol>
      <ol v-if="run.nodes.length" class="node-list">
        <li v-for="node in run.nodes" :key="node.nodeExecutionId">
          <header>
            <span :class="['node-mark', { 'node-mark--active': node.status === 'running' }]" aria-hidden="true" />
            <strong>{{ node.nodeName }}</strong><small>第 {{ node.executionIndex }} 次</small><em>{{ nodeStatus(node.status) }}</em>
          </header>
          <pre v-if="node.output">{{ node.output }}</pre>
          <p v-if="node.errorMessage" class="execution-error">{{ node.errorMessage }}</p>
          <details v-if="node.toolCalls.length" class="node-tools">
            <summary>工具调用 · {{ node.toolCalls.length }}</summary>
            <div v-for="call in node.toolCalls" :key="call.functionCallId" :class="['node-tool', `node-tool--${call.status}`]">
              <strong>{{ call.displayName }}</strong><span>{{ toolStatus(call.status) }}</span>
              <small v-if="call.hits !== undefined">命中 {{ call.hits }} · 引用 {{ call.citations || 0 }} · {{ call.costMs || 0 }} ms<span v-if="call.degraded"> · 已降级</span></small>
              <small v-else-if="call.routeKey">routeKey: {{ call.routeKey }}<span v-if="call.reason"> · {{ call.reason }}</span></small>
              <small v-else-if="call.errorCode">{{ call.errorCode }} · {{ call.retryable ? '可重试' : '不可重试' }}</small>
            </div>
          </details>
          <p v-if="node.routeRepairStatus" class="route-repair">路由修复：{{ node.routeRepairStatus === 'running' ? '进行中' : `已完成${node.routeRepairRouteKey ? ` · ${node.routeRepairRouteKey}` : ''}` }}</p>
          <footer v-if="node.routeTargetNodeId || node.totalTokens !== undefined">
            <span v-if="node.routeTargetNodeId" :class="[`route--${(node.routeCategory || 'BUSINESS').toLowerCase()}`]">{{ routeLabel(node.routeCategory) }}：{{ node.routeKey || node.routeStrategy || '默认' }} → {{ node.routeTargetNodeName || node.routeTargetNodeId }}</span>
            <span v-if="node.totalTokens !== undefined">{{ node.totalTokens }} Token</span>
          </footer>
        </li>
      </ol>
      <p v-if="run.errorMessage" class="execution-error">{{ run.errorMessage }}</p>
      <div class="execution-trace"><code>{{ run.traceId }}</code><button type="button" @click="copyTrace">复制 Trace ID</button></div>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowRunViewState } from '@/types/intelligent-workflow';
import { copyText } from '@/utils/clipboard';
const props = defineProps<{ run: WorkflowRunViewState }>();
const statusLabel = computed(() => props.run.waitingAll ? '等待子 Agent 回调' : ({ running: 'Agent 正在工作', completed: '运行已完成', failed: '运行失败', cancelled: '运行已取消' }[props.run.status]));
const eventSummary = computed(() => `${props.run.nodes.length + props.run.activities.length + props.run.reactTurns.length} 个步骤 · #${props.run.lastSequence}`);
function nodeStatus(status: string) { return ({ running: '执行中', completed: '已完成', failed: '失败', cancelled: '已取消' } as Record<string, string>)[status] || status; }
function toolStatus(status: string) { return ({ running: '调用中', completed: '已完成', failed: '失败' } as Record<string, string>)[status] || status; }
function activityStatus(status: string) { return ({ running: '进行中', completed: '完成', failed: '失败', waiting: '等待中' } as Record<string, string>)[status] || status; }
function routeLabel(category?: string) { return category === 'FAILURE' ? '技术失败路由' : category === 'DEFAULT' ? '默认兜底' : '工具裁决'; }
async function copyTrace() { await copyText(props.run.traceId); }
</script>

<style scoped>
.execution-panel{margin:2px 0 12px;overflow:hidden;border:1px solid color-mix(in srgb,var(--ink) 13%,transparent);border-radius:12px;background:#f6f7f3;box-shadow:inset 0 1px 0 rgb(255 255 255/.8)}
.execution-panel>summary{display:flex;align-items:center;min-height:42px;padding:0 12px;gap:9px;cursor:pointer;list-style:none}.execution-panel>summary::-webkit-details-marker{display:none}.execution-panel>summary>span:last-child{margin-left:auto;color:var(--muted);font:11px ui-monospace,SFMono-Regular,Menlo,monospace}
.execution-orb{width:8px;height:8px;flex:none;border-radius:50%;background:var(--success)}.execution-orb--running{background:var(--accent-deep);box-shadow:0 0 0 0 color-mix(in srgb,var(--accent) 28%,transparent);animation:soft-pulse 1.6s ease-out infinite}.execution-orb--failed,.execution-orb--cancelled{background:var(--danger)}
.execution-body{display:grid;gap:8px;padding:0 10px 10px}.thinking-block{overflow:hidden;border:1px solid color-mix(in srgb,var(--ink) 9%,transparent);border-radius:9px;background:rgb(255 255 255/.58)}.thinking-block>summary{display:flex;align-items:center;min-height:36px;padding:0 10px;gap:8px;cursor:pointer;list-style:none}.thinking-block>summary small{margin-left:auto;color:var(--muted)}.thinking-block pre{max-height:220px;margin:0;padding:2px 12px 12px;overflow:auto;color:#59605a;white-space:pre-wrap;word-break:break-word;font:12px/1.72 ui-monospace,SFMono-Regular,Menlo,monospace}
.thinking-wave{display:flex;align-items:flex-end;width:17px;height:13px;gap:2px}.thinking-wave i{width:3px;height:5px;border-radius:2px;background:var(--accent-deep);animation:wave 1s ease-in-out infinite}.thinking-wave i:nth-child(2){height:10px;animation-delay:.12s}.thinking-wave i:nth-child(3){height:7px;animation-delay:.24s}
.react-timeline{display:grid;margin:0;padding:0;gap:8px;list-style:none}.react-turn{display:grid;gap:6px;padding-left:10px;border-left:2px solid color-mix(in srgb,var(--accent) 28%,var(--line))}.react-tool{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;padding:9px 10px;gap:9px;border-radius:8px;background:rgb(255 255 255/.64)}.react-tool small{display:block;margin-top:3px;color:var(--muted)}.react-tool em{color:var(--muted);font-size:11px;font-style:normal}.react-tool--running .activity-mark{border-color:var(--accent-deep);border-top-color:transparent;animation:spin .8s linear infinite}.react-tool--completed .activity-mark{border-color:var(--success);background:var(--success)}.react-tool--failed .activity-mark{border-color:var(--danger);background:var(--danger)}
.activity-list,.node-list{display:grid;margin:0;padding:0;gap:6px;list-style:none}.activity-list li{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;padding:9px 10px;gap:9px;border-radius:8px;background:rgb(255 255 255/.54)}.activity-mark{width:9px;height:9px;margin-top:3px;border:2px solid var(--line-strong);border-radius:50%}.activity--running .activity-mark{border-color:var(--accent-deep);border-top-color:transparent;animation:spin .8s linear infinite}.activity--completed .activity-mark{border-color:var(--success);background:var(--success)}.activity--failed .activity-mark{border-color:var(--danger);background:var(--danger)}.activity--waiting .activity-mark{border-color:var(--warning);background:var(--warning)}.activity-list strong,.activity-list small{display:block}.activity-list small{margin-top:3px;color:var(--muted)}.activity-list em{color:var(--muted);font-size:11px;font-style:normal}
.node-list>li{padding:10px;border-radius:9px;background:rgb(255 255 255/.54)}.node-list header,.node-list footer{display:flex;align-items:center;gap:8px}.node-list header em{margin-left:auto;font-size:11px;font-style:normal}.node-list pre{max-height:180px;margin:8px 0;overflow:auto;white-space:pre-wrap;font:12px/1.65 ui-monospace,SFMono-Regular,Menlo,monospace}.node-list footer{justify-content:space-between;color:var(--muted);font-size:11px}.node-mark{width:10px;height:10px;border:2px solid var(--line-strong);border-radius:50%}.node-mark--active{border-color:var(--accent-deep);border-top-color:transparent;animation:spin .8s linear infinite}
.node-tools{margin:8px 0;border:1px solid var(--line);border-radius:8px}.node-tools>summary{padding:7px 9px;font-size:11px}.node-tool{display:grid;grid-template-columns:1fr auto;padding:7px 9px;gap:3px 8px;border-top:1px solid var(--line);font-size:11px}.node-tool small{grid-column:1/-1;color:var(--muted)}.node-tool--failed{border-left:3px solid var(--danger)}.route-repair{color:var(--warning);font-size:11px}.route--failure{color:var(--danger);font-weight:700}.route--default{color:var(--warning)}
.execution-error{margin:0;padding:8px 10px;color:var(--danger);border-radius:8px;background:var(--danger-soft)}.execution-trace{display:flex;align-items:center;min-width:0;padding:7px 2px 0;gap:8px;color:var(--muted)}.execution-trace code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.execution-trace button{margin-left:auto;flex:none;border:0;background:none;color:var(--accent-deep);cursor:pointer}
@keyframes spin{to{transform:rotate(360deg)}}@keyframes wave{0%,100%{transform:scaleY(.55);opacity:.55}50%{transform:scaleY(1);opacity:1}}@keyframes soft-pulse{70%{box-shadow:0 0 0 7px transparent}}@media(prefers-reduced-motion:reduce){.execution-orb--running,.activity--running .activity-mark,.node-mark--active,.thinking-wave i{animation:none}}@media(max-width:640px){.execution-panel>summary>span:last-child{display:none}.activity-list li{grid-template-columns:auto minmax(0,1fr)}.activity-list em{grid-column:2}.thinking-block pre{max-height:180px}}
</style>
