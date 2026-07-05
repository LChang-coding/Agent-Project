<template>
  <div class="page page-grid">
    <SectionHeader
      title="Token 用量"
      description="第一阶段 token_usage 已经进入 Loki；当前页面先保留产品形态，后续可从数据库报表或日志查询服务读取。"
    >
      <template #actions>
        <a class="button button--primary" href="http://69.165.65.123:13000" target="_blank" rel="noreferrer">打开 Grafana</a>
      </template>
    </SectionHeader>

    <section class="stat-grid">
      <StatCard label="今日总量" value="--" hint="等待 model_usage 或 Loki 查询 API" />
      <StatCard label="Prompt" value="--" hint="promptTokens" />
      <StatCard label="Completion" value="--" hint="candidateTokens" />
      <StatCard label="模型错误" value="--" hint="model_error" />
    </section>

    <div class="card">
      <div class="card__body">
        <SectionHeader title="用量趋势" description="这里会展示按用户、模型、Agent 聚合的 token 趋势。" :level="2" />
        <div class="usage-bars">
          <span style="height: 42%" />
          <span style="height: 68%" />
          <span style="height: 52%" />
          <span style="height: 78%" />
          <span style="height: 61%" />
          <span style="height: 86%" />
          <span style="height: 74%" />
        </div>
      </div>
    </div>

    <FeaturePlaceholder
      title="按用户 / 模型聚合"
      description="后续会拉取 model_usage 表或日志聚合服务，展示 userId、modelVersion、agentName 维度。"
      status="待接报表 API"
      :items="['按 tenantId 限定数据边界', '按 userId 统计总 token', '按 modelVersion 统计模型消耗']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
</script>

<style scoped>
.usage-bars {
  display: flex;
  align-items: end;
  gap: 14px;
  height: 260px;
  margin-top: 22px;
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: var(--surface-muted);
}

.usage-bars span {
  flex: 1;
  min-width: 18px;
  border-radius: 999px 999px 8px 8px;
  background: linear-gradient(180deg, var(--accent), var(--gold));
}
</style>
