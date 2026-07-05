<template>
  <div class="page page-grid">
    <SectionHeader
      title="租户与成员"
      description="多租户的核心入口：谁能发布公共 Skill / MCP / RAG，谁只能使用或发布个人资源，都应该在这里配置。"
    />

    <section class="stat-grid">
      <StatCard label="当前租户" :value="authStore.tenantId ? '已识别' : '--'" :hint="authStore.tenantId || 'JWT tenantId'" />
      <StatCard label="当前用户" :value="authStore.userId ? '已识别' : '--'" :hint="authStore.userId || 'JWT userId'" />
      <StatCard label="角色" :value="authStore.roleCode || '--'" hint="owner / admin / member" />
      <StatCard label="成员数" value="--" hint="待接 tenant_user" />
    </section>

    <FeaturePlaceholder
      title="成员与角色权限"
      description="后续 owner 可以邀请成员、调整角色、控制公共资源发布权限。"
      status="待接租户 API"
      :items="['邀请码加入已有租户', 'owner/admin/member 角色', '公共资源发布审批']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
import { useAuthStore } from '@/stores/auth';

const authStore = useAuthStore();
</script>
