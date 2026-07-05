<template>
  <div class="page page-grid">
    <SectionHeader title="RAG 知识库" description="企业公共知识库和个人私有知识库都会在这里配置，当前先占位数据模型和操作入口。" />

    <section class="stat-grid">
      <StatCard label="知识库" value="--" hint="rag_knowledge_base" />
      <StatCard label="文档" value="--" hint="rag_document" />
      <StatCard label="切片" value="--" hint="rag_chunk" />
      <StatCard label="向量索引" value="--" hint="后续接入" />
    </section>

    <FeaturePlaceholder
      title="知识库生命周期"
      description="后续会支持上传、解析、切片、向量化、召回测试和租户权限控制。"
      status="待接 RAG 服务"
      :items="['企业公共知识库供员工复用', '个人知识库仅本人可见', '聊天时按 Agent 策略注入上下文']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
</script>
