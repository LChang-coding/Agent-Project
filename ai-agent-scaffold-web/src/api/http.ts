import axios, {
  AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';

import type { ApiResponse, AuthTokenResponse } from '@/types/api';

const SUCCESS_CODE = '0000';
const UNAUTHORIZED_CODE = 'AUTH_UNAUTHORIZED';
const TOKEN_KEY = 'ai_agent_scaffold_access_token';
const REFRESH_TOKEN_KEY = 'ai_agent_scaffold_refresh_token';
const AUTH_META_KEY = 'ai_agent_scaffold_auth_meta';

type AuthChangedListener = (auth: AuthTokenResponse | null) => void;

interface RetryRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

export class ApiError extends Error {
  code: string;
  info: string;
  traceId: string;

  constructor(code: string, info: string, traceId = '') {
    super(info);
    this.name = 'ApiError';
    this.code = code;
    this.info = info;
    this.traceId = traceId;
  }
}

let authChangedListener: AuthChangedListener | null = null;
let refreshPromise: Promise<AuthTokenResponse> | null = null;

export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 120_000,
});

const refreshClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30_000,
});

export function setAuthChangedListener(listener: AuthChangedListener) {
  authChangedListener = listener;
}

export function getAccessToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function getRefreshToken() {
  const token = localStorage.getItem(REFRESH_TOKEN_KEY) || '';
  return token === 'undefined' || token === 'null' ? '' : token;
}

export function getStoredAuthMeta() {
  const raw = localStorage.getItem(AUTH_META_KEY);
  return raw ? (JSON.parse(raw) as Omit<AuthTokenResponse, 'token' | 'refreshToken'>) : null;
}

export function saveAuthTokens(auth: AuthTokenResponse) {
  localStorage.setItem(TOKEN_KEY, auth.token);
  if (auth.refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, auth.refreshToken);
  } else {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }
  localStorage.setItem(
    AUTH_META_KEY,
    JSON.stringify({
      tokenType: auth.tokenType,
      expiresIn: auth.expiresIn,
      refreshExpiresIn: auth.refreshExpiresIn,
      tenantId: auth.tenantId,
      userId: auth.userId,
      username: auth.username,
      roleCode: auth.roleCode,
    }),
  );
  authChangedListener?.(auth);
}

export function clearAuthStorage() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(AUTH_META_KEY);
  authChangedListener?.(null);
}

httpClient.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

httpClient.interceptors.response.use(
  async (response) => {
    const config = response.config as RetryRequestConfig;
    const payload = response.data as ApiResponse<unknown> | undefined;
    if (payload?.code === UNAUTHORIZED_CODE && !config._retry) {
      await refreshAccessToken();
      config._retry = true;
      return httpClient.request(config);
    }
    return response;
  },
  async (error: AxiosError<ApiResponse<unknown>>) => {
    const config = error.config as RetryRequestConfig | undefined;
    if (error.response?.status === 401 && config && !config._retry) {
      await refreshAccessToken();
      config._retry = true;
      return httpClient.request(config);
    }
    throw error;
  },
);

export async function request<T>(config: AxiosRequestConfig) {
  return (await requestWithTrace<T>(config)).data;
}

export interface TracedResult<T> {
  data: T;
  traceId: string;
}

export function resolveTraceId(headers?: unknown, body?: ApiResponse<unknown>) {
  let headerTraceId = '';
  if (headers && typeof headers === 'object') {
    const record = headers as Record<string, unknown>;
    if (typeof record.get === 'function') {
      const value = (record.get as (name: string) => unknown)('x-trace-id');
      headerTraceId = typeof value === 'string' ? value : '';
    }
    if (!headerTraceId) {
      const value = record['x-trace-id'] || record['X-Trace-Id'];
      headerTraceId = typeof value === 'string' ? value : '';
    }
  }
  return headerTraceId || body?.traceId || '';
}

export function traceIdOfError(error: unknown) {
  if (error instanceof ApiError) {
    return error.traceId;
  }
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    return resolveTraceId(error.response?.headers, error.response?.data);
  }
  return '';
}

export async function requestWithTrace<T>(config: AxiosRequestConfig): Promise<TracedResult<T>> {
  const response = await httpClient.request<ApiResponse<T>>(config);
  const body = response.data;
  const traceId = resolveTraceId(response.headers, body);
  if (body.code !== SUCCESS_CODE) {
    throw new ApiError(body.code, body.info || '请求失败', traceId);
  }
  return { data: body.data as T, traceId };
}

export async function refreshAccessToken() {
  if (refreshPromise) {
    return refreshPromise;
  }
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    clearAuthStorage();
    throw new ApiError(UNAUTHORIZED_CODE, '登录已过期，请重新登录');
  }

  refreshPromise = refreshClient
    .post<ApiResponse<AuthTokenResponse>>('/v1/auth/refresh', { refreshToken })
    .then((response) => {
      const body = response.data;
      if (body.code !== SUCCESS_CODE || !body.data) {
        clearAuthStorage();
        throw new ApiError(body.code, body.info || '令牌续期失败', resolveTraceId(response.headers, body));
      }
      saveAuthTokens(body.data);
      return body.data;
    })
    .catch((error) => {
      clearAuthStorage();
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(UNAUTHORIZED_CODE, '登录已过期，请重新登录');
    })
    .finally(() => {
      refreshPromise = null;
    });

  return refreshPromise;
}
