<template>
  <div class="page page-grid">
    <SectionHeader
      title="Skill 中心"
      description="Skill 会按版本号和 OSS 地址管理，服务端订阅配置变更后下载、校验并热更新。"
    >
      <template #actions>
        <button class="button button--primary" type="button" disabled>发布 Skill（占位）</button>
      </template>
    </SectionHeader>

    <section class="page-grid page-grid--two">
      <FeaturePlaceholder
        title="Skill 版本仓库"
        description="这里会展示每个 Skill 的版本、来源、哈希、发布人、可见范围和回滚记录。"
        status="待接 Nacos + OSS"
        :items="['配置 dataId / version / ossUrl', '记录变更前后内容', '热更新后保留旧版本可回滚']"
      />
      <FeaturePlaceholder
        title="运行时装配"
        description="Agent 会按租户和权限选择可用 Skill，避免用户调用自己无权使用的能力。"
        status="待接权限模型"
        :items="['企业公共 Skill', '个人私有 Skill', 'Agent 工作流步骤级授权']"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
</script>
