<template>
  <div class="page page-grid">
    <SectionHeader title="工作流编排" description="给企业用户创建可视化 Agent 工作流：串行、并行、循环、工具调用和审批节点都会放在这里。" />
    <section class="workflow-board card">
      <div class="workflow-node workflow-node--start">开始</div>
      <div class="workflow-line" />
      <div class="workflow-node">Agent 分析</div>
      <div class="workflow-line" />
      <div class="workflow-node">MCP 工具</div>
      <div class="workflow-line" />
      <div class="workflow-node workflow-node--end">输出</div>
    </section>
    <FeaturePlaceholder
      title="可视化 DAG 编辑器"
      description="后端工作流 DSL 和运行记录还没开放，前端先保留画布、节点库和发布入口。"
      status="待接工作流 API"
      :items="['拖拽节点创建 Agent 步骤', '配置每一步模型、Skill、MCP 和输入输出', '发布版本并绑定租户权限']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
</script>

<style scoped>
.workflow-board {
  display: flex;
  align-items: center;
  gap: 14px;
  overflow-x: auto;
  padding: 28px;
}

.workflow-node {
  min-width: 150px;
  padding: 22px;
  text-align: center;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  font-weight: 800;
}

.workflow-node--start {
  color: var(--success);
  background: var(--success-soft);
}

.workflow-node--end {
  color: var(--accent-deep);
  background: var(--accent-soft);
}

.workflow-line {
  width: 72px;
  height: 2px;
  background: var(--line-strong);
  flex: 0 0 auto;
}
</style>
