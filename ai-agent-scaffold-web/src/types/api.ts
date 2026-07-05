export interface ApiResponse<T> {
  code: string;
  info: string;
  data?: T;
}

export interface RegisterRequest {
  tenantName: string;
  username: string;
  password: string;
  nickname?: string;
  email?: string;
  phone?: string;
}

export interface RegisterResponse {
  tenantId: string;
  userId: string;
  username: string;
  roleCode: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AuthTokenResponse {
  token: string;
  refreshToken?: string;
  tokenType: string;
  expiresIn: number;
  refreshExpiresIn?: number;
  tenantId: string;
  userId: string;
  username: string;
  roleCode: string;
}

export interface UserProfile {
  tenantId: string;
  userId: string;
  username: string;
  nickname?: string;
  email?: string;
  phone?: string;
  avatar?: string;
  roleCode: string;
}

export interface UpdateProfileRequest {
  nickname?: string;
  email?: string;
  phone?: string;
  avatar?: string;
}

export interface UpdatePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface AiAgentConfig {
  agentId: string;
  agentName: string;
  agentDesc?: string;
}

export interface CreateSessionRequest {
  agentId: string;
  userId?: string;
}

export interface CreateSessionResponse {
  sessionId: string;
}

export interface ChatRequest {
  agentId: string;
  userId?: string;
  sessionId?: string;
  message: string;
}

export interface ChatResponse {
  sessionId: string;
  content: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: string;
  status?: 'sending' | 'streaming' | 'done' | 'error';
}

export interface LocalChatSession {
  sessionId: string;
  agentId: string;
  agentName: string;
  title: string;
  updatedAt: string;
  messages: ChatMessage[];
}

export interface StreamHandlers {
  onSession?: (sessionId: string) => void;
  onChunk?: (content: string) => void;
  onError?: (message: string) => void;
  signal?: AbortSignal;
}
