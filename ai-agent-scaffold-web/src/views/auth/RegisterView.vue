<template>
  <section class="auth-page">
    <div class="auth-hero">
      <RouterLink class="auth-hero__brand" to="/auth/login">
        <span class="brand-mark">AI</span>
        <span>AI Agent Scaffold</span>
      </RouterLink>
      <div class="auth-hero__copy">
        <h1>先有租户，再长出企业智能体生态。</h1>
        <p>
          第一版注册会创建一个独立租户，并把当前用户绑定为 owner，后续公共 RAG、MCP、Skill 和定时任务都会围绕这个租户授权。
        </p>
      </div>
      <div class="auth-hero__metrics">
        <div class="auth-metric">
          <strong>Owner</strong>
          <span>默认租户管理员</span>
        </div>
        <div class="auth-metric">
          <strong>Tenant</strong>
          <span>企业资源边界</span>
        </div>
        <div class="auth-metric">
          <strong>Policy</strong>
          <span>后续权限入口</span>
        </div>
      </div>
    </div>

    <div class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <h2>创建企业空间</h2>
        <p>注册成功后会自动登录，直接进入控制台。</p>

        <div class="form-grid">
          <div class="field">
            <label for="tenantName">企业 / 租户名称</label>
            <input id="tenantName" v-model.trim="form.tenantName" class="input" />
          </div>
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model.trim="form.username" class="input" autocomplete="username" />
          </div>
          <div class="field">
            <label for="nickname">昵称</label>
            <input id="nickname" v-model.trim="form.nickname" class="input" />
          </div>
          <div class="field">
            <label for="email">邮箱</label>
            <input id="email" v-model.trim="form.email" class="input" type="email" />
          </div>
          <div class="field">
            <label for="phone">手机号</label>
            <input id="phone" v-model.trim="form.phone" class="input" />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" class="input" type="password" autocomplete="new-password" />
          </div>
          <span v-if="errorMessage" class="error-text">{{ errorMessage }}</span>
          <button class="button button--primary" type="submit" :disabled="authStore.loading">
            {{ authStore.loading ? '创建中...' : '创建并登录' }}
          </button>
          <span>
            已有账号？
            <RouterLink class="link" to="/auth/login">返回登录</RouterLink>
          </span>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const authStore = useAuthStore();
const errorMessage = ref('');
const form = reactive({
  tenantName: '',
  username: '',
  password: '',
  nickname: '',
  email: '',
  phone: '',
});

/**
 * 提交注册表单；无参数；成功后自动登录并进入总览。
 */
async function submit() {
  errorMessage.value = '';
  try {
    await authStore.register(form);
    await authStore.login({ username: form.username, password: form.password });
    await router.replace('/dashboard');
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '注册失败';
  }
}
</script>
