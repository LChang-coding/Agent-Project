import { request } from '@/api/http';

export type RagTargetType = 'agent' | 'workflow';
export type RagRetrievalMode = 'dense' | 'sparse' | 'hybrid';
export type RagFusionStrategy = 'rrf' | 'weighted';

export interface RagKnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description?: string;
  visibility: string;
  status: string;
  embeddingDimension: number;
  currentGeneration: number;
  revision: number;
}

export interface RagDocument {
  documentId: string;
  knowledgeBaseId: string;
  displayName: string;
  status: string;
  activeVersionId?: string;
  activeGeneration?: number;
  targetGeneration?: number;
  revision: number;
}

export interface RagDocumentUploadResult {
  documentId: string;
  versionId: string;
  taskId: string;
  fileName: string;
  sizeBytes: number;
  status: string;
  deduplicated: boolean;
}

export interface RagKnowledgeBaseDeleteTask {
  taskId: string;
  knowledgeBaseId: string;
  requestedByUserId: string;
  status: string;
  stage: string;
  totalDocuments: number;
  completedDocuments: number;
  currentDocumentId?: string;
  attemptCount: number;
  maxAttempts: number;
  nextRetryAt?: string;
  errorCode?: string;
  errorMessage?: string;
  revision: number;
}

export interface RagIngestTask {
  taskId: string;
  knowledgeBaseId: string;
  documentId: string;
  versionId: string;
  operation: string;
  stage: string;
  status: string;
  processedChunks: number;
  totalChunks: number;
  attemptCount: number;
  maxAttempts: number;
  errorCode?: string;
  cancelReason?: string;
  revision: number;
}

export interface RagRetrievalProfile {
  profileId: string;
  name: string;
  mode: RagRetrievalMode;
  fusionStrategy: RagFusionStrategy;
  denseWeight: number;
  sparseWeight: number;
  denseTopK: number;
  sparseTopK: number;
  fusionTopK: number;
  rerankEnabled: boolean;
  rerankTopK: number;
  finalTopK: number;
  neighborWindow: number;
  maxContextTokens: number;
  scoreThreshold?: number;
  queryRewriteEnabled: boolean;
  deduplicateEnabled: boolean;
  revision: number;
}

export interface RagRetrievalProfilePayload extends Omit<RagRetrievalProfile, 'profileId' | 'revision'> {
  expectedRevision?: number;
}

export interface RagBinding {
  bindingId: string;
  targetType: RagTargetType;
  targetId: string;
  knowledgeBaseId: string;
  profileId: string;
  required: boolean;
  maxTokens: number;
  priority: number;
  revision: number;
}

export interface RagBindingPayload {
  targetType: RagTargetType;
  targetId: string;
  knowledgeBaseId: string;
  profileId: string;
  required: boolean;
  maxTokens: number;
  priority: number;
}

export interface RagRetrievalDebugResult {
  retrievalId: string;
  estimatedTokenCount: number;
  degraded: boolean;
  degradationReasons: string[];
  metrics: {
    denseCandidateCount: number;
    sparseCandidateCount: number;
    fusionCandidateCount: number;
    rerankCandidateCount: number;
    embeddingMs: number;
    denseMs: number;
    sparseMs: number;
    fusionMs: number;
    rerankMs: number;
    totalMs: number;
  };
  citations: Array<{
    citationId: string;
    rank: number;
    knowledgeBaseId: string;
    documentId: string;
    documentName: string;
    documentVersion: number;
    generation: number;
    chunkId: string;
    context: string;
    pageNumber?: number;
    headingPath?: string;
    denseScore?: number;
    sparseScore?: number;
    fusionScore: number;
    rerankScore?: number;
    metadata: Record<string, string>;
  }>;
}

export function queryKnowledgeBases() {
  return request<RagKnowledgeBase[]>({ url: '/v1/rag/knowledge-bases', method: 'GET' });
}

export function createKnowledgeBase(payload: { name: string; description?: string }) {
  return request<RagKnowledgeBase>({ url: '/v1/rag/knowledge-bases', method: 'POST', data: payload });
}

export function updateKnowledgeBase(knowledgeBaseId: string, payload: {
  name: string;
  description?: string;
  expectedRevision: number;
}) {
  return request<RagKnowledgeBase>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}`,
    method: 'PUT',
    data: payload,
  });
}

export function requestKnowledgeBaseDeletion(knowledgeBaseId: string, expectedRevision: number) {
  return request<RagKnowledgeBaseDeleteTask>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/delete-tasks`,
    method: 'POST',
    data: { expectedRevision },
  });
}

export function queryKnowledgeBaseDeleteTask(knowledgeBaseId: string) {
  return request<RagKnowledgeBaseDeleteTask>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/delete-task`,
    method: 'GET',
  });
}

export function queryKnowledgeBaseDeleteTaskById(taskId: string) {
  return request<RagKnowledgeBaseDeleteTask>({
    url: `/v1/rag/knowledge-base-delete-tasks/${encodeURIComponent(taskId)}`,
    method: 'GET',
  });
}

export function retryKnowledgeBaseDeleteTask(taskId: string) {
  return request<RagKnowledgeBaseDeleteTask>({
    url: `/v1/rag/knowledge-base-delete-tasks/${encodeURIComponent(taskId)}/retry`,
    method: 'POST',
  });
}

export function queryRagDocuments(knowledgeBaseId: string) {
  return request<RagDocument[]>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`,
    method: 'GET',
  });
}

export function uploadRagDocument(knowledgeBaseId: string, file: File, onProgress?: (percent: number) => void) {
  const data = new FormData();
  data.append('file', file);
  return request<RagDocumentUploadResult>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`,
    method: 'POST',
    data,
    timeout: 180_000,
    onUploadProgress: (event) => {
      if (event.total) onProgress?.(Math.min(100, Math.round((event.loaded / event.total) * 100)));
    },
  });
}

export function deleteRagDocument(knowledgeBaseId: string, documentId: string, expectedRevision: number) {
  return request<RagIngestTask>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents/${encodeURIComponent(documentId)}`,
    method: 'DELETE',
    params: { expectedRevision },
  });
}

export function queryRagIngestTask(taskId: string) {
  return request<RagIngestTask>({
    url: `/v1/rag/ingest-tasks/${encodeURIComponent(taskId)}`,
    method: 'GET',
  });
}

export function queryRagIngestTasks(knowledgeBaseId: string, limit = 100) {
  return request<RagIngestTask[]>({
    url: `/v1/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/ingest-tasks`,
    method: 'GET',
    params: { limit },
  });
}

export function cancelRagIngestTask(taskId: string, reason: string) {
  return request<RagIngestTask>({
    url: `/v1/rag/ingest-tasks/${encodeURIComponent(taskId)}/cancel`,
    method: 'POST',
    data: { reason },
  });
}

export function retryRagIngestTask(taskId: string) {
  return request<RagIngestTask>({
    url: `/v1/rag/ingest-tasks/${encodeURIComponent(taskId)}/retry`,
    method: 'POST',
  });
}

export function queryRagRetrievalProfiles() {
  return request<RagRetrievalProfile[]>({ url: '/v1/rag/retrieval-profiles', method: 'GET' });
}

export function createRagRetrievalProfile(payload: RagRetrievalProfilePayload) {
  return request<RagRetrievalProfile>({ url: '/v1/rag/retrieval-profiles', method: 'POST', data: payload });
}

export function updateRagRetrievalProfile(profileId: string, payload: RagRetrievalProfilePayload) {
  return request<RagRetrievalProfile>({
    url: `/v1/rag/retrieval-profiles/${encodeURIComponent(profileId)}`,
    method: 'PUT',
    data: payload,
  });
}

export function queryRagBindings() {
  return request<RagBinding[]>({ url: '/v1/rag/bindings', method: 'GET' });
}

export function createRagBinding(payload: RagBindingPayload) {
  return request<RagBinding>({ url: '/v1/rag/bindings', method: 'POST', data: payload });
}

export function deleteRagBinding(bindingId: string, expectedRevision: number) {
  return request<boolean>({
    url: `/v1/rag/bindings/${encodeURIComponent(bindingId)}`,
    method: 'DELETE',
    params: { expectedRevision },
  });
}

export function debugRagRetrieval(payload: {
  targetType: RagTargetType;
  targetId: string;
  query: string;
  maxContextTokens: number;
}) {
  return request<RagRetrievalDebugResult>({ url: '/v1/rag/retrieval-debug', method: 'POST', data: payload });
}
