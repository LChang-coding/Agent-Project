export const WORKFLOW_EVENT_SCHEMA = 'workflow-event-v1' as const;

export type WorkflowEventType =
  | 'WORKFLOW_STARTED'
  | 'NODE_STARTED'
  | 'NODE_OUTPUT_DELTA'
  | 'NODE_COMPLETED'
  | 'NODE_FAILED'
  | 'NODE_CANCELLED'
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
  totalTokens?: number;
  errorMessage?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface WorkflowRunViewState {
  runId: string;
  traceId: string;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  lastSequence: number;
  seenEventIds: string[];
  nodes: WorkflowNodeExecutionView[];
  finalAnswer: string;
  errorMessage: string;
}
