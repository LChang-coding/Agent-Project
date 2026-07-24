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
        title="唯一链路检索"
        description="从响应头 X-Trace-Id、工具调用或RAG审计中复制 traceId，在Grafana顶部输入后即可按时间正序还原整条业务链路。"
        status="中文业务节点已接入"
        :items="['HTTP → 会话运行 → Context Manager', 'RAG → 模型 → 工具 → 回答收口', 'traceId仅查询时解析，不写成Loki高基数标签']"
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
