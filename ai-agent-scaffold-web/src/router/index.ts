import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

import ConsoleLayout from '@/layouts/ConsoleLayout.vue';
import { useAuthStore } from '@/stores/auth';

const routes: RouteRecordRaw[] = [
  {
    path: '/auth/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { guest: true },
  },
  {
    path: '/auth/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { guest: true },
  },
  {
    path: '/',
    component: ConsoleLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
      { path: 'chat', name: 'chat', component: () => import('@/views/chat/ChatWorkspaceView.vue') },
      { path: 'agents', name: 'agents', component: () => import('@/views/agent/AgentConfigView.vue') },
      { path: 'share/:token', name: 'session-share', component: () => import('@/views/chat/SessionShareView.vue') },
      { path: 'context', name: 'context', component: () => import('@/views/context/ContextInsightView.vue') },
      { path: 'tokens', name: 'tokens', component: () => import('@/views/tokens/TokenUsageView.vue') },
      { path: 'workflow', name: 'workflow', component: () => import('@/views/workflow/WorkflowBuilderView.vue') },
      { path: 'schedules', name: 'schedules', component: () => import('@/views/schedule/ScheduleManagerView.vue') },
      { path: 'mcp', name: 'mcp', component: () => import('@/views/mcp/McpRegistryView.vue') },
      { path: 'skills', name: 'skills', component: () => import('@/views/skills/SkillRegistryView.vue') },
      { path: 'rag', name: 'rag', component: () => import('@/views/rag/KnowledgeBaseView.vue') },
      { path: 'assets', name: 'assets', component: () => import('@/views/assets/AssetCenterView.vue') },
      { path: 'tenant', name: 'tenant', component: () => import('@/views/tenant/TenantMembersView.vue') },
      { path: 'observability', name: 'observability', component: () => import('@/views/observability/ObservabilityView.vue') },
      { path: 'settings', name: 'settings', component: () => import('@/views/settings/ProfileSettingsView.vue') },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

const routeTitles: Record<string, string> = {
  login: '登录',
  register: '创建企业空间',
  dashboard: '总览',
  chat: 'Agent 编排工作台',
  agents: 'Agent 管理',
  workflow: '工作流编排',
  schedules: '定时任务',
  mcp: 'MCP 中心',
  skills: 'Skill 中心',
  rag: 'RAG 知识库',
  assets: '附件资产',
  tenant: '租户与成员',
  context: '上下文管理',
  tokens: 'Token 用量',
  observability: '可观测性',
  settings: '账号设置',
};

router.afterEach((to) => {
  const title = routeTitles[String(to.name || '')];
  document.title = title ? `${title} · Agent OS` : 'Agent OS · 企业智能体控制面';
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  await authStore.bootstrap();

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  if (to.meta.guest && authStore.isLoggedIn) {
    return { name: 'dashboard' };
  }

  return true;
});

export default router;
