<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink class="sidebar__brand" to="/dashboard" title="总览">
        <span class="brand-mark">AI</span>
      </RouterLink>

      <nav class="sidebar__nav" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.path"
          class="nav-link"
          :data-label="item.status ? `${item.label} · ${item.status}` : item.label"
          :title="item.status ? `${item.label} · ${item.status}` : item.label"
          :to="item.path"
        >
          <component :is="item.icon" />
          <span class="sr-only">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar__footer">
        <RouterLink class="nav-avatar" to="/settings" title="账号设置">
          {{ avatarText }}
        </RouterLink>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="topbar__breadcrumb">
          <strong>{{ currentTitle }}</strong>
          <span>{{ currentSubtitle }}</span>
        </div>
        <div class="topbar__actions">
          <div class="user-chip">
            <span class="avatar">{{ avatarText }}</span>
            <span>{{ authStore.displayName }}</span>
          </div>
          <button class="button" type="button" @click="logout">退出</button>
        </div>
      </header>
      <RouterView />
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
  CalendarClock,
  Gauge,
  GitBranch,
  MessageSquareText,
  Settings,
} from '@lucide/vue';

import { useAuthStore } from '@/stores/auth';

interface NavItem {
  path: string;
  label: string;
  status?: string;
  icon: Component;
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const navItems: NavItem[] = [
  { path: '/dashboard', label: '总览', icon: Gauge },
  { path: '/chat', label: '智能体会话', icon: MessageSquareText },
  { path: '/agents', label: 'Agent 管理', icon: Bot },
  { path: '/workflow', label: '工作流编排', icon: GitBranch },
  { path: '/schedules', label: '定时任务', icon: CalendarClock },
  { path: '/mcp', label: 'MCP 中心', icon: Blocks },
  { path: '/skills', label: 'Skill 中心', icon: Bot },
  { path: '/observability', label: '可观测性', icon: Activity },
  { path: '/settings', label: '账号设置', icon: Settings },
];

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

const currentSubtitle = computed(() => {
  return 'AI Agent Scaffold · Minimal Console';
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
