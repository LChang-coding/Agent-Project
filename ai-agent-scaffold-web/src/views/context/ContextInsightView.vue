<template>
  <div class="page page-grid">
    <SectionHeader
      title="上下文管理"
      description="这里会成为 context manager 的可视化入口：展示每轮模型拿到的历史消息、摘要、RAG 片段、附件片段和工具结果。"
    />

    <section class="stat-grid">
      <StatCard label="模型窗口" value="128K" hint="示例容量，等待后端返回" />
      <StatCard label="当前占用" value="18.4K" hint="最近 N 轮 + 系统提示词" />
      <StatCard label="摘要占用" value="--" hint="摘要模块未接入" />
      <StatCard label="RAG 片段" value="--" hint="知识库模块未接入" />
    </section>

    <section class="page-grid page-grid--two">
      <FeaturePlaceholder
        title="上下文构成"
        description="后续每次对话都会记录上下文来源，让我们知道模型到底看到了什么。"
        status="待接 Context API"
        :items="['系统提示词 token 占比', '最近 N 轮消息 token 占比', '摘要 / RAG / 附件 / 工具结果分层展示']"
      />
      <FeaturePlaceholder
        title="上下文策略"
        description="这里会配置不同 Agent 的窗口控制策略，避免无限增长和跨租户污染。"
        status="规划中"
        :items="['最近 N 轮优先级', '超过阈值自动摘要', '敏感工具结果不进入长期记忆']"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
</script>
