<template>
  <div class="page page-grid">
    <SectionHeader
      title="MCP 中心"
      description="企业公共 MCP 与个人私有 MCP 会在这里统一发布、审核、版本化和授权。"
    >
      <template #actions>
        <button class="button button--primary" type="button" disabled>发布 MCP（占位）</button>
      </template>
    </SectionHeader>

    <div class="table-card">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>可见范围</th>
            <th>版本</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in mockMcp" :key="item.name">
            <td>{{ item.name }}</td>
            <td>{{ item.scope }}</td>
            <td>{{ item.version }}</td>
            <td><span class="badge badge--gold">{{ item.status }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <FeaturePlaceholder
      title="MCP 发布与权限"
      description="后续 owner/admin 可以发布企业公共 MCP，普通员工只能发布个人私有 MCP。"
      status="待接注册中心"
      :items="['配置 MCP Server 地址和认证方式', '按租户、角色、用户授权', '运行时按 Agent 工作流动态装配']"
    />
  </div>
</template>

<script setup lang="ts">
import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';

const mockMcp = [
  { name: '企业订单查询', scope: '企业公共', version: 'v1.0.0', status: '规划中' },
  { name: '个人文件检索', scope: '个人私有', version: 'v0.1.0', status: '规划中' },
];
</script>
