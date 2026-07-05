<template>
  <section class="auth-page">
    <div class="auth-hero">
      <RouterLink class="auth-hero__brand" to="/auth/login">
        <span class="brand-mark">AI</span>
        <span>AI Agent Scaffold</span>
      </RouterLink>
      <div class="auth-hero__copy">
        <h1>让企业智能体从配置走向平台。</h1>
        <p>
          统一身份、会话隔离、观测日志和后续的 Skill / MCP / RAG 权限体系，会在这套控制台里逐步闭环。
        </p>
      </div>
      <div class="auth-hero__metrics">
        <div class="auth-metric">
          <strong>JWT</strong>
          <span>可信身份链路</span>
        </div>
        <div class="auth-metric">
          <strong>Loki</strong>
          <span>日志观测聚合</span>
        </div>
        <div class="auth-metric">
          <strong>ADK</strong>
          <span>智能体运行时</span>
        </div>
      </div>
    </div>

    <div class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <h2>登录控制台</h2>
        <p>使用后端账号登录，成功后会自动写入 Bearer Token 并恢复当前租户身份。</p>

        <div class="form-grid">
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model.trim="form.username" class="input" autocomplete="username" />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" class="input" type="password" autocomplete="current-password" />
          </div>
          <span v-if="errorMessage" class="error-text">{{ errorMessage }}</span>
          <button class="button button--primary" type="submit" :disabled="authStore.loading">
            {{ authStore.loading ? '登录中...' : '进入工作台' }}
          </button>
          <span>
            还没有账号？
            <RouterLink class="link" to="/auth/register">创建租户和管理员</RouterLink>
          </span>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const errorMessage = ref('');
const form = reactive({
  username: '',
  password: '',
});

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
