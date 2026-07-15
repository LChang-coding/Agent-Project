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
  requestedRunId?: string;
  message: string;
}

export interface ChatResponse {
  sessionId: string;
  content: string;
  runId: string;
  runStatus: string;
  contextRevision: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: string;
  status?: 'sending' | 'streaming' | 'done' | 'error' | 'canceled' | 'superseded';
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
  contextRevision?: number;
}

export interface SessionListPage {
  items: Array<{
    sessionId: string;
    agentId: string;
    agentName: string;
    appName?: string;
    title: string;
    status: string;
    lastMessageTime: string;
    contextRevision: number;
  }>;
  nextCursor?: string;
  hasMore: boolean;
}

export interface SessionMessagePage {
  sessionId: string;
  items: Array<{
    messageId: string;
    runId?: string;
    role: 'user' | 'assistant' | 'system';
    contentType: string;
    content: string;
    estimatedTokenCount?: number;
    sequenceNo: number;
    createTime: string;
  }>;
  nextBeforeSequence?: number;
  hasMore: boolean;
}

export interface SessionDeleteResponse {
  sessionId: string;
  contextRevision: number;
}

export interface StreamHandlers {
  onSession?: (sessionId: string) => void;
  onRun?: (run: RunStreamEvent) => void;
  onChunk?: (content: string) => void;
  onError?: (message: string) => void;
  signal?: AbortSignal;
}

export interface RunStreamEvent {
  runId: string;
  status: string;
  contextRevision: number;
}

export interface RunControlResponse {
  runId: string;
  sessionId: string;
  status: string;
  contextRevision: number;
  successorRunId?: string;
}

export interface SessionShareMessage {
  id: string;
  role: 'user' | 'assistant';
  contentType: string;
  content: string;
  sequenceNo: number;
  createdAt?: string;
}

export interface SessionShareResponse {
  shareId: string;
  shareUrl?: string;
  downloadUrl?: string;
  status: string;
  expiresAt: string;
  maxDownloads: number;
  downloadCount: number;
  messageCount: number;
  title: string;
  sessionId?: string;
  agentId?: string;
  agentName?: string;
  appName?: string;
  messages?: SessionShareMessage[];
}

export type ScheduleMisfirePolicy = 'fire_once_now' | 'skip' | 'catch_up';

export interface ScheduleConfig {
  configId: string;
  agentId: string;
  agentName?: string;
  message: string;
  cronExpr: string;
  timezone: string;
  enabled: boolean;
  status: string;
  misfirePolicy: ScheduleMisfirePolicy;
  maxRetries: number;
  configVersion: number;
  lastReconciledAt?: string;
  createTime?: string;
  updateTime?: string;
}

export interface ScheduleSaveRequest {
  agentId: string;
  agentName?: string;
  message: string;
  cronExpr: string;
  timezone: string;
  enabled: boolean;
  misfirePolicy: ScheduleMisfirePolicy;
  maxRetries: number;
}

export interface ScheduleExecution {
  executionId: string;
  traceId?: string;
  plannedTime: string;
  attemptNo: number;
  status: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  errorMessage?: string;
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

export interface SkillPackageUploadResponse {
  assetId: string;
  bucket: string;
  objectKey: string;
  fileName: string;
  sha256: string;
  sizeBytes: number;
}

export interface SkillCreateRequest {
  skillName: string;
  skillCode?: string;
  description?: string;
  visibility?: 'private' | 'tenant_public';
  version?: string;
  assetId: string;
}

export interface SkillVersionCreateRequest {
  version?: string;
  assetId: string;
}

export interface SkillDefinition {
  skillId: string;
  skillName: string;
  skillCode: string;
  description?: string;
  visibility: string;
  currentVersion?: string;
  publishedVersion?: string;
  status: string;
}

export interface McpCreateRequest {
  mcpName: string;
  description?: string;
  visibility?: 'private' | 'tenant_public';
  version?: string;
  transportType: 'http' | 'sse' | 'stdio' | 'local';
  endpoint?: string;
  command?: string;
  args?: string;
  env?: string;
}

export interface McpDefinition {
  mcpId: string;
  mcpName: string;
  description?: string;
  visibility: string;
  transportType: string;
  endpoint?: string;
  currentVersion?: string;
  publishedVersion?: string;
  testStatus?: string;
  testMessage?: string;
  lastTestTime?: string;
  status: string;
}

export interface ToolPublishRequest {
  version?: string;
}

export interface ToolCatalogItem {
  toolType: 'skill' | 'mcp';
  toolId: string;
  toolName: string;
  toolCode?: string;
  description?: string;
  version?: string;
  visibility: string;
}

export interface ToolCallLog {
  toolType: string;
  toolId: string;
  toolName: string;
  version?: string;
  invocationId?: string;
  traceId?: string;
  status: string;
  errorType?: string;
  errorMessage?: string;
  costMs?: number;
  createTime?: string;
}
