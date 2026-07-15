<template>
  <div class="page page-grid">
    <SectionHeader
      title="上下文管理"
      description="结合供应商最近一次实际 Prompt Token 与 Context Manager 当前分层估算。"
    >
      <template #actions>
        <select class="select insight-select" :value="selectedSessionId" @change="selectSession">
          <option value="">请选择会话</option>
          <option v-for="session in chatStore.sessions" :key="session.sessionId" :value="session.sessionId">
            {{ session.title }} · {{ session.agentName }}
          </option>
        </select>
        <button class="button button--soft" type="button" :disabled="!selectedSessionId || insightStore.loadingSession" @click="refresh">
          {{ insightStore.loadingSession ? '刷新中' : '刷新' }}
        </button>
      </template>
    </SectionHeader>

    <span v-if="insightStore.sessionError" class="error-text">{{ insightStore.sessionError }}</span>

    <section class="stat-grid">
      <StatCard label="模型窗口" :value="formatTokens(context?.modelWindowTokens)" hint="当前模型可用窗口" />
      <StatCard label="当前占用" :value="formatTokens(displayContextTokens)" :hint="utilizationText" />
      <StatCard label="摘要占用" :value="formatTokens(context?.summaryTokens)" :hint="memoryHint" />
      <StatCard label="RAG 片段" :value="formatTokens(context?.ragTokens)" hint="当前后端返回的 RAG Token" />
    </section>

    <section class="page-grid page-grid--two">
      <article class="card">
        <div class="card__body">
          <SectionHeader title="上下文构成" description="按服务端实际组装结果分层。" :level="2" />
          <dl class="insight-list">
            <div><dt>系统指令</dt><dd>{{ formatTokens(context?.systemTokens) }}</dd></div>
            <div><dt>有效历史</dt><dd>{{ formatTokens(context?.historyTokens) }}</dd></div>
            <div><dt>压缩摘要</dt><dd>{{ formatTokens(context?.summaryTokens) }}</dd></div>
            <div><dt>上游上下文</dt><dd>{{ formatTokens(context?.upstreamTokens) }}</dd></div>
            <div><dt>工具结果</dt><dd>{{ formatTokens(context?.toolResultTokens) }}</dd></div>
            <div><dt>附件</dt><dd>{{ formatTokens(context?.attachmentTokens) }} · {{ context?.attachmentCount ?? 0 }} 个</dd></div>
          </dl>
        </div>
      </article>

      <article class="card">
        <div class="card__body">
          <SectionHeader title="上下文状态" description="用于判断压缩、裁剪与工具调用范围。" :level="2" />
          <dl class="insight-list">
            <div><dt>Context Revision</dt><dd>{{ context?.contextRevision ?? '--' }}</dd></div>
            <div><dt>Memory Version</dt><dd>{{ context?.memoryVersion ?? '--' }}</dd></div>
            <div><dt>压缩状态</dt><dd>{{ context?.compactionStatus || '--' }}</dd></div>
            <div><dt>有效序号</dt><dd>{{ sequenceText }}</dd></div>
            <div><dt>工具 / 调用</dt><dd>{{ context ? `${context.toolCount} / ${context.callCount}` : '--' }}</dd></div>
            <div><dt>裁剪原因</dt><dd>{{ context?.trimReason || '无' }}</dd></div>
          </dl>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
import { useChatStore } from '@/stores/chat';
import { useInsightStore } from '@/stores/insight';

const chatStore = useChatStore();
const insightStore = useInsightStore();
const selectedSessionId = ref('');
const context = computed(() => insightStore.context);
const displayContextTokens = computed(() => insightStore.usage?.latest?.promptTokens
  ?? context.value?.effectiveTokens);
const utilizationText = computed(() => {
  if (!context.value || displayContextTokens.value === undefined) {
    return '暂无数据';
  }
  const ratio = context.value.modelWindowTokens > 0
    ? (displayContextTokens.value / context.value.modelWindowTokens) * 100
    : 0;
  return `${insightStore.usage?.latest ? '供应商最近实际 Prompt' : 'Context Manager 当前估算'} · 窗口使用率 ${ratio.toFixed(1)}%`;
});
const memoryHint = computed(() => context.value?.memoryVersion ? `Memory v${context.value.memoryVersion}` : '当前未激活摘要');
const sequenceText = computed(() => {
  if (context.value?.effectiveFromSequence === undefined || context.value.effectiveToSequence === undefined) {
    return '--';
  }
  return `${context.value.effectiveFromSequence} - ${context.value.effectiveToSequence}`;
});

onMounted(async () => {
  if (chatStore.sessions.length === 0) {
    await chatStore.loadAgents();
  }
  selectedSessionId.value = chatStore.sessionId || chatStore.sessions[0]?.sessionId || '';
  await refresh();
});

/**
 * 选择统计会话；参数是下拉事件；刷新选中会话的真实洞察。
 */
async function selectSession(event: Event) {
  selectedSessionId.value = (event.target as HTMLSelectElement).value;
  await refresh();
}

/**
 * 刷新当前会话；无参数；从服务端重新读取上下文和用量。
 */
async function refresh() {
  await insightStore.loadSession(selectedSessionId.value);
}

/**
 * 格式化 Token 数；参数是可选数量；返回本地化展示。
 */
function formatTokens(value?: number) {
  return value === undefined || value === null ? '--' : value.toLocaleString('zh-CN');
}
</script>

<style scoped>
.insight-select {
  min-width: min(360px, 48vw);
}

.insight-list {
  display: grid;
  gap: 0;
  margin: 18px 0 0;
}

.insight-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line);
}

.insight-list dt {
  color: var(--muted);
}

.insight-list dd {
  margin: 0;
  font-weight: 800;
  text-align: right;
}
</style>
