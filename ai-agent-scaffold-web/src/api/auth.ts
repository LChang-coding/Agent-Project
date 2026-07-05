import { request, saveAuthTokens } from '@/api/http';
import type {
  AuthTokenResponse,
  LoginRequest,
  RegisterRequest,
  RegisterResponse,
  UpdatePasswordRequest,
  UpdateProfileRequest,
  UserProfile,
} from '@/types/api';

/**
 * 注册新租户和初始管理员；参数是租户、账号和密码；返回新身份信息。
 */
export async function registerAccount(payload: RegisterRequest) {
  return request<RegisterResponse>({
    url: '/v1/auth/register',
    method: 'POST',
    data: payload,
  });
}

/**
 * 登录账号；参数是用户名和密码；返回访问令牌和刷新令牌。
 */
export async function loginAccount(payload: LoginRequest) {
  const result = await request<AuthTokenResponse>({
    url: '/v1/auth/login',
    method: 'POST',
    data: payload,
  });
  saveAuthTokens(result);
  return result;
}

/**
 * 查询当前登录用户；参数来自请求头令牌；返回用户资料。
 */
export async function queryCurrentUser() {
  return request<UserProfile>({
    url: '/v1/auth/me',
    method: 'GET',
  });
}

/**
 * 更新用户资料；参数是允许修改的资料字段；返回最新用户资料。
 */
export async function updateProfile(payload: UpdateProfileRequest) {
  return request<UserProfile>({
    url: '/v1/auth/profile',
    method: 'POST',
    data: payload,
  });
}

/**
 * 修改当前用户密码；参数是旧密码和新密码；返回最新用户资料。
 */
export async function changePassword(payload: UpdatePasswordRequest) {
  return request<UserProfile>({
    url: '/v1/auth/change_password',
    method: 'POST',
    data: payload,
  });
}
