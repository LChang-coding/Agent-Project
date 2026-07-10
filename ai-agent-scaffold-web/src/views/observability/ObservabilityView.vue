<template>
  <div class="page page-grid">
    <SectionHeader
      title="可观测性"
      description="当前最小链路是本地 Logback 文件日志，经 Alloy tail 后推送到服务器 Loki，再由 Grafana 查询。"
    >
      <template #actions>
        <a class="button button--primary" href="http://69.165.65.123:13000" target="_blank" rel="noreferrer">打开 Grafana</a>
      </template>
    </SectionHeader>

    <section class="page-grid page-grid--two">
      <FeaturePlaceholder
        title="日志查询面板"
        description="这里后续可以内嵌 Grafana dashboard 或接一个后端日志查询代理，直接按 traceId、userId、sessionId 检索。"
        status="Grafana 已部署"
        :items="['token_usage 结构化日志', 'model_error 实时日志', 'auth_login / auth_register 业务日志']"
      />
      <div class="card">
        <div class="card__body">
          <SectionHeader title="当前日志链路" description="不用 Prometheus，不上数据库报表，先把日志观测跑通。" :level="2" />
          <div class="trace-flow">
            <span>Spring Boot</span>
            <span>Logback File</span>
            <span>Alloy</span>
            <span>Loki</span>
            <span>Grafana</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
</script>

<style scoped>
.trace-flow {
  display: grid;
  gap: 1px;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--line);
}

.trace-flow span {
  padding: 10px 12px;
  background: var(--surface);
  font-size: 13px;
  font-weight: 800;
}
</style>
