export interface ApiResponse<T> {
  code: string;
  info: string;
  traceId?: string;
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
  orchestrationRole?: 'NORMAL' | 'SUPERVISOR';
  category?: string;
  bestFor?: string[];
  notFor?: string[];
  capabilities?: string[];
  allowedSubAgentIds?: string[];
}

export interface AgentConfigItem extends AiAgentConfig {
  sourceType: 'static_config' | 'database';
  status: 'enabled' | 'disabled';
  manageable: boolean;
  revision: number;
  disabledAt?: string;
  toolPermissions?: AgentToolPermission[];
}

export type AgentToolPermissionMode = 'ALLOW' | 'DENY' | 'REQUIRE_APPROVAL';

export interface AgentToolPermission {
  toolCode: string;
  toolName?: string;
  toolType?: 'platform' | 'mcp' | 'skill';
  description?: string;
  mode: AgentToolPermissionMode;
  timeoutSeconds: number;
  timeoutDecision: 'APPROVE' | 'REJECT';
  suggestions: string[];
  revision: number;
}

export interface AgentToolPermissionUpdateRequest {
  mode: AgentToolPermissionMode;
  timeoutSeconds: number;
  timeoutDecision: 'APPROVE' | 'REJECT';
  suggestions: string[];
  expectedRevision: number;
}

export interface ToolApprovalRequest {
  sequence: number;
  approvalId: string;
  parentAgentId: string;
  parentRunId: string;
  sourceRunId: string;
  parentSessionId: string;
  traceId?: string;
  toolCode: string;
  requestedInput: Record<string, unknown>;
  suggestions: string[];
  allowedSubAgentIds: string[];
  timeoutDecision: 'APPROVE' | 'REJECT';
  status: 'PENDING';
  expiresAt: string;
  revision: number;
}

export type SessionOrchestrationPhase = 'IDLE' | 'WAITING_APPROVAL' | 'EXECUTING' | 'SUMMARIZING'
  | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'CANCELLED';

export interface SubagentTaskView {
  taskId: string;
  childAgentId: string;
  childSessionId?: string;
  childRunId?: string;
  childRunTraceId?: string;
  instruction: string;
  traceId?: string;
  status: 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'ACKED';
  callbackStatus?: string;
  attempt?: number;
  resultSummary?: string;
  fullContext?: string;
  errorCode?: string;
  createdAt?: string;
  completedAt?: string;
}

export interface SubagentRunView {
  parentRunId: string;
  parentAgentId: string;
  phase: SessionOrchestrationPhase;
  createdAt?: string;
  completedAt?: string;
  tasks: SubagentTaskView[];
}

export interface SessionOrchestrationSnapshot {
  sessionId: string;
  version: string;
  active: boolean;
  inputLocked: boolean;
  phase: SessionOrchestrationPhase;
  currentRunId?: string;
  runs: SubagentRunView[];
  approvals: Array<{ approvalId: string; parentRunId: string; parentAgentId: string; toolCode: string; expiresAt: string; revision: number }>;
}

export type ToolApprovalDecision = 'APPROVE' | 'REJECT' | 'APPROVE_WITH_CHANGES' | 'REPLAN';

export interface AgentStatusUpdateRequest {
  enabled: boolean;
  reason?: string;
  expectedRevision?: number;
}

export interface AgentMutationResponse {
  agentId: string;
  status: 'enabled' | 'disabled';
  revision: number;
  updatedAt?: string;
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
  attachmentIds?: string[];
  message: string;
}

export interface ChatResponse {
  sessionId: string;
  content: string;
  runId: string;
  runStatus: string;
  contextRevision: number;
  traceId?: string;
}

export interface ChatMessage {
  id: string;
  runId?: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: string;
  traceId?: string;
  status?: 'sending' | 'streaming' | 'done' | 'error' | 'canceled' | 'superseded';
  /** 浏览器乐观消息；同一 runId 的服务端消息到达后应被置换。 */
  localOnly?: boolean;
}

export interface LocalChatSession {
  sessionId: string;
  agentId: string;
  agentName: string;
  sourceType?: 'agent' | 'workflow';
  workflowId?: string;
  workflowName?: string;
  workflowVersion?: number;
  modelCode?: string;
  title: string;
  updatedAt: string;
  contextRevision?: number;
  ragEnabled?: boolean;
  ragMode?: SessionRagMode;
  ragInvocationMode?: RagInvocationMode;
  ragRevision?: number;
}

export interface SessionListPage {
  items: Array<{
    sessionId: string;
    agentId: string;
    agentName: string;
    sourceType?: 'agent' | 'workflow';
    workflowVersion?: number;
    modelCode?: string;
    appName?: string;
    title: string;
    status: string;
    lastMessageTime: string;
    contextRevision: number;
    ragEnabled: boolean;
    ragMode?: SessionRagMode;
    ragInvocationMode?: RagInvocationMode;
    ragRevision?: number;
  }>;
  nextCursor?: string;
  hasMore: boolean;
}

export interface SessionMessagePage {
  sessionId: string;
  items: Array<{
    messageId: string;
    runId?: string;
    traceId?: string;
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

export type SessionRagMode = 'OFF' | 'AUTO' | 'MANUAL';
export type RagInvocationMode = 'AUTO_CONTEXT' | 'AGENT_TOOL';

export interface SessionRagEligibleBinding {
  bindingId: string;
  knowledgeBaseId: string;
  knowledgeBaseName?: string;
  profileId: string;
  profileName?: string;
  required: boolean;
  maxTokens: number;
  priority: number;
  revision: number;
  status?: string;
}

export interface SessionRagSetting {
  sessionId: string;
  enabled: boolean;
  bindingConfigured: boolean;
  targetType: 'AGENT' | 'WORKFLOW';
  targetId: string;
  message: string;
  mode?: SessionRagMode;
  invocationMode?: RagInvocationMode;
  selectedBindingIds?: string[];
  eligibleBindings?: SessionRagEligibleBinding[];
  revision?: number;
}

export interface SessionRagSettingUpdate {
  mode: SessionRagMode;
  invocationMode: RagInvocationMode;
  selectedBindingIds: string[];
  expectedRevision?: number;
}

export interface StreamHandlers {
  onTrace?: (traceId: string) => void;
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
  traceId?: string;
}

export interface RunControlResponse {
  runId: string;
  sessionId: string;
  status: string;
  contextRevision: number;
  successorRunId?: string;
}

export interface ContextInsight {
  sessionId: string;
  contextRevision: number;
  modelWindowTokens: number;
  effectiveTokens: number;
  utilization: number;
  systemTokens: number;
  historyTokens: number;
  summaryTokens: number;
  toolResultTokens: number;
  attachmentTokens: number;
  ragTokens: number;
  upstreamTokens: number;
  effectiveFromSequence?: number;
  effectiveToSequence?: number;
  memoryVersion?: number;
  compactionStatus: string;
  toolCount: number;
  callCount: number;
  attachmentCount: number;
  trimReason?: string;
}

export interface ModelUsageLatestCall {
  callId: string;
  runId?: string;
  invocationId?: string;
  modelVersion?: string;
  callStatus: string;
  finishReason?: string;
  promptTokens: number;
  candidateTokens: number;
  totalTokens: number;
  thoughtsTokens: number;
  toolUsePromptTokens: number;
  createTime?: string;
}

export interface ModelUsageSummary {
  callCount: number;
  successCount: number;
  failedCount: number;
  runningCount: number;
  cancelledCount: number;
  promptTokens: number;
  candidateTokens: number;
  totalTokens: number;
  thoughtsTokens: number;
  toolUsePromptTokens: number;
}

export interface ModelUsageResponse {
  latest?: ModelUsageLatestCall;
  session?: ModelUsageSummary;
  run?: ModelUsageSummary;
  recent?: ModelUsageSummary;
}

export interface ArtifactAsset {
  assetId: string;
  assetKind: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  sha256?: string;
  sessionId?: string;
  messageId?: string;
  parseStatus: string;
  parseError?: string;
  status: string;
  createTime?: string;
  updateTime?: string;
}

export interface AssetPage {
  items: ArtifactAsset[];
  nextCursor?: string;
  hasMore: boolean;
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
  formatVersion?: number;
  sourceType?: 'agent' | 'workflow';
  workflowId?: string;
  workflowVersion?: number;
  modelCode?: string;
  toolDependencies?: ShareToolDependency[];
  toolPrecheck?: ShareToolPrecheck;
  legacySnapshot?: boolean;
  messages?: SessionShareMessage[];
}

export interface ShareToolDependency {
  toolType: 'skill' | 'mcp';
  toolId: string;
  toolName: string;
  version?: string;
  source: string;
  requiredPermission?: string;
}

export interface ShareToolAccessItem extends ShareToolDependency {
  access: 'available' | 'missing' | 'denied';
  reason?: string;
}

export interface ShareToolPrecheck {
  hasRisk: boolean;
  availableCount: number;
  missingCount: number;
  deniedCount: number;
  items: ShareToolAccessItem[];
}

export interface SessionShareImportRequest {
  confirmToolAccessRisk: boolean;
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
  workflowKind?: 'STATIC' | 'INTELLIGENT';
}

export interface WorkflowDeleteResponse {
  workflowId: string;
  status: 'deleted';
  deletedAt?: string;
  conflictReason?: string;
}

export interface WorkflowGraph {
  workflowKind?: 'STATIC' | 'INTELLIGENT';
  routingProtocolVersion?: 'MARKER_V1' | 'TOOL_V2';
  maxSteps?: number;
  tokenBudget?: number;
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
  enabledStrategies?: WorkflowRouteStrategy[];
  allowedTargetNodeIds?: string[];
  defaultTargetNodeId?: string;
  routeInstruction?: string;
  maxVisits?: number;
  ragToolEnabled?: boolean;
  x?: number;
  y?: number;
}

export interface WorkflowEdge {
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
  routeType?: 'FIXED' | 'SUCCESS' | 'FAILURE' | 'EXPRESSION' | 'NODE_SUGGESTION' | 'AI_ROUTER' | 'DEFAULT';
  routeKey?: string;
  routeAliases?: string[];
  conditionExpression?: string;
  priority?: number;
}

export type WorkflowRouteStrategy =
  | 'FIXED'
  | 'SUCCESS'
  | 'EXPRESSION'
  | 'NODE_SUGGESTION'
  | 'AI_ROUTER'
  | 'DEFAULT';

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
  manageable?: boolean;
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
  manageable?: boolean;
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
