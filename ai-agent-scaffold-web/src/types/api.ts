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
  agentId?: string;
  workflowId?: string;
  workflowVersion?: number;
  modelCode?: string;
  userId?: string;
}

export interface CreateSessionResponse {
  sessionId: string;
}

export interface ChatRequest {
  agentId?: string;
  workflowId?: string;
  workflowVersion?: number;
  modelCode?: string;
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
  sourceType?: 'agent' | 'workflow';
  workflowId?: string;
  workflowName?: string;
  modelCode?: string;
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

export interface WorkflowSummary {
  workflowId: string;
  workflowName: string;
  description?: string;
  visibility: string;
  status: string;
  defaultModelCode: string;
  currentVersion: number;
  publishedVersion: number;
}

export interface WorkflowGraph {
  mode: 'sequential' | 'parallel' | 'loop';
  rootNodeId: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

export interface WorkflowNode {
  nodeId: string;
  nodeType: 'llm' | 'sequential' | 'parallel' | 'loop';
  name: string;
  description?: string;
  instruction?: string;
  modelCode?: string;
  mcpIds?: string[];
  skillIds?: string[];
  maxIterations?: number;
  x?: number;
  y?: number;
}

export interface WorkflowEdge {
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
}

export interface WorkflowDetail {
  workflow: WorkflowSummary;
  version: number;
  versionStatus: string;
  graph: WorkflowGraph;
}

export interface WorkflowOption {
  value: string;
  label: string;
  description?: string;
  type?: string;
  status?: string;
}

export interface WorkflowNodeOptions {
  nodeTypes: WorkflowOption[];
  models: WorkflowOption[];
  mcpServers: WorkflowOption[];
  skills: WorkflowOption[];
}

export interface WorkflowCreateRequest {
  workflowName: string;
  description?: string;
  defaultModelCode?: string;
  visibility?: string;
}

export interface WorkflowSaveDraftRequest {
  workflowName: string;
  description?: string;
  defaultModelCode?: string;
  visibility?: string;
  graph: WorkflowGraph;
}
