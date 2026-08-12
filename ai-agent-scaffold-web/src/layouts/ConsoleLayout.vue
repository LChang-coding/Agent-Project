<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside class="sidebar" aria-label="平台导航">
      <div class="sidebar__header">
        <RouterLink class="sidebar__brand" to="/dashboard" title="返回总览">
          <span class="brand-mark" aria-hidden="true">A</span>
          <span class="brand-copy">
            <strong>Agent OS</strong>
            <small>Control plane</small>
          </span>
        </RouterLink>
        <div class="environment-state" title="当前运行环境正常">
          <span class="environment-state__dot" aria-hidden="true" />
          <span>MONOLITH / LIVE</span>
        </div>
      </div>

      <nav class="sidebar__nav" aria-label="主导航">
        <section v-for="group in navGroups" :key="group.label" class="nav-group">
          <span class="nav-group__label">{{ group.label }}</span>
          <RouterLink
            v-for="item in group.items"
            :key="item.path"
            class="nav-link"
            :title="item.label"
            :to="item.path"
          >
            <component :is="item.icon" aria-hidden="true" />
            <span class="nav-link__label">{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="sidebar__footer">
        <RouterLink class="sidebar-profile" to="/settings" title="账号设置">
          <span class="nav-avatar">{{ avatarText }}</span>
          <span class="sidebar-profile__copy">
            <strong>{{ authStore.displayName }}</strong>
            <small>{{ authStore.roleCode || '当前用户' }}</small>
          </span>
        </RouterLink>
      </div>
    </aside>

    <main id="main-content" class="main" tabindex="-1">
      <header class="topbar">
        <div class="topbar__breadcrumb">
          <span>{{ currentGroup }}</span>
          <strong>{{ currentTitle }}</strong>
        </div>
        <div class="topbar__actions">
          <div class="runtime-state" aria-label="运行时状态">
            <span aria-hidden="true" />
            运行正常
          </div>
          <button class="button topbar__logout" type="button" @click="logout">退出登录</button>
        </div>
      </header>
      <div class="main__viewport">
        <RouterView />
      </div>
      <ToolApprovalPanel />
    </main>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue';
import { computed } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import {
  Activity,
  Blocks,
  Bot,
  BrainCircuit,
  CalendarClock,
  Coins,
  Database,
  Gauge,
  GitBranch,
  MessageSquareText,
  Paperclip,
  Settings,
  Users,
} from '@lucide/vue';

import { useAuthStore } from '@/stores/auth';
import ToolApprovalPanel from '@/components/agent/ToolApprovalPanel.vue';

interface NavItem {
  path: string;
  label: string;
  icon: Component;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const navGroups: NavGroup[] = [
  {
    label: '运行工作台',
    items: [
      { path: '/dashboard', label: '总览', icon: Gauge },
      { path: '/chat', label: 'Agent 编排', icon: MessageSquareText },
      { path: '/schedules', label: '定时任务', icon: CalendarClock },
    ],
  },
  {
    label: '能力构建',
    items: [
      { path: '/agents', label: 'Agent 管理', icon: Bot },
      { path: '/workflow', label: '工作流编排', icon: GitBranch },
      { path: '/rag', label: 'RAG 知识库', icon: Database },
      { path: '/mcp', label: 'MCP 中心', icon: Blocks },
      { path: '/skills', label: 'Skill 中心', icon: BrainCircuit },
      { path: '/assets', label: '附件资产', icon: Paperclip },
    ],
  },
  {
    label: '治理与观测',
    items: [
      { path: '/context', label: '上下文管理', icon: BrainCircuit },
      { path: '/tokens', label: 'Token 用量', icon: Coins },
      { path: '/tenant', label: '租户与成员', icon: Users },
      { path: '/observability', label: '可观测性', icon: Activity },
      { path: '/settings', label: '账号设置', icon: Settings },
    ],
  },
];

const navItems = navGroups.flatMap((group) => group.items);

const routeLabels: Record<string, string> = {
  '/context': '上下文管理',
  '/tokens': 'Token 用量',
  '/rag': 'RAG 知识库',
  '/assets': '附件资产',
  '/tenant': '租户与成员',
};

const currentTitle = computed(() => {
  const item = navItems.find((nav) => route.path.startsWith(nav.path));
  return item?.label || routeLabels[route.path] || 'AI Agent Scaffold';
});

const currentGroup = computed(() => {
  const group = navGroups.find((item) => item.items.some((nav) => route.path.startsWith(nav.path)));
  return group?.label || '平台控制面';
});

const avatarText = computed(() => {
  const name = authStore.displayName || authStore.auth?.username || 'AI';
  return name.slice(0, 2).toUpperCase();
});

async function logout() {
  authStore.logout();
  await router.push({ name: 'login' });
}
</script>
