<template>
  <section class="auth-page">
    <AuthMediaShell
      eyebrow="CREATE A SECURE TENANT"
      title="从清晰的企业边界开始。"
      description="创建独立租户和 Owner 身份。Agent、RAG、MCP、Skill 与定时任务将围绕同一权限边界组织。"
      :metrics="registerMetrics"
    />

    <main class="auth-panel auth-panel--register">
      <form class="auth-card auth-card--register" @submit.prevent="submit">
        <RouterLink class="auth-panel__brand" to="/auth/login" aria-label="Agent OS 首页">
          <span class="brand-mark" aria-hidden="true">A</span>
          <strong>Agent OS</strong>
        </RouterLink>
        <div class="auth-card__heading">
          <span>NEW WORKSPACE</span>
          <h2>创建企业空间</h2>
          <p>一次完成租户与管理员初始化，成功后自动进入控制台。</p>
        </div>

        <div class="form-grid auth-register-grid">
          <div class="field">
            <label for="tenantName">企业 / 租户名称</label>
            <input id="tenantName" v-model.trim="form.tenantName" class="input" autocomplete="organization" required />
          </div>
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model.trim="form.username" class="input" autocomplete="username" required />
          </div>
          <div class="field">
            <label for="nickname">昵称</label>
            <input id="nickname" v-model.trim="form.nickname" class="input" autocomplete="nickname" />
          </div>
          <div class="field">
            <label for="email">邮箱</label>
            <input id="email" v-model.trim="form.email" class="input" type="email" autocomplete="email" />
          </div>
          <div class="field">
            <label for="phone">手机号</label>
            <input id="phone" v-model.trim="form.phone" class="input" type="tel" autocomplete="tel" />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" class="input" type="password" autocomplete="new-password" required />
          </div>
          <span v-if="errorMessage" class="error-text auth-register-grid__wide" role="alert">{{ errorMessage }}</span>
          <button class="button button--primary auth-submit auth-register-grid__wide" type="submit" :disabled="authStore.loading">
            {{ authStore.loading ? '创建中...' : '创建并登录' }}
          </button>
          <span class="auth-switch auth-register-grid__wide">
            已有账号？
            <RouterLink class="link" to="/auth/login">返回登录</RouterLink>
          </span>
        </div>
      </form>
    </main>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';

import AuthMediaShell from '@/components/auth/AuthMediaShell.vue';
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
const registerMetrics = [
  { value: 'OWNER', label: '初始管理者' },
  { value: 'TENANT', label: '企业资源边界' },
  { value: 'POLICY', label: '后续授权入口' },
];

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
