export const WORKFLOW_EVENT_SCHEMA = 'workflow-event-v1' as const;

export type WorkflowEventType =
  | 'WORKFLOW_STARTED'
  | 'AGENT_STARTED'
  | 'THINKING_DELTA'
  | 'ANSWER_DELTA'
  | 'PARENT_RESUME_STARTED'
  | 'WAITING_ALL'
  | 'APPROVAL_REQUIRED'
  | 'APPROVAL_RESOLVED'
  | 'NODE_STARTED'
  | 'NODE_OUTPUT_DELTA'
  | 'NODE_COMPLETED'
  | 'NODE_FAILED'
  | 'NODE_CANCELLED'
  | 'TOOL_CALL_STARTED'
  | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED'
  | 'ROUTE_REPAIR_STARTED'
  | 'ROUTE_REPAIR_COMPLETED'
  | 'ROUTE_DECIDED'
  | 'FINAL_ANSWER_DELTA'
  | 'FINAL_ANSWER_COMPLETED'
  | 'WORKFLOW_COMPLETED'
  | 'WORKFLOW_FAILED'
  | 'WORKFLOW_CANCELLED';

export interface IntelligentWorkflowStartRequest {
  workflowId: string;
  workflowVersion?: number;
  modelCode?: string;
  sessionId: string;
  message: string;
  requestedRunId: string;
  attachmentIds?: string[];
}

export interface IntelligentWorkflowRunResponse {
  runId: string;
  workflowId: string;
  workflowVersion: number;
  status: string;
  currentNodeId: string;
  traceId: string;
  operationTraceId?: string;
  maxSteps: number;
  tokenBudget: number;
}

export type StaticWorkflowStartRequest = IntelligentWorkflowStartRequest;

export interface StaticWorkflowRunResponse {
  runId: string;
  sessionId: string;
  workflowId: string;
  status: string;
  traceId: string;
  operationTraceId?: string;
}

export interface WorkflowStreamMetadata {
  schemaVersion: typeof WORKFLOW_EVENT_SCHEMA;
  runId: string;
  traceId: string;
  operationTraceId: string;
  afterSequence: number;
}

export interface WorkflowRunEvent {
  schemaVersion: typeof WORKFLOW_EVENT_SCHEMA;
  eventId: string;
  sequence: number;
  runId: string;
  eventType: WorkflowEventType;
  nodeExecutionId?: string;
  nodeId?: string;
  payloadJson: string;
  traceId: string;
  occurredAt: string;
}

export interface WorkflowNodeExecutionView {
  nodeExecutionId: string;
  nodeId: string;
  nodeName: string;
  executionIndex: number;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  output: string;
  routeTargetNodeId?: string;
  routeStrategy?: string;
  routeKey?: string;
  routeTargetNodeName?: string;
  routeSource?: string;
  routeReason?: string;
  routeFunctionCallId?: string;
  routeCostMs?: number;
  routeCategory?: 'DEFAULT' | 'FAILURE' | 'BUSINESS';
  routeRepairStatus?: 'running' | 'completed';
  routeRepairRouteKey?: string;
  toolCalls: WorkflowToolCallView[];
  totalTokens?: number;
  errorMessage?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface WorkflowToolCallView {
  functionCallId: string;
  toolCode: string;
  displayName: string;
  status: 'running' | 'completed' | 'failed';
  startedAt: string;
  finishedAt?: string;
  success?: boolean;
  costMs?: number;
  retrievalId?: string;
  hits?: number;
  citations?: number;
  tokens?: number;
  degraded?: boolean;
  routeKey?: string;
  reason?: string;
  errorCode?: string;
  retryable?: boolean;
}

export interface WorkflowRunViewState {
  runId: string;
  traceId: string;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  lastSequence: number;
  seenEventIds: string[];
  nodes: WorkflowNodeExecutionView[];
  thinking: string;
  reactTurns: AgentReactTurnView[];
  waitingAll: boolean;
  activities: AgentRunActivityView[];
  finalAnswer: string;
  errorMessage: string;
}

export interface AgentReactTurnView {
  id: string;
  sequence: number;
  thinking: string;
  tools: WorkflowToolCallView[];
  startedAt: string;
}

export interface AgentRunActivityView {
  id: string;
  sequence: number;
  type: 'agent' | 'tool' | 'wait' | 'approval';
  label: string;
  status: 'running' | 'completed' | 'failed' | 'waiting';
  startedAt: string;
  finishedAt?: string;
  detail?: string;
  costMs?: number;
}
