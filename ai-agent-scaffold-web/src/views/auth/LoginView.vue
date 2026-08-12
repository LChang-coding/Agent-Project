<template>
  <section class="auth-page">
    <AuthMediaShell
      eyebrow="ENTERPRISE AGENT CONTROL PLANE"
      title="编排智能，观测每一步执行。"
      description="在一个可控工作面中管理 Agent、工作流、RAG 与工具。每次运行都拥有可追溯的身份、节点和链路。"
      :metrics="loginMetrics"
    />

    <main class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <RouterLink class="auth-panel__brand" to="/auth/login" aria-label="Agent OS 首页">
          <span class="brand-mark" aria-hidden="true">A</span>
          <strong>Agent OS</strong>
        </RouterLink>
        <div class="auth-card__heading">
          <span>WELCOME BACK</span>
          <h2>登录控制台</h2>
          <p>进入你的租户工作区，继续管理运行中的智能体系统。</p>
        </div>

        <div class="form-grid">
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model.trim="form.username" class="input" autocomplete="username" required />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" class="input" type="password" autocomplete="current-password" required />
          </div>
          <span v-if="errorMessage" class="error-text" role="alert">{{ errorMessage }}</span>
          <button class="button button--primary auth-submit" type="submit" :disabled="authStore.loading">
            {{ authStore.loading ? '登录中...' : '进入工作台' }}
          </button>
          <span class="auth-switch">
            还没有账号？
            <RouterLink class="link" to="/auth/register">创建租户和管理员</RouterLink>
          </span>
        </div>
      </form>
    </main>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';

import AuthMediaShell from '@/components/auth/AuthMediaShell.vue';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const errorMessage = ref('');
const form = reactive({
  username: '',
  password: '',
});
const loginMetrics = [
  { value: '01', label: '身份与租户隔离' },
  { value: '02', label: '节点级执行追踪' },
  { value: '03', label: '知识与工具调度' },
];

/**
 * 提交登录表单；无参数；成功后进入原目标页面或总览。
 */
async function submit() {
  errorMessage.value = '';
  try {
    await authStore.login(form);
    await router.replace((route.query.redirect as string) || '/dashboard');
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败';
  }
}
</script>
