<template>
  <div class="page page-grid">
    <SectionHeader
      title="附件资产"
      description="聊天附件、知识库源文件、工作流产物和模型输出文件会统一进入 artifact_asset。"
    >
      <template #actions>
        <button class="button button--primary" type="button" disabled>上传文件（占位）</button>
      </template>
    </SectionHeader>

    <FeaturePlaceholder
      title="OSS 文件资产"
      description="这里会追踪文件归属、来源会话、解析状态、OSS 地址和引用关系。"
      status="待接 OSS"
      :items="['聊天会话附件', 'RAG 原始文档', 'Agent 生成产物', '租户级存储配额']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
</script>
