import { defineStore } from 'pinia';

import {
  changePassword,
  loginAccount,
  queryCurrentUser,
  registerAccount,
  updateProfile,
} from '@/api/auth';
import {
  clearAuthStorage,
  getAccessToken,
  getRefreshToken,
  getStoredAuthMeta,
  setAuthChangedListener,
} from '@/api/http';
import type {
  AuthTokenResponse,
  LoginRequest,
  RegisterRequest,
  UpdatePasswordRequest,
  UpdateProfileRequest,
  UserProfile,
} from '@/types/api';

interface AuthState {
  auth: AuthTokenResponse | null;
  profile: UserProfile | null;
  bootstrapped: boolean;
  loading: boolean;
  profileLoading: boolean;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    auth: null,
    profile: null,
    bootstrapped: false,
    loading: false,
    profileLoading: false,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.auth?.token),
    displayName: (state) => state.profile?.nickname || state.auth?.username || '未登录用户',
    tenantId: (state) => state.profile?.tenantId || state.auth?.tenantId || '',
    userId: (state) => state.profile?.userId || state.auth?.userId || '',
    roleCode: (state) => state.profile?.roleCode || state.auth?.roleCode || '',
  },
  actions: {
    /**
     * 绑定 HTTP 令牌变更；无参数；让自动续期后同步刷新页面状态。
     */
    bindHttpListener() {
      setAuthChangedListener((auth) => {
        this.auth = auth;
        if (!auth) {
          this.profile = null;
        }
      });
    },

    /**
     * 从本地令牌恢复登录；无参数；成功后返回当前用户资料。
     */
    async bootstrap() {
      if (this.bootstrapped) {
        return this.profile;
      }

      const token = getAccessToken();
      const refreshToken = getRefreshToken();
      const meta = getStoredAuthMeta();
      if (!token || !meta) {
        this.bootstrapped = true;
        return null;
      }

      this.auth = {
        ...meta,
        token,
        refreshToken,
      };
      this.profile = profileFromAuth(this.auth);

      try {
        await this.reloadProfile();
        return this.profile;
      } catch (error) {
        if (isUnauthorizedError(error)) {
          this.logout();
          return null;
        }
        return this.profile;
      } finally {
        this.bootstrapped = true;
      }
    },

    /**
     * 注册租户和用户；参数是注册表单；返回注册结果。
     */
    async register(payload: RegisterRequest) {
      this.loading = true;
      try {
        return await registerAccount(payload);
      } finally {
        this.loading = false;
      }
    },

    /**
     * 登录账号；参数是用户名和密码；返回令牌信息。
     */
    async login(payload: LoginRequest) {
      this.loading = true;
      try {
        const auth = await loginAccount(payload);
        this.auth = auth;
        this.profile = profileFromAuth(auth);
        this.bootstrapped = true;
        void this.reloadProfileSilently();
        return auth;
      } finally {
        this.loading = false;
        this.bootstrapped = true;
      }
    },

    /**
     * 退出登录；无参数；清空本地令牌和用户资料。
     */
    logout() {
      clearAuthStorage();
      this.auth = null;
      this.profile = null;
      this.bootstrapped = true;
    },

    /**
     * 更新个人资料；参数是允许修改的字段；返回最新资料。
     */
    async updateProfile(payload: UpdateProfileRequest) {
      this.profile = await updateProfile(payload);
      return this.profile;
    },

    /**
     * 重新加载当前用户资料；无参数；成功后返回用户资料。
     */
    async reloadProfile() {
      this.profileLoading = true;
      try {
        this.profile = await queryCurrentUser();
        return this.profile;
      } finally {
        this.profileLoading = false;
      }
    },

    /**
     * 静默加载用户资料；无参数；失败不阻塞登录和页面跳转。
     */
    async reloadProfileSilently() {
      try {
        return await this.reloadProfile();
      } catch {
        if (this.auth && !this.profile) {
          this.profile = profileFromAuth(this.auth);
        }
        return null;
      }
    },

    /**
     * 修改密码；参数是旧密码和新密码；成功后清理本地登录态。
     */
    async changePassword(payload: UpdatePasswordRequest) {
      const profile = await changePassword(payload);
      this.logout();
      return profile;
    },
  },
});

/**
 * 用令牌身份生成基础用户资料；参数是登录令牌；返回可展示的用户资料。
 */
function profileFromAuth(auth: AuthTokenResponse): UserProfile {
  return {
    tenantId: auth.tenantId,
    userId: auth.userId,
    username: auth.username,
    nickname: auth.username,
    roleCode: auth.roleCode,
  };
}

/**
 * 判断是否为未登录错误；参数是任意异常；返回是否需要清理登录态。
 */
function isUnauthorizedError(error: unknown) {
  const candidate = error as { code?: string; response?: { status?: number } };
  return candidate?.code === 'AUTH_UNAUTHORIZED' || candidate?.response?.status === 401 || candidate?.response?.status === 403;
}
