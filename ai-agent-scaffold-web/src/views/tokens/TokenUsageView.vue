<template>
  <div class="page page-grid">
    <SectionHeader
      title="Token 用量"
      description="展示当前登录用户过去 24 小时的模型调用与 Token 真实聚合。"
    >
      <template #actions>
        <button class="button button--soft" type="button" :disabled="insightStore.loadingRecent" @click="refresh">
          {{ insightStore.loadingRecent ? '刷新中' : '刷新' }}
        </button>
        <a class="button button--primary" href="http://69.165.65.123:13000" target="_blank" rel="noreferrer">打开 Grafana</a>
      </template>
    </SectionHeader>

    <span v-if="insightStore.recentError" class="error-text">{{ insightStore.recentError }}</span>

    <section class="stat-grid">
      <StatCard label="24h 总量" :value="formatTokens(summary?.totalTokens)" :hint="`${formatCount(summary?.callCount)} 次模型调用`" />
      <StatCard label="Prompt" :value="formatTokens(summary?.promptTokens)" hint="promptTokens" />
      <StatCard label="Completion" :value="formatTokens(summary?.candidateTokens)" hint="candidateTokens" />
      <StatCard label="模型错误" :value="formatCount(summary?.failedCount)" hint="failed calls" />
    </section>

    <section class="page-grid page-grid--two">
      <article class="card">
        <div class="card__body">
          <SectionHeader title="调用结果" description="最近一天的成功与失败调用数。" :level="2" />
          <div class="usage-split">
            <div><span>成功</span><strong>{{ formatCount(summary?.successCount) }}</strong></div>
            <div><span>失败</span><strong>{{ formatCount(summary?.failedCount) }}</strong></div>
          </div>
        </div>
      </article>

      <article class="card">
        <div class="card__body">
          <SectionHeader title="扩展 Token" description="模型思考与工具使用提示的独立计数。" :level="2" />
          <div class="usage-split">
            <div><span>Thoughts</span><strong>{{ formatTokens(summary?.thoughtsTokens) }}</strong></div>
            <div><span>Tool Use Prompt</span><strong>{{ formatTokens(summary?.toolUsePromptTokens) }}</strong></div>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
import { useInsightStore } from '@/stores/insight';

const insightStore = useInsightStore();
const summary = computed(() => insightStore.recent);

onMounted(() => refresh());

/**
 * 刷新 24 小时用量；无参数；从服务端读取当前用户聚合。
 */
async function refresh() {
  await insightStore.loadRecent(1);
}

/**
 * 格式化 Token 数；参数是可选数量；无统计时返回占位符。
 */
function formatTokens(value?: number) {
  return value === undefined || value === null ? '--' : value.toLocaleString('zh-CN');
}

/**
 * 格式化调用数；参数是可选数量；无统计时返回占位符。
 */
function formatCount(value?: number) {
  return value === undefined || value === null ? '--' : value.toLocaleString('zh-CN');
}
</script>

<style scoped>
.usage-split {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-top: 18px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--line);
}

.usage-split div {
  display: grid;
  gap: 8px;
  padding: 18px;
  background: var(--surface);
}

.usage-split span {
  color: var(--muted);
  font-size: 12px;
}

.usage-split strong {
  font-size: 24px;
}
</style>
