<template>
  <aside v-if="pending.length" class="approval-panel" aria-live="polite">
    <header><strong>工具调用待审批</strong><span>{{ pending.length }} 项</span></header>
    <article v-for="item in pending" :key="item.approvalId">
      <p><strong>{{ item.parentAgentId }}</strong> 请求调用 <code>{{ item.toolCode }}</code></p>
      <pre>{{ JSON.stringify(item.requestedInput, null, 2) }}</pre>
      <div class="suggestions">
        <button v-for="suggestion in item.suggestions" :key="suggestion" type="button"
                @click="drafts[item.approvalId].comment = suggestion">{{ suggestion }}</button>
      </div>
      <textarea v-model="drafts[item.approvalId].comment" placeholder="补充说明或重新规划要求"></textarea>
      <textarea v-model="drafts[item.approvalId].amendedInput" placeholder="修改后的 JSON 参数（修改后同意时填写）"></textarea>
      <div class="actions">
        <button type="button" @click="submit(item, 'APPROVE')">同意</button>
        <button type="button" @click="submit(item, 'APPROVE_WITH_CHANGES')">修改后同意</button>
        <button type="button" @click="submit(item, 'REPLAN')">重新规划</button>
        <button type="button" class="danger" @click="submit(item, 'REJECT')">拒绝</button>
      </div>
      <small>超时：{{ item.expiresAt }}</small>
    </article>
    <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
  </aside>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { decideToolApproval, streamToolApprovals } from '@/api/agent';
import type { ToolApprovalDecision, ToolApprovalRequest } from '@/types/api';

const pending = ref<ToolApprovalRequest[]>([]);
const drafts = reactive<Record<string, { comment: string; amendedInput: string }>>({});
const errorMessage = ref(''); let cursor = 0; let controller: AbortController | undefined;
let reconnectTimer: number | undefined; let expiryTimer: number | undefined;

onMounted(() => {
  connect();
  expiryTimer = window.setInterval(() => {
    const now = Date.now(); pending.value = pending.value.filter((item) => Date.parse(item.expiresAt) > now);
  }, 1000);
});
onBeforeUnmount(() => { controller?.abort(); if (reconnectTimer) clearTimeout(reconnectTimer); if (expiryTimer) clearInterval(expiryTimer); });

async function connect() {
  controller = new AbortController();
  try {
    await streamToolApprovals(cursor, controller.signal, (item) => {
      cursor = Math.max(cursor, item.sequence);
      if (!pending.value.some((current) => current.approvalId === item.approvalId)) {
        pending.value.push(item); drafts[item.approvalId] = { comment: '', amendedInput: JSON.stringify(item.requestedInput, null, 2) };
      }
    });
    if (!controller.signal.aborted) reconnectTimer = window.setTimeout(connect, 500);
  } catch (error) {
    if (!controller.signal.aborted) {
      errorMessage.value = error instanceof Error ? error.message : '审批事件流已断开';
      reconnectTimer = window.setTimeout(connect, 2000);
    }
  }
}

async function submit(item: ToolApprovalRequest, decision: ToolApprovalDecision) {
  errorMessage.value = '';
  try {
    const draft = drafts[item.approvalId];
    const amendedInput = decision === 'APPROVE_WITH_CHANGES' ? JSON.parse(draft.amendedInput) : undefined;
    await decideToolApproval(item.approvalId, { decision, comment: draft.comment, amendedInput, expectedRevision: item.revision });
    pending.value = pending.value.filter((current) => current.approvalId !== item.approvalId); delete drafts[item.approvalId];
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '审批提交失败'; }
}
</script>

<style scoped>
.approval-panel{position:fixed;right:20px;bottom:20px;z-index:40;width:min(480px,calc(100vw - 40px));max-height:75vh;overflow:auto;padding:14px;border:1px solid var(--line);border-radius:12px;background:var(--surface);box-shadow:0 16px 48px #0003}.approval-panel header,.actions,.suggestions{display:flex;gap:8px;justify-content:space-between;flex-wrap:wrap}.approval-panel article{display:grid;gap:8px;margin-top:12px;padding-top:12px;border-top:1px solid var(--line)}pre,textarea{width:100%;box-sizing:border-box}pre{max-height:180px;overflow:auto;padding:8px;background:var(--surface-soft);white-space:pre-wrap}textarea{min-height:58px}.danger,.error{color:var(--danger)}
</style>
