<template>
  <div class="page page-grid">
    <SectionHeader title="账号设置" description="这里使用当前 JWT 身份修改资料和密码，后端会按 TenantContextHolder 判断当前用户。" />

    <section class="page-grid page-grid--two">
      <form class="card" @submit.prevent="saveProfile">
        <div class="card__body form-grid">
          <SectionHeader title="个人资料" description="只允许修改昵称、邮箱、手机号和头像地址。" :level="2" />
          <div class="field">
            <label for="nickname">昵称</label>
            <input id="nickname" v-model.trim="profileForm.nickname" class="input" />
          </div>
          <div class="field">
            <label for="email">邮箱</label>
            <input id="email" v-model.trim="profileForm.email" class="input" type="email" />
          </div>
          <div class="field">
            <label for="phone">手机号</label>
            <input id="phone" v-model.trim="profileForm.phone" class="input" />
          </div>
          <div class="field">
            <label for="avatar">头像地址</label>
            <input id="avatar" v-model.trim="profileForm.avatar" class="input" />
          </div>
          <span v-if="profileMessage" class="badge badge--green">{{ profileMessage }}</span>
          <button class="button button--primary" type="submit">保存资料</button>
        </div>
      </form>

      <form class="card" @submit.prevent="savePassword">
        <div class="card__body form-grid">
          <SectionHeader title="修改密码" description="修改成功后，后端会禁用 refreshToken，前端也会清空登录态。" :level="2" />
          <div class="field">
            <label for="oldPassword">旧密码</label>
            <input id="oldPassword" v-model="passwordForm.oldPassword" class="input" type="password" />
          </div>
          <div class="field">
            <label for="newPassword">新密码</label>
            <input id="newPassword" v-model="passwordForm.newPassword" class="input" type="password" />
          </div>
          <span v-if="passwordMessage" class="error-text">{{ passwordMessage }}</span>
          <button class="button button--danger" type="submit">修改密码并重新登录</button>
        </div>
      </form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watchEffect } from 'vue';
import { useRouter } from 'vue-router';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const authStore = useAuthStore();
const profileMessage = ref('');
const passwordMessage = ref('');
const profileForm = reactive({
  nickname: '',
  email: '',
  phone: '',
  avatar: '',
});
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
});

watchEffect(() => {
  profileForm.nickname = authStore.profile?.nickname || '';
  profileForm.email = authStore.profile?.email || '';
  profileForm.phone = authStore.profile?.phone || '';
  profileForm.avatar = authStore.profile?.avatar || '';
});

/**
 * 保存个人资料；无参数；成功后刷新页面中的用户资料。
 */
async function saveProfile() {
  profileMessage.value = '';
  try {
    await authStore.updateProfile(profileForm);
    profileMessage.value = '资料已更新';
  } catch (error) {
    profileMessage.value = error instanceof Error ? error.message : '保存失败';
  }
}

/**
 * 修改当前密码；无参数；成功后跳回登录页。
 */
async function savePassword() {
  passwordMessage.value = '';
  try {
    await authStore.changePassword(passwordForm);
    await router.push({ name: 'login' });
  } catch (error) {
    passwordMessage.value = error instanceof Error ? error.message : '修改密码失败';
  }
}
</script>
