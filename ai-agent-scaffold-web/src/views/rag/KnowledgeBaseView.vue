<template>
  <div class="rag-page">
    <header class="rag-hero">
      <div>
        <span class="rag-kicker">Knowledge operations / {{ authStore.tenantId || 'tenant' }}</span>
        <h1>知识编辑室</h1>
        <p>从原始文档到可引用答案，在一个可观测的工作台内管理摄取、检索策略与 Agent 绑定。</p>
      </div>
      <div class="hero-actions">
        <button class="rag-button rag-button--quiet" type="button" :disabled="loading" @click="reloadAll">
          <LoaderCircle v-if="loading" class="spin" :size="16" />
          <RefreshCw v-else :size="16" />
          {{ loading ? '正在同步' : '刷新数据' }}
        </button>
        <button v-if="isAdministrator" class="rag-button rag-button--primary" type="button" @click="openKnowledgeBaseEditor()">
          <Plus :size="17" /> 新建知识库
        </button>
      </div>
    </header>

    <div v-if="notice" class="notice" :class="`notice--${notice.kind}`" role="status">
      <CircleCheck v-if="notice.kind === 'success'" :size="18" />
      <CircleAlert v-else :size="18" />
      <span>{{ notice.message }}</span>
      <button type="button" aria-label="关闭通知" @click="notice = null"><X :size="16" /></button>
    </div>

    <div v-if="!isAdministrator" class="permission-note">
      <LockKeyhole :size="17" />
      当前账号为只读成员；上传、策略、绑定和调试仅向租户 owner/admin 开放。
    </div>

    <main class="rag-workbench" :aria-busy="loading">
      <aside class="library-rail">
        <div class="rail-heading">
          <div>
            <span class="section-index">01</span>
            <h2>知识库</h2>
          </div>
          <span class="count-chip">{{ knowledgeBases.length }}</span>
        </div>

        <div v-if="loading && !knowledgeBases.length" class="skeleton-list" aria-label="正在加载知识库">
          <span v-for="item in 3" :key="item" />
        </div>
        <button
          v-for="knowledgeBase in knowledgeBases"
          v-else
          :key="knowledgeBase.knowledgeBaseId"
          class="library-card"
          :class="{ 'library-card--active': selectedKnowledgeBaseId === knowledgeBase.knowledgeBaseId }"
          type="button"
          @click="selectKnowledgeBase(knowledgeBase.knowledgeBaseId)"
        >
          <span class="library-glyph"><BookOpen :size="19" /></span>
          <span class="library-copy">
            <strong>{{ knowledgeBase.name }}</strong>
            <small>{{ knowledgeBase.description || '暂无描述' }}</small>
            <span>
              <i :class="`status-dot status-dot--${statusTone(knowledgeBase.status)}`" />
              {{ statusText(knowledgeBase.status) }} · G{{ knowledgeBase.currentGeneration }}
            </span>
          </span>
          <ChevronRight :size="17" />
        </button>

        <div v-if="!loading && !knowledgeBases.length" class="rail-empty">
          <Database :size="28" />
          <strong>还没有知识库</strong>
          <span>{{ isAdministrator ? '建立第一个企业知识空间。' : '请联系租户管理员创建。' }}</span>
        </div>

        <footer v-if="selectedKnowledgeBase" class="rail-footer">
          <span>Embedding</span>
          <strong>{{ selectedKnowledgeBase.embeddingDimension }} dimensions</strong>
          <small>ID · {{ shortId(selectedKnowledgeBase.knowledgeBaseId) }}</small>
          <button v-if="isAdministrator" class="rag-button rag-button--soft rag-button--wide" type="button" @click="openKnowledgeBaseEditor(selectedKnowledgeBase)">
            编辑知识库信息
          </button>
        </footer>
      </aside>

      <section class="document-stage">
        <div class="stage-heading">
          <div>
            <span class="section-index">02</span>
            <h2>文档生命周期</h2>
            <p v-if="selectedKnowledgeBase">{{ selectedKnowledgeBase.name }} · PDF / DOCX / Markdown</p>
            <p v-else>请先选择一个知识库</p>
          </div>
          <button
            v-if="isAdministrator"
            class="rag-button rag-button--primary"
            type="button"
            :disabled="!selectedKnowledgeBase || uploadBusy"
            @click="openFilePicker"
          >
            <LoaderCircle v-if="uploadBusy" class="spin" :size="17" />
            <FileUp v-else :size="17" />
            {{ uploadBusy ? `上传中 ${uploadProgress}%` : '上传文档' }}
          </button>
          <input ref="fileInput" class="sr-file" type="file" accept=".pdf,.docx,.md,.markdown" @change="onFileSelected" />
        </div>

        <div v-if="uploadBusy" class="upload-progress" aria-live="polite">
          <span :style="{ width: `${uploadProgress}%` }" />
          <div><strong>{{ pendingFileName }}</strong><em>{{ uploadProgress }}%</em></div>
        </div>

        <section v-if="selectedTasks.length" class="task-list" aria-label="摄取任务">
          <article v-for="task in selectedTasks" :key="task.taskId" class="task-card" :class="`task-card--${statusTone(task.status)}`">
            <div class="task-orbit">
              <LoaderCircle v-if="isTaskRunning(task.status)" class="spin" :size="22" />
              <CircleAlert v-else-if="statusTone(task.status) === 'danger'" :size="22" />
              <CircleCheck v-else :size="22" />
            </div>
            <div class="task-main">
              <span>{{ operationText(task.operation) }} · {{ statusText(task.status) }} / {{ taskStageText(task.stage) }} · {{ shortId(task.taskId) }}</span>
              <strong>{{ documentName(task.documentId) }}</strong>
              <div class="task-progress"><span :style="{ width: `${taskPercent(task)}%` }" /></div>
              <small>{{ task.processedChunks }}/{{ task.totalChunks || '—' }} chunks · 尝试 {{ task.attemptCount }}/{{ task.maxAttempts }}</small>
              <small v-if="task.errorCode" class="task-error">错误码·{{ task.errorCode }}</small>
              <small v-if="task.cancelReason" class="task-error">取消原因·{{ task.cancelReason }}</small>
            </div>
            <button
              v-if="isAdministrator && task.operation !== 'delete' && isTaskRunning(task.status)"
              class="rag-button rag-button--danger"
              type="button"
              :disabled="cancelBusyTaskId === task.taskId"
              @click="cancelTask(task)"
            >
              <LoaderCircle v-if="cancelBusyTaskId === task.taskId" class="spin" :size="15" />
              {{ cancelBusyTaskId === task.taskId ? '正在取消' : '取消任务' }}
            </button>
            <button
              v-else-if="isAdministrator && task.operation !== 'rebuild' && ['failed', 'dead'].includes(task.status)"
              class="rag-button rag-button--danger"
              type="button"
              :disabled="retryBusyTaskId === task.taskId"
              @click="retryTask(task)"
            >
              <LoaderCircle v-if="retryBusyTaskId === task.taskId" class="spin" :size="15" />
              {{ retryBusyTaskId === task.taskId ? '重新入队中' : task.operation === 'delete' ? '继续删除' : '重新执行' }}
            </button>
          </article>
        </section>

        <div v-if="documentLoading" class="document-skeleton">
          <span v-for="item in 4" :key="item" />
        </div>
        <div v-else-if="documents.length" class="document-list">
          <article v-for="document in documents" :key="document.documentId" class="document-row">
            <div class="file-mark" :data-type="fileType(document.displayName)">{{ fileType(document.displayName) }}</div>
            <div class="document-copy">
              <strong>{{ document.displayName }}</strong>
              <span>{{ shortId(document.documentId) }} · {{ document.activeVersionId ? `版本 ${shortId(document.activeVersionId)}` : '尚未激活版本' }}</span>
            </div>
            <div class="document-generation">
              <small>GENERATION</small>
              <strong>{{ document.activeGeneration ?? '—' }}</strong>
            </div>
            <span class="status-pill" :class="`status-pill--${statusTone(document.status)}`">
              {{ statusText(document.status) }}
            </span>
            <button
              v-if="isAdministrator && ['ready', 'failed'].includes(document.status)"
              class="document-delete"
              type="button"
              :disabled="deletingDocumentId === document.documentId"
              :aria-label="`删除文档 ${document.displayName}`"
              @click="removeDocument(document)"
            >
              <LoaderCircle v-if="deletingDocumentId === document.documentId" class="spin" :size="16" />
              <Trash2 v-else :size="16" />
              <span>{{ deletingDocumentId === document.documentId ? '受理中' : '删除' }}</span>
            </button>
          </article>
        </div>
        <div v-else class="document-empty">
          <div class="empty-illustration"><FileText :size="34" /><span /></div>
          <strong>{{ selectedKnowledgeBase ? '这个知识库还没有文档' : '选择知识库后查看文档' }}</strong>
          <p v-if="selectedKnowledgeBase && isAdministrator">上传 PDF、DOCX 或 Markdown，系统会显示解析、切块和向量化进度。</p>
        </div>
      </section>

      <aside class="retrieval-lab">
        <div class="lab-heading">
          <div>
            <span class="section-index">03</span>
            <h2>检索实验台</h2>
          </div>
          <FlaskConical :size="22" />
        </div>

        <nav class="lab-tabs" aria-label="RAG 配置选项">
          <button v-for="tab in labTabs" :key="tab.id" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">
            {{ tab.label }}
          </button>
        </nav>

        <div v-if="!isAdministrator" class="lab-locked">
          <LockKeyhole :size="27" />
          <strong>运营控制台已锁定</strong>
          <p>仅 owner/admin 可配置检索策略、绑定运行目标或查看引用正文。</p>
        </div>

        <template v-else-if="activeTab === 'debug'">
          <div class="lab-form">
            <label>运行目标</label>
            <select v-model="debugForm.bindingId" class="lab-input">
              <option value="">选择已绑定的 Agent / Workflow</option>
              <option v-for="binding in bindings" :key="binding.bindingId" :value="binding.bindingId">
                {{ targetLabel(binding.targetType, binding.targetId) }} · {{ knowledgeBaseName(binding.knowledgeBaseId) }}
              </option>
            </select>
            <div class="label-row"><label>测试问题</label><span>{{ debugForm.query.length }}/2000</span></div>
            <textarea v-model="debugForm.query" class="lab-input lab-textarea" maxlength="2000" placeholder="例如：员工如何申请差旅报销？" />
            <label>Context Token 预算</label>
            <input v-model.number="debugForm.maxContextTokens" class="lab-input" type="number" min="1" max="32768" />
            <button class="rag-button rag-button--ink rag-button--wide" type="button" :disabled="debugBusy || !debugForm.bindingId || !debugForm.query.trim()" @click="runDebug">
              <LoaderCircle v-if="debugBusy" class="spin" :size="17" />
              <Search v-else :size="17" />
              {{ debugBusy ? '正在执行多路检索…' : '执行检索调试' }}
            </button>
          </div>

          <section v-if="debugResult" class="debug-result">
            <div class="result-summary">
              <span :class="{ degraded: debugResult.degraded }">{{ debugResult.degraded ? '已降级' : '完整链路' }}</span>
              <strong>{{ debugResult.metrics.totalMs }}<small>ms</small></strong>
              <em>{{ debugResult.citations.length }} 条引用 · {{ debugResult.estimatedTokenCount }} tokens</em>
            </div>
            <div class="metric-strip">
              <span v-for="metric in debugMetrics" :key="metric.label"><small>{{ metric.label }}</small><strong>{{ metric.value }}</strong></span>
            </div>
            <div v-if="debugResult.degradationReasons.length" class="degrade-note">
              <TriangleAlert :size="16" /> {{ debugResult.degradationReasons.join(' · ') }}
            </div>
            <article v-for="citation in debugResult.citations" :key="citation.citationId" class="citation-card">
              <header><span>#{{ citation.rank }}</span><strong>{{ citation.documentName }}</strong><em>{{ scoreText(citation.rerankScore ?? citation.fusionScore) }}</em></header>
              <p>{{ citation.context }}</p>
              <footer>{{ citation.headingPath || '未标记章节' }}<span>{{ citation.pageNumber ? `P.${citation.pageNumber}` : shortId(citation.chunkId) }}</span></footer>
            </article>
            <p v-if="!debugResult.citations.length" class="no-hit">检索已完成，但没有候选通过当前策略和预算。</p>
          </section>
        </template>

        <template v-else-if="activeTab === 'profiles' && isAdministrator">
          <button class="rag-button rag-button--soft rag-button--wide" type="button" @click="openProfileEditor()"><Plus :size="16" /> 新建检索策略</button>
          <div class="profile-list">
            <button v-for="profile in profiles" :key="profile.profileId" type="button" class="profile-card" @click="openProfileEditor(profile)">
              <span class="mode-badge">{{ profile.mode }}</span>
              <strong>{{ profile.name }}</strong>
              <small>{{ profile.fusionStrategy.toUpperCase() }} · Top {{ profile.finalTopK }} · {{ profile.maxContextTokens }}T</small>
              <span>{{ profile.rerankEnabled ? 'Rerank ON' : 'Rerank OFF' }} <ChevronRight :size="15" /></span>
            </button>
          </div>
          <p v-if="!profiles.length" class="no-hit">尚未创建检索策略。</p>
        </template>

        <template v-else-if="activeTab === 'bindings' && isAdministrator">
          <div class="lab-form binding-form">
            <label>目标类型</label>
            <select v-model="bindingForm.targetType" class="lab-input"><option value="agent">Agent</option><option value="workflow">Workflow</option></select>
            <label>运行目标</label>
            <select v-if="targetOptions.length" v-model="bindingForm.targetId" class="lab-input">
              <option value="">选择可见目标</option>
              <option v-for="target in targetOptions" :key="target.id" :value="target.id">{{ target.name }} · {{ target.id }}</option>
            </select>
            <input v-else v-model.trim="bindingForm.targetId" class="lab-input" maxlength="64" placeholder="无可见列表，请输入精确 ID" />
            <label>知识库 / 策略</label>
            <select v-model="bindingForm.knowledgeBaseId" class="lab-input"><option value="">选择知识库</option><option v-for="kb in knowledgeBases" :key="kb.knowledgeBaseId" :value="kb.knowledgeBaseId">{{ kb.name }}</option></select>
            <select v-model="bindingForm.profileId" class="lab-input"><option value="">选择检索策略</option><option v-for="profile in profiles" :key="profile.profileId" :value="profile.profileId">{{ profile.name }}</option></select>
            <div class="compact-fields"><label>Tokens<input v-model.number="bindingForm.maxTokens" class="lab-input" type="number" min="1" max="32768" /></label><label>优先级<input v-model.number="bindingForm.priority" class="lab-input" type="number" min="0" max="10000" /></label></div>
            <label class="check-row"><input v-model="bindingForm.required" type="checkbox" /> <span><strong>强制依赖</strong><small>知识检索不可用时阻断模型调用</small></span></label>
            <button class="rag-button rag-button--ink rag-button--wide" type="button" :disabled="bindingBusy || !canCreateBinding" @click="saveBinding">
              <LoaderCircle v-if="bindingBusy" class="spin" :size="16" />{{ bindingBusy ? '正在建立绑定…' : '建立运行绑定' }}
            </button>
          </div>
          <div class="binding-list">
            <article v-for="binding in bindings" :key="binding.bindingId">
              <span>{{ binding.targetType }}</span><strong>{{ targetLabel(binding.targetType, binding.targetId) }}</strong>
              <small>{{ knowledgeBaseName(binding.knowledgeBaseId) }} · {{ profileName(binding.profileId) }}</small>
              <button type="button" :disabled="deletingBindingId === binding.bindingId" aria-label="删除绑定" @click="removeBinding(binding)"><LoaderCircle v-if="deletingBindingId === binding.bindingId" class="spin" :size="15" /><Trash2 v-else :size="15" /></button>
            </article>
          </div>
        </template>
      </aside>
    </main>

    <div v-if="showKnowledgeBaseEditor" class="modal-backdrop" @click.self="closeKnowledgeBaseEditor">
      <form class="rag-modal" @submit.prevent="saveKnowledgeBase">
        <header><div><span>{{ editingKnowledgeBaseId ? 'EDIT LIBRARY' : 'NEW LIBRARY' }}</span><h2>{{ editingKnowledgeBaseId ? '编辑知识库' : '建立知识库' }}</h2></div><button type="button" aria-label="关闭" @click="closeKnowledgeBaseEditor"><X :size="19" /></button></header>
        <label>知识库名称<input v-model.trim="knowledgeBaseForm.name" class="lab-input" maxlength="128" autofocus placeholder="例如：人力资源制度" /></label>
        <label>用途说明<textarea v-model.trim="knowledgeBaseForm.description" class="lab-input lab-textarea" maxlength="500" placeholder="说明这里收纳哪些文档，以及将由哪些 Agent 使用。" /></label>
        <p><ShieldCheck :size="16" /> {{ editingKnowledgeBaseId ? '仅修改名称与说明；索引、绑定及 collection 保持不变，并通过 revision 防止覆盖他人更新。' : '归属当前租户，知识库 ID 和向量 collection 别名由服务端生成。' }}</p>
        <footer><button class="rag-button rag-button--quiet" type="button" :disabled="knowledgeBaseBusy" @click="closeKnowledgeBaseEditor">取消</button><button class="rag-button rag-button--primary" type="submit" :disabled="knowledgeBaseBusy || !knowledgeBaseForm.name"><LoaderCircle v-if="knowledgeBaseBusy" class="spin" :size="16" />{{ knowledgeBaseBusy ? '正在保存…' : editingKnowledgeBaseId ? '保存修改' : '创建知识库' }}</button></footer>
      </form>
    </div>

    <div v-if="showProfileEditor" class="modal-backdrop" @click.self="showProfileEditor = false">
      <form class="rag-modal rag-modal--wide" @submit.prevent="saveProfile">
        <header><div><span>RETRIEVAL PROFILE</span><h2>{{ editingProfileId ? '编辑检索策略' : '新建检索策略' }}</h2></div><button type="button" aria-label="关闭" @click="showProfileEditor = false"><X :size="19" /></button></header>
        <div class="profile-grid">
          <label class="span-two">策略名称<input v-model.trim="profileForm.name" class="lab-input" maxlength="128" /></label>
          <label>检索模式<select v-model="profileForm.mode" class="lab-input"><option value="dense">Dense</option><option value="sparse">Sparse</option><option value="hybrid">Hybrid</option></select></label>
          <label>融合策略<select v-model="profileForm.fusionStrategy" class="lab-input"><option value="rrf">RRF</option><option value="weighted">Weighted</option></select></label>
          <label>Dense Top K<input v-model.number="profileForm.denseTopK" class="lab-input" type="number" min="1" max="200" /></label>
          <label>Sparse Top K<input v-model.number="profileForm.sparseTopK" class="lab-input" type="number" min="1" max="200" /></label>
          <label>Fusion Top K<input v-model.number="profileForm.fusionTopK" class="lab-input" type="number" min="1" max="200" /></label>
          <label>Final Top K<input v-model.number="profileForm.finalTopK" class="lab-input" type="number" min="1" max="50" /></label>
          <label>Dense 权重<input v-model.number="profileForm.denseWeight" class="lab-input" type="number" min="0" max="10" step="0.1" /></label>
          <label>Sparse 权重<input v-model.number="profileForm.sparseWeight" class="lab-input" type="number" min="0" max="10" step="0.1" /></label>
          <label>Rerank Top K<input v-model.number="profileForm.rerankTopK" class="lab-input" type="number" min="1" max="100" /></label>
          <label>邻居窗口<input v-model.number="profileForm.neighborWindow" class="lab-input" type="number" min="0" max="5" /></label>
          <label>Context Tokens<input v-model.number="profileForm.maxContextTokens" class="lab-input" type="number" min="1" max="32768" /></label>
          <label>分数阈值<input v-model.number="profileForm.scoreThreshold" class="lab-input" type="number" min="0" max="1" step="0.01" /></label>
          <label class="check-row span-two"><input v-model="profileForm.rerankEnabled" type="checkbox" /><span><strong>启用 Rerank</strong><small>对融合候选进行交叉编码重排</small></span></label>
          <label class="check-row"><input v-model="profileForm.deduplicateEnabled" type="checkbox" /><span><strong>内容去重</strong></span></label>
          <label class="check-row"><input v-model="profileForm.queryRewriteEnabled" type="checkbox" /><span><strong>查询改写</strong><small>当前未配模型时会明确降级</small></span></label>
        </div>
        <footer><button class="rag-button rag-button--quiet" type="button" @click="showProfileEditor = false">取消</button><button class="rag-button rag-button--primary" type="submit" :disabled="profileBusy || !profileForm.name"><LoaderCircle v-if="profileBusy" class="spin" :size="16" />{{ profileBusy ? '正在保存…' : '保存策略' }}</button></footer>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import {
  BookOpen, ChevronRight, CircleAlert, CircleCheck, Database, FileText, FileUp, FlaskConical,
  LoaderCircle, LockKeyhole, Plus, RefreshCw, Search, ShieldCheck, Trash2, TriangleAlert, X,
} from '@lucide/vue';

import { queryAgentConfigManagement } from '@/api/agent';
import {
  cancelRagIngestTask, createKnowledgeBase, createRagBinding, createRagRetrievalProfile,
  debugRagRetrieval, deleteRagBinding, deleteRagDocument, queryKnowledgeBases, queryRagBindings, queryRagDocuments,
  queryRagIngestTask, queryRagIngestTasks, queryRagRetrievalProfiles, retryRagIngestTask, updateKnowledgeBase,
  updateRagRetrievalProfile, uploadRagDocument,
  type RagBinding, type RagDocument, type RagIngestTask, type RagKnowledgeBase,
  type RagRetrievalDebugResult, type RagRetrievalProfile, type RagRetrievalProfilePayload,
  type RagTargetType,
} from '@/api/rag';
import { queryWorkflows } from '@/api/workflow';
import { useAuthStore } from '@/stores/auth';

type Notice = { kind: 'success' | 'error'; message: string };
type LabTab = 'debug' | 'profiles' | 'bindings';

const authStore = useAuthStore();
const knowledgeBases = ref<RagKnowledgeBase[]>([]);
const documents = ref<RagDocument[]>([]);
const profiles = ref<RagRetrievalProfile[]>([]);
const bindings = ref<RagBinding[]>([]);
const taskById = ref<Record<string, RagIngestTask>>({});
const selectedKnowledgeBaseId = ref('');
const loading = ref(true);
const documentLoading = ref(false);
const notice = ref<Notice | null>(null);
const activeTab = ref<LabTab>('debug');
const fileInput = ref<HTMLInputElement | null>(null);
const uploadBusy = ref(false);
const uploadProgress = ref(0);
const pendingFileName = ref('');
const cancelBusyTaskId = ref('');
const retryBusyTaskId = ref('');
const deletingDocumentId = ref('');
const knowledgeBaseBusy = ref(false);
const showKnowledgeBaseEditor = ref(false);
const editingKnowledgeBaseId = ref('');
const editingKnowledgeBaseRevision = ref<number | undefined>();
const knowledgeBaseForm = reactive({ name: '', description: '' });
const debugBusy = ref(false);
const debugResult = ref<RagRetrievalDebugResult | null>(null);
const debugForm = reactive({ bindingId: '', query: '', maxContextTokens: 4096 });
const agentTargets = ref<Array<{ id: string; name: string }>>([]);
const workflowTargets = ref<Array<{ id: string; name: string }>>([]);
const bindingBusy = ref(false);
const deletingBindingId = ref('');
const bindingForm = reactive({ targetType: 'agent' as RagTargetType, targetId: '', knowledgeBaseId: '', profileId: '', required: false, maxTokens: 1024, priority: 100 });
const showProfileEditor = ref(false);
const profileBusy = ref(false);
const editingProfileId = ref('');
const editingProfileRevision = ref<number | undefined>();
const profileForm = reactive(defaultProfile());
let taskTimer: number | undefined;

const labTabs = [{ id: 'debug' as const, label: '调试' }, { id: 'profiles' as const, label: '策略' }, { id: 'bindings' as const, label: '绑定' }];
const isAdministrator = computed(() => ['owner', 'admin'].includes(authStore.roleCode.toLowerCase()));
const selectedKnowledgeBase = computed(() => knowledgeBases.value.find((item) => item.knowledgeBaseId === selectedKnowledgeBaseId.value));
const selectedTasks = computed(() => Object.values(taskById.value)
  .filter((task) => task.knowledgeBaseId === selectedKnowledgeBaseId.value)
  .sort((left, right) => Number(isTaskRunning(right.status)) - Number(isTaskRunning(left.status))));
const selectedBinding = computed(() => bindings.value.find((item) => item.bindingId === debugForm.bindingId));
const targetOptions = computed(() => bindingForm.targetType === 'agent' ? agentTargets.value : workflowTargets.value);
const canCreateBinding = computed(() => Boolean(bindingForm.targetId && bindingForm.knowledgeBaseId && bindingForm.profileId && bindingForm.maxTokens > 0));
const debugMetrics = computed(() => debugResult.value ? [
  { label: 'Dense', value: `${debugResult.value.metrics.denseCandidateCount} / ${debugResult.value.metrics.denseMs}ms` },
  { label: 'Sparse', value: `${debugResult.value.metrics.sparseCandidateCount} / ${debugResult.value.metrics.sparseMs}ms` },
  { label: 'Fusion', value: `${debugResult.value.metrics.fusionCandidateCount} / ${debugResult.value.metrics.fusionMs}ms` },
  { label: 'Rerank', value: `${debugResult.value.metrics.rerankCandidateCount} / ${debugResult.value.metrics.rerankMs}ms` },
] : []);

onMounted(reloadAll);
onBeforeUnmount(() => taskTimer && window.clearInterval(taskTimer));
watch(() => bindingForm.targetType, () => { bindingForm.targetId = ''; });

async function reloadAll() {
  loading.value = true;
  try {
    const bases = await queryKnowledgeBases();
    knowledgeBases.value = bases;
    if (!bases.some((item) => item.knowledgeBaseId === selectedKnowledgeBaseId.value)) selectedKnowledgeBaseId.value = bases[0]?.knowledgeBaseId || '';
    if (selectedKnowledgeBaseId.value) await loadKnowledgeBaseData();
    else { documents.value = []; taskById.value = {}; }
    if (isAdministrator.value) {
      const [profileValues, bindingValues] = await Promise.all([queryRagRetrievalProfiles(), queryRagBindings()]);
      profiles.value = profileValues;
      bindings.value = bindingValues;
      await loadTargets();
    }
  } catch (error) {
    showError(error, '知识库数据加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadTargets() {
  const results = await Promise.allSettled([queryAgentConfigManagement(false), queryWorkflows()]);
  if (results[0].status === 'fulfilled') agentTargets.value = results[0].value.filter((item) => item.status === 'enabled').map((item) => ({ id: item.agentId, name: item.agentName }));
  if (results[1].status === 'fulfilled') workflowTargets.value = results[1].value
    .filter((item) => item.status === 'published' && item.publishedVersion > 0)
    .map((item) => ({ id: item.workflowId, name: item.workflowName }));
}

async function selectKnowledgeBase(id: string) {
  if (selectedKnowledgeBaseId.value === id) return;
  selectedKnowledgeBaseId.value = id;
  bindingForm.knowledgeBaseId = id;
  debugResult.value = null;
  await loadKnowledgeBaseData();
}

async function loadKnowledgeBaseData() {
  await Promise.all([loadDocuments(), loadTasks()]);
  startTaskPolling();
}

async function loadDocuments() {
  if (!selectedKnowledgeBaseId.value) { documents.value = []; return; }
  documentLoading.value = true;
  try { documents.value = await queryRagDocuments(selectedKnowledgeBaseId.value); }
  catch (error) { documents.value = []; showError(error, '文档列表加载失败'); }
  finally { documentLoading.value = false; }
}

async function loadTasks() {
  const knowledgeBaseId = selectedKnowledgeBaseId.value;
  if (!knowledgeBaseId || !isAdministrator.value) {
    taskById.value = {};
    return;
  }
  try {
    const tasks = await queryRagIngestTasks(knowledgeBaseId);
    taskById.value = Object.fromEntries(tasks.map((task) => [task.taskId, task]));
  } catch (error) {
    taskById.value = {};
    showError(error, '摄取任务加载失败');
  }
}

function openFilePicker() { fileInput.value?.click(); }

async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !selectedKnowledgeBaseId.value) return;
  if (!/\.(pdf|docx|md|markdown)$/i.test(file.name)) { notice.value = { kind: 'error', message: '仅支持 PDF、DOCX 和 Markdown 文档。' }; return; }
  uploadBusy.value = true;
  uploadProgress.value = 0;
  pendingFileName.value = `${file.name} · ${formatBytes(file.size)}`;
  try {
    const result = await uploadRagDocument(selectedKnowledgeBaseId.value, file, (value) => { uploadProgress.value = value; });
    taskById.value = { ...taskById.value, [result.taskId]: await queryRagIngestTask(result.taskId) };
    notice.value = { kind: 'success', message: result.deduplicated ? '相同内容已受理，已复用摄取任务。' : `${file.name} 已上传，正在解析和向量化。` };
    await loadDocuments();
    startTaskPolling();
  } catch (error) { showError(error, '文档上传失败'); }
  finally { uploadBusy.value = false; uploadProgress.value = 0; pendingFileName.value = ''; }
}

function startTaskPolling() {
  if (taskTimer) return;
  taskTimer = window.setInterval(async () => {
    const running = Object.values(taskById.value).filter((task) => isTaskRunning(task.status));
    if (!running.length) { window.clearInterval(taskTimer); taskTimer = undefined; return; }
    const settled = await Promise.allSettled(running.map((task) => queryRagIngestTask(task.taskId)));
    settled.forEach((item) => { if (item.status === 'fulfilled') taskById.value = { ...taskById.value, [item.value.taskId]: item.value }; });
    const failedCount = settled.filter((item) => item.status === 'rejected').length;
    if (failedCount) {
      notice.value = { kind: 'error', message: `${failedCount} 个摄取任务进度同步失败，可点击“刷新数据”重试。` };
    }
    if (settled.some((item) => item.status === 'fulfilled' && !isTaskRunning(item.value.status))) await loadDocuments();
  }, 2500);
}

async function cancelTask(target: RagIngestTask) {
  if (!window.confirm(`确定取消任务 ${shortId(target.taskId)}？未激活的向量和分块将被清理。`)) return;
  cancelBusyTaskId.value = target.taskId;
  try {
    const task = await cancelRagIngestTask(target.taskId, '租户管理员在知识编辑室中取消');
    taskById.value = { ...taskById.value, [task.taskId]: task };
    await loadDocuments();
    notice.value = { kind: 'success', message: task.status === 'cancelled'
      ? '摄取任务已取消，未激活的文档版本已关闭。'
      : '取消请求已记录，Worker 将在下一个外部调用前停止并清理未激活数据。' };
  } catch (error) { showError(error, '任务取消失败'); }
  finally { cancelBusyTaskId.value = ''; }
}

async function removeDocument(document: RagDocument) {
  const confirmed = window.confirm(`删除“${document.displayName}”的全部内容与版本？\n\n系统会保留不可检索的审计墓碑，并异步清理向量、分块和原文件。清理开始后不能取消。`);
  if (!confirmed || !selectedKnowledgeBaseId.value) return;
  deletingDocumentId.value = document.documentId;
  try {
    const task = await deleteRagDocument(selectedKnowledgeBaseId.value, document.documentId, document.revision);
    taskById.value = { ...taskById.value, [task.taskId]: task };
    await loadDocuments();
    startTaskPolling();
    notice.value = { kind: 'success', message: task.status === 'completed'
      ? `“${document.displayName}”已删除。`
      : `“${document.displayName}”已退出检索，正在清理向量、分块和原文件。` };
  } catch (error) { showError(error, '文档删除受理失败'); }
  finally { deletingDocumentId.value = ''; }
}

async function retryTask(task: RagIngestTask) {
  retryBusyTaskId.value = task.taskId;
  try {
    const requeued = await retryRagIngestTask(task.taskId);
    taskById.value = { ...taskById.value, [requeued.taskId]: requeued };
    await loadDocuments();
    startTaskPolling();
    notice.value = { kind: 'success', message: requeued.operation === 'delete'
      ? '删除任务已从上次检查点继续清理。'
      : '摄取任务已安全重置，将从原始文档重新解析和建立索引。' };
  } catch (error) { showError(error, '任务重新执行失败'); }
  finally { retryBusyTaskId.value = ''; }
}

function openKnowledgeBaseEditor(knowledgeBase?: RagKnowledgeBase) {
  editingKnowledgeBaseId.value = knowledgeBase?.knowledgeBaseId || '';
  editingKnowledgeBaseRevision.value = knowledgeBase?.revision;
  knowledgeBaseForm.name = knowledgeBase?.name || '';
  knowledgeBaseForm.description = knowledgeBase?.description || '';
  showKnowledgeBaseEditor.value = true;
}

function closeKnowledgeBaseEditor() {
  if (knowledgeBaseBusy.value) return;
  showKnowledgeBaseEditor.value = false;
  editingKnowledgeBaseId.value = '';
  editingKnowledgeBaseRevision.value = undefined;
  knowledgeBaseForm.name = '';
  knowledgeBaseForm.description = '';
}

async function saveKnowledgeBase() {
  knowledgeBaseBusy.value = true;
  try {
    const editing = Boolean(editingKnowledgeBaseId.value);
    const saved = editing
      ? await updateKnowledgeBase(editingKnowledgeBaseId.value, {
        ...knowledgeBaseForm,
        expectedRevision: editingKnowledgeBaseRevision.value!,
      })
      : await createKnowledgeBase({ ...knowledgeBaseForm });
    const existing = knowledgeBases.value.some((item) => item.knowledgeBaseId === saved.knowledgeBaseId);
    knowledgeBases.value = existing
      ? knowledgeBases.value.map((item) => item.knowledgeBaseId === saved.knowledgeBaseId ? saved : item)
      : [saved, ...knowledgeBases.value];
    selectedKnowledgeBaseId.value = saved.knowledgeBaseId;
    bindingForm.knowledgeBaseId = saved.knowledgeBaseId;
    if (!editing) documents.value = [];
    knowledgeBaseBusy.value = false;
    closeKnowledgeBaseEditor();
    notice.value = { kind: 'success', message: `知识库“${saved.name}”已${editing ? '更新' : '创建'}。` };
  } catch (error) { showError(error, editingKnowledgeBaseId.value ? '知识库更新失败，请刷新后重试' : '知识库创建失败'); }
  finally { knowledgeBaseBusy.value = false; }
}

async function runDebug() {
  const binding = selectedBinding.value;
  if (!binding) return;
  debugBusy.value = true;
  debugResult.value = null;
  try {
    debugResult.value = await debugRagRetrieval({ targetType: binding.targetType, targetId: binding.targetId, query: debugForm.query.trim(), maxContextTokens: debugForm.maxContextTokens });
  } catch (error) { showError(error, '检索调试失败'); }
  finally { debugBusy.value = false; }
}

async function saveBinding() {
  bindingBusy.value = true;
  try {
    const created = await createRagBinding({ ...bindingForm });
    bindings.value = [...bindings.value, created].sort((a, b) => a.priority - b.priority);
    debugForm.bindingId = created.bindingId;
    notice.value = { kind: 'success', message: '运行目标已绑定知识库，可立即进行检索调试。' };
  } catch (error) { showError(error, '运行绑定创建失败'); }
  finally { bindingBusy.value = false; }
}

async function removeBinding(binding: RagBinding) {
  if (!window.confirm(`删除 ${targetLabel(binding.targetType, binding.targetId)} 的这条 RAG 绑定？`)) return;
  deletingBindingId.value = binding.bindingId;
  try {
    await deleteRagBinding(binding.bindingId, binding.revision);
    bindings.value = bindings.value.filter((item) => item.bindingId !== binding.bindingId);
    if (debugForm.bindingId === binding.bindingId) debugForm.bindingId = '';
    notice.value = { kind: 'success', message: '绑定已删除。' };
  } catch (error) { showError(error, '绑定删除失败'); }
  finally { deletingBindingId.value = ''; }
}

function openProfileEditor(profile?: RagRetrievalProfile) {
  Object.assign(profileForm, profile ? { ...profile } : defaultProfile());
  editingProfileId.value = profile?.profileId || '';
  editingProfileRevision.value = profile?.revision;
  showProfileEditor.value = true;
}

async function saveProfile() {
  profileBusy.value = true;
  const payload: RagRetrievalProfilePayload = { ...profileForm, expectedRevision: editingProfileRevision.value };
  try {
    const saved = editingProfileId.value ? await updateRagRetrievalProfile(editingProfileId.value, payload) : await createRagRetrievalProfile(payload);
    const index = profiles.value.findIndex((item) => item.profileId === saved.profileId);
    profiles.value = index < 0 ? [...profiles.value, saved] : profiles.value.map((item) => item.profileId === saved.profileId ? saved : item);
    bindingForm.profileId ||= saved.profileId;
    showProfileEditor.value = false;
    notice.value = { kind: 'success', message: `检索策略“${saved.name}”已保存。` };
  } catch (error) { showError(error, '检索策略保存失败'); }
  finally { profileBusy.value = false; }
}

function defaultProfile(): RagRetrievalProfilePayload {
  return { name: '高质量混合检索', mode: 'hybrid', fusionStrategy: 'rrf', denseWeight: 1, sparseWeight: 1, denseTopK: 30, sparseTopK: 30, fusionTopK: 30, rerankEnabled: true, rerankTopK: 15, finalTopK: 6, neighborWindow: 1, maxContextTokens: 4096, scoreThreshold: 0, queryRewriteEnabled: false, deduplicateEnabled: true };
}

function showError(error: unknown, fallback: string) { const candidate = error as { info?: string; message?: string }; notice.value = { kind: 'error', message: candidate?.info || candidate?.message || fallback }; }
function isTaskRunning(status: string) { return ['pending', 'running', 'retrying', 'cancel_requested'].includes(status); }
function taskPercent(task: RagIngestTask) { if (task.operation === 'delete') return ({ received: 8, deleting_vectors: 30, deleting_chunks: 60, deleting_source: 85, completed: 100 } as Record<string, number>)[task.stage] ?? 0; if (!task.totalChunks) return task.status === 'completed' ? 100 : isTaskRunning(task.status) ? 12 : 0; return Math.min(100, Math.round((task.processedChunks / task.totalChunks) * 100)); }
function shortId(value?: string) { if (!value) return '—'; return value.length > 14 ? `${value.slice(0, 7)}…${value.slice(-4)}` : value; }
function formatBytes(value: number) { if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`; return `${(value / 1024 / 1024).toFixed(1)} MiB`; }
function fileType(name: string) { return name.split('.').pop()?.toUpperCase() || 'DOC'; }
function documentName(id: string) { return documents.value.find((item) => item.documentId === id)?.displayName || shortId(id); }
function knowledgeBaseName(id: string) { return knowledgeBases.value.find((item) => item.knowledgeBaseId === id)?.name || shortId(id); }
function profileName(id: string) { return profiles.value.find((item) => item.profileId === id)?.name || shortId(id); }
function targetLabel(type: RagTargetType, id: string) { const target = (type === 'agent' ? agentTargets.value : workflowTargets.value).find((item) => item.id === id); return target?.name || id; }
function scoreText(value?: number) { return Number.isFinite(value) ? Number(value).toFixed(3) : '—'; }
function statusTone(status: string) { if (['active', 'ready', 'completed'].includes(status)) return 'success'; if (['failed', 'dead', 'cancelled'].includes(status)) return 'danger'; if (['pending', 'running', 'retrying', 'cancel_requested', 'processing', 'deleting'].includes(status)) return 'working'; return 'neutral'; }
function statusText(status: string) { return ({ active: '可用', ready: '已就绪', completed: '已完成', failed: '失败', dead: '已终止', cancelled: '已取消', pending: '等待中', running: '处理中', retrying: '等待重试', cancel_requested: '取消中', processing: '处理中', deleting: '删除中', deleted: '已删除' } as Record<string, string>)[status] || status; }
function taskStageText(stage: string) { return ({ received: '已受理', queued: '排队', downloading: '下载原文档', parsing: '解析结构', chunking: '切分语义块', embedding: '生成向量', indexing: '写入索引', verifying: '验证完整性', deleting_vectors: '清理向量', deleting_chunks: '清理分块', deleting_source: '清理原文件', completed: '已完成' } as Record<string, string>)[stage] || stage; }
function operationText(operation: string) { return ({ ingest: '摄取', rebuild: '重建', delete: '删除' } as Record<string, string>)[operation] || operation; }
</script>

<style scoped>
.rag-page { min-width: 0; min-height: calc(100vh - 72px); padding: 30px; background: radial-gradient(circle at 80% 0, rgba(169, 121, 57, .10), transparent 28%), linear-gradient(135deg, rgba(30, 90, 103, .035), transparent 38%); }
.rag-hero { display: flex; align-items: flex-end; justify-content: space-between; max-width: 1680px; margin: 0 auto 22px; gap: 28px; }
.rag-kicker, .section-index { color: var(--gold); font-family: "IBM Plex Mono", "SFMono-Regular", monospace; font-size: 11px; font-weight: 700; letter-spacing: .16em; overflow-wrap: anywhere; text-transform: uppercase; }.rag-kicker { display: block; max-width: 100%; word-break: break-all; }
.rag-hero h1 { margin: 6px 0 8px; font-family: "Fraunces", "Songti SC", serif; font-size: clamp(34px, 4vw, 58px); line-height: 1; letter-spacing: -.055em; }
.rag-hero p { max-width: 730px; margin: 0; color: var(--muted); line-height: 1.7; }
.hero-actions, .rag-button { display: flex; align-items: center; gap: 8px; }
.hero-actions { flex: none; }
.rag-button { min-height: 40px; padding: 0 14px; border: 1px solid transparent; border-radius: 10px; cursor: pointer; font-size: 13px; font-weight: 700; transition: transform var(--motion-fast), box-shadow var(--motion-fast), background var(--motion-fast); }
.rag-button:hover:not(:disabled) { transform: translateY(-1px); }
.rag-button:disabled { cursor: not-allowed; opacity: .55; }
.rag-button--primary { color: #fffdf5; background: var(--accent); box-shadow: 0 9px 20px rgba(30, 90, 103, .18); }
.rag-button--quiet { color: var(--ink-soft); border-color: var(--line); background: rgba(252,252,250,.76); }
.rag-button--soft { color: var(--accent-deep); background: var(--accent-soft); }
.rag-button--ink { color: #fff; background: var(--surface-ink); }
.rag-button--danger { min-height: 34px; color: var(--danger); background: var(--danger-soft); }
.rag-button--wide { width: 100%; justify-content: center; }
.notice, .permission-note { display: flex; align-items: center; max-width: 1680px; margin: 0 auto 14px; padding: 11px 14px; gap: 9px; border-radius: 11px; font-size: 13px; }
.notice span { flex: 1; }.notice button { display: grid; padding: 4px; color: inherit; cursor: pointer; background: transparent; place-items: center; }
.notice--success { color: var(--success); border: 1px solid rgba(45,107,79,.18); background: var(--success-soft); }.notice--error { color: var(--danger); border: 1px solid rgba(155,62,62,.2); background: var(--danger-soft); }
.permission-note { color: var(--warning); border: 1px solid rgba(141,104,31,.18); background: var(--warning-soft); }
.rag-workbench { display: grid; grid-template-columns: minmax(220px, .72fr) minmax(430px, 1.5fr) minmax(330px, 1fr); max-width: 1680px; min-height: 680px; margin: 0 auto; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 22px; background: rgba(252,252,250,.94); box-shadow: 0 26px 70px rgba(23,33,43,.09); }
.library-rail, .document-stage, .retrieval-lab { min-width: 0; padding: 22px; }
.library-rail { display: flex; flex-direction: column; border-right: 1px solid var(--line); background: linear-gradient(180deg, #f5f3ec, #ecefe9); }
.retrieval-lab { border-left: 1px solid var(--line); background: #f7f8f5; }
.rail-heading, .stage-heading, .lab-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.rail-heading h2, .stage-heading h2, .lab-heading h2 { margin: 3px 0 0; font-size: 17px; letter-spacing: -.025em; }
.stage-heading > div { min-width: 0; }.stage-heading p { overflow-wrap: anywhere; margin: 5px 0 0; color: var(--muted); font-size: 12px; }
.count-chip { display: grid; min-width: 28px; height: 28px; color: var(--accent-deep); border-radius: 50%; background: var(--accent-soft); font: 700 12px/1 monospace; place-items: center; }
.library-card { display: grid; grid-template-columns: 38px minmax(0,1fr) 16px; align-items: center; width: 100%; margin-bottom: 8px; padding: 13px 10px; gap: 9px; color: var(--ink); border: 1px solid transparent; border-radius: 13px; cursor: pointer; text-align: left; background: transparent; transition: all var(--motion-fast); }
.library-card:hover { border-color: rgba(30,90,103,.14); background: rgba(255,255,255,.58); }.library-card--active { border-color: rgba(30,90,103,.21); background: #fff; box-shadow: var(--shadow-sm); }
.library-glyph { display: grid; width: 38px; height: 38px; color: var(--accent); border-radius: 11px; background: var(--accent-soft); place-items: center; }.library-copy { min-width: 0; }.library-copy strong, .library-copy small, .library-copy span { display: block; }.library-copy strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.library-copy small { overflow: hidden; margin: 3px 0 7px; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.library-copy span { color: var(--ink-soft); font-size: 10px; }
.status-dot { display: inline-block; width: 6px; height: 6px; margin-right: 4px; border-radius: 50%; }.status-dot--success { background: var(--success); }.status-dot--working { background: var(--gold); box-shadow: 0 0 0 3px var(--gold-soft); }.status-dot--danger { background: var(--danger); }.status-dot--neutral { background: var(--muted); }
.rail-footer { margin-top: auto; padding-top: 18px; border-top: 1px solid rgba(23,33,43,.1); }.rail-footer span,.rail-footer strong,.rail-footer small { display: block; }.rail-footer span { color: var(--muted); font: 10px monospace; letter-spacing: .12em; }.rail-footer strong { margin: 6px 0; font-size: 13px; }.rail-footer small { color: var(--muted); font-family: monospace; }.rail-footer .rag-button { margin-top: 12px; }
.rail-empty, .document-empty, .lab-locked { display: grid; place-items: center; color: var(--muted); text-align: center; }.rail-empty { margin: 40px 8px; gap: 8px; }.rail-empty strong,.document-empty strong,.lab-locked strong { color: var(--ink-soft); }.rail-empty span { font-size: 12px; line-height: 1.5; }
.document-stage { background: rgba(255,255,252,.78); }.sr-file { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; }
.upload-progress { position: relative; margin: -6px 0 16px; overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-muted); }.upload-progress > span { position: absolute; inset: 0 auto 0 0; background: linear-gradient(90deg, var(--accent-soft), rgba(169,121,57,.22)); transition: width .2s; }.upload-progress div { position: relative; display: flex; justify-content: space-between; padding: 9px 11px; font-size: 11px; }.upload-progress em { font-style: normal; font-weight: 700; }
.task-list { display: grid; max-height: 330px; margin-bottom: 14px; gap: 8px; overflow-y: auto; }.task-card { display: grid; grid-template-columns: 42px minmax(0,1fr) auto; align-items: center; padding: 14px; gap: 12px; border: 1px solid rgba(169,121,57,.24); border-radius: 14px; background: linear-gradient(105deg, var(--gold-soft), #fffaf0); }.task-card--danger { border-color: rgba(155,62,62,.2); background: var(--danger-soft); }.task-orbit { display: grid; width: 42px; height: 42px; color: var(--gold); border-radius: 50%; background: rgba(255,255,255,.7); place-items: center; }.task-card--danger .task-orbit,.task-error { color: var(--danger) !important; }.task-main span,.task-main small { display: block; color: var(--muted); font-size: 10px; }.task-main strong { display: block; margin: 3px 0 7px; font-size: 12px; }.task-progress { height: 3px; margin-bottom: 6px; overflow: hidden; border-radius: 3px; background: rgba(23,33,43,.1); }.task-progress span { display: block; height: 100%; background: var(--gold); transition: width .35s; }.task-error { margin-top: 4px; overflow-wrap: anywhere; }
.document-list { display: grid; gap: 9px; }.document-row { display: grid; grid-template-columns: 48px minmax(0,1fr) 76px auto auto; align-items: center; min-height: 76px; padding: 11px 13px; gap: 12px; border: 1px solid var(--line); border-radius: 13px; background: #fff; transition: transform var(--motion-fast), box-shadow var(--motion-fast); }.document-row:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm); }.file-mark { display: grid; width: 42px; height: 48px; color: var(--accent-deep); border-radius: 7px 13px 7px 7px; background: var(--accent-soft); font: 800 9px monospace; place-items: center; }.file-mark[data-type="PDF"] { color: var(--danger); background: var(--danger-soft); }.file-mark[data-type="DOCX"] { color: #315d91; background: #e3ebf5; }.document-copy { min-width: 0; }.document-copy strong,.document-copy span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.document-copy strong { font-size: 13px; }.document-copy span { margin-top: 5px; color: var(--muted); font: 10px monospace; }.document-generation small,.document-generation strong { display: block; text-align: center; }.document-generation small { color: var(--muted); font: 8px monospace; letter-spacing: .08em; }.document-generation strong { margin-top: 4px; font: 700 14px monospace; }.status-pill { padding: 5px 8px; border-radius: 999px; font-size: 10px; font-weight: 700; white-space: nowrap; }.status-pill--success { color: var(--success); background: var(--success-soft); }.status-pill--working { color: var(--warning); background: var(--warning-soft); }.status-pill--danger { color: var(--danger); background: var(--danger-soft); }.status-pill--neutral { color: var(--muted); background: var(--surface-muted); }.document-delete { display: inline-flex; align-items: center; min-height: 34px; padding: 0 9px; gap: 6px; color: var(--danger); border: 1px solid rgba(155,62,62,.16); border-radius: 9px; cursor: pointer; background: var(--danger-soft); font-size: 11px; font-weight: 700; }.document-delete:disabled { cursor: wait; opacity: .55; }
.document-empty { min-height: 430px; }.document-empty p { max-width: 360px; margin: 8px auto; font-size: 12px; line-height: 1.65; }.empty-illustration { position: relative; display: grid; width: 88px; height: 88px; margin-bottom: 17px; color: var(--accent); border: 1px solid var(--line); border-radius: 25px; background: var(--surface-muted); place-items: center; transform: rotate(-3deg); }.empty-illustration span { position: absolute; right: -8px; bottom: -8px; width: 32px; height: 32px; border-radius: 50%; background: var(--gold-soft); }
.lab-heading svg { color: var(--gold); }.lab-tabs { display: grid; grid-template-columns: repeat(3,1fr); margin-bottom: 18px; padding: 3px; border-radius: 10px; background: var(--bg-strong); }.lab-tabs button { padding: 8px; color: var(--muted); border-radius: 8px; cursor: pointer; font-size: 11px; font-weight: 700; background: transparent; }.lab-tabs button.active { color: var(--ink); background: #fff; box-shadow: 0 4px 12px rgba(23,33,43,.07); }
.lab-locked { min-height: 420px; padding: 30px; }.lab-locked p { line-height: 1.7; }.lab-form { display: grid; gap: 9px; }.lab-form > label,.rag-modal > label,.profile-grid > label { color: var(--ink-soft); font-size: 11px; font-weight: 700; }.label-row { display: flex; justify-content: space-between; margin-top: 5px; color: var(--ink-soft); font-size: 11px; font-weight: 700; }.label-row span { color: var(--muted); font-family: monospace; }.lab-input { width: 100%; min-height: 39px; padding: 0 10px; color: var(--ink); border: 1px solid var(--line-strong); border-radius: 9px; outline: none; background: #fff; transition: border var(--motion-fast), box-shadow var(--motion-fast); }.lab-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(30,90,103,.1); }.lab-textarea { min-height: 92px; padding: 10px; resize: vertical; line-height: 1.55; }.compact-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.compact-fields label { color: var(--ink-soft); font-size: 10px; font-weight: 700; }.compact-fields input { margin-top: 5px; }.check-row { display: flex; align-items: flex-start; gap: 9px; padding: 9px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }.check-row input { margin-top: 2px; accent-color: var(--accent); }.check-row span,.check-row strong,.check-row small { display: block; }.check-row small { margin-top: 3px; color: var(--muted); font-weight: 400; line-height: 1.45; }
.debug-result { margin-top: 18px; padding-top: 16px; border-top: 1px dashed var(--line-strong); }.result-summary { display: grid; grid-template-columns: 1fr auto; align-items: center; }.result-summary > span { width: max-content; padding: 4px 7px; color: var(--success); border-radius: 6px; background: var(--success-soft); font-size: 9px; font-weight: 800; }.result-summary > span.degraded { color: var(--warning); background: var(--warning-soft); }.result-summary strong { grid-row: span 2; font: 700 28px/1 monospace; }.result-summary strong small { font-size: 10px; }.result-summary em { margin-top: 7px; color: var(--muted); font-size: 10px; font-style: normal; }.metric-strip { display: grid; grid-template-columns: repeat(2,1fr); margin: 13px 0; gap: 6px; }.metric-strip span { padding: 8px; border: 1px solid var(--line); border-radius: 8px; background: #fff; }.metric-strip small,.metric-strip strong { display: block; }.metric-strip small { color: var(--muted); font: 8px monospace; text-transform: uppercase; }.metric-strip strong { margin-top: 4px; font: 700 10px monospace; }.degrade-note { display: flex; margin-bottom: 9px; padding: 8px; gap: 6px; color: var(--warning); border-radius: 8px; background: var(--warning-soft); font-size: 10px; line-height: 1.4; }.citation-card { margin-top: 8px; padding: 11px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }.citation-card header,.citation-card footer { display: flex; align-items: center; gap: 7px; }.citation-card header span { color: var(--gold); font: 700 10px monospace; }.citation-card header strong { min-width: 0; flex: 1; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.citation-card header em { color: var(--accent); font: 700 10px monospace; }.citation-card p { display: -webkit-box; overflow: hidden; margin: 9px 0; color: var(--ink-soft); font-size: 11px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 4; }.citation-card footer { justify-content: space-between; color: var(--muted); font-size: 9px; }.no-hit { color: var(--muted); font-size: 11px; line-height: 1.6; text-align: center; }
.profile-list,.binding-list { display: grid; margin-top: 10px; gap: 7px; }.profile-card { display: grid; grid-template-columns: auto 1fr; padding: 11px; gap: 4px 8px; border: 1px solid var(--line); border-radius: 10px; cursor: pointer; text-align: left; background: #fff; }.mode-badge { grid-row: span 3; align-self: center; padding: 4px 5px; color: var(--accent); border-radius: 5px; background: var(--accent-soft); font: 700 8px monospace; text-transform: uppercase; }.profile-card strong { font-size: 11px; }.profile-card small { color: var(--muted); font: 9px monospace; }.profile-card > span:last-child { display: flex; align-items: center; color: var(--gold); font-size: 9px; }.binding-form { padding-bottom: 14px; border-bottom: 1px dashed var(--line-strong); }.binding-list article { position: relative; display: grid; padding: 10px 36px 10px 10px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }.binding-list article > span { color: var(--gold); font: 700 8px monospace; text-transform: uppercase; }.binding-list strong { margin: 3px 0; font-size: 11px; }.binding-list small { color: var(--muted); font-size: 9px; }.binding-list button { position: absolute; top: 50%; right: 8px; display: grid; width: 27px; height: 27px; color: var(--danger); border-radius: 7px; cursor: pointer; background: var(--danger-soft); place-items: center; transform: translateY(-50%); }
.modal-backdrop { position: fixed; z-index: var(--z-modal); inset: 0; display: grid; overflow-y: auto; padding: 24px; background: rgba(18,26,32,.48); backdrop-filter: blur(8px); place-items: center; }.rag-modal { width: min(100%, 510px); padding: 24px; border: 1px solid rgba(255,255,255,.4); border-radius: 20px; background: var(--surface); box-shadow: 0 30px 90px rgba(10,20,24,.25); }.rag-modal--wide { width: min(100%, 720px); }.rag-modal header,.rag-modal footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }.rag-modal header { margin-bottom: 20px; }.rag-modal header span { color: var(--gold); font: 700 9px monospace; letter-spacing: .13em; }.rag-modal h2 { margin: 4px 0 0; font: 28px/1.1 "Fraunces", "Songti SC", serif; }.rag-modal header button { display: grid; width: 34px; height: 34px; color: var(--ink-soft); border-radius: 9px; cursor: pointer; background: var(--surface-muted); place-items: center; }.rag-modal > label { display: grid; margin-top: 13px; gap: 6px; }.rag-modal > p { display: flex; align-items: flex-start; margin: 15px 0; padding: 10px; gap: 7px; color: var(--muted); border-radius: 9px; background: var(--surface-muted); font-size: 11px; line-height: 1.5; }.rag-modal footer { margin-top: 20px; padding-top: 15px; border-top: 1px solid var(--line); }.profile-grid { display: grid; grid-template-columns: repeat(2,1fr); gap: 12px; }.profile-grid > label:not(.check-row) { display: grid; gap: 5px; }.span-two { grid-column: span 2; }
.skeleton-list,.document-skeleton { display: grid; gap: 9px; }.skeleton-list span,.document-skeleton span { border-radius: 12px; background: linear-gradient(90deg, var(--surface-muted) 25%, #fff 40%, var(--surface-muted) 60%); background-size: 300% 100%; animation: shimmer 1.4s infinite; }.skeleton-list span { height: 68px; }.document-skeleton span { height: 76px; }
.spin { animation: spin .9s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }@keyframes shimmer { to { background-position: -150% 0; } }
@media (max-width: 1260px) { .rag-workbench { grid-template-columns: 230px minmax(420px,1fr); }.retrieval-lab { grid-column: 1 / -1; border-top: 1px solid var(--line); border-left: 0; }.lab-form,.debug-result,.profile-list,.binding-list { max-width: 760px; }.retrieval-lab .lab-tabs { max-width: 460px; } }
@media (max-width: 800px) { .rag-page { overflow-x: clip; padding: 18px 14px; }.rag-hero { align-items: stretch; flex-direction: column; }.rag-hero > div { min-width: 0; max-width: 100%; }.rag-kicker { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.rag-hero h1 { font-size: clamp(32px, 11vw, 44px); }.hero-actions { flex-wrap: wrap; }.rag-workbench { display: block; width: 100%; border-radius: 16px; }.library-rail { border-right: 0; border-bottom: 1px solid var(--line); }.rail-footer { display: none; }.document-stage,.library-rail,.retrieval-lab { padding: 18px 14px; }.document-row { grid-template-columns: 42px minmax(0,1fr) auto; }.document-generation { display: none; }.document-delete { grid-column: 2 / -1; justify-content: center; }.stage-heading { align-items: flex-start; }.stage-heading .rag-button { flex: none; font-size: 0; }.stage-heading .rag-button svg { margin: 0; }.task-card { grid-template-columns: 38px minmax(0,1fr); }.task-card .rag-button { grid-column: 1 / -1; justify-content: center; }.profile-grid { grid-template-columns: 1fr; }.span-two { grid-column: auto; }.modal-backdrop { align-items: start; padding: 12px; }.rag-modal { margin: 20px 0; } }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; transition-duration: .01ms !important; } }
</style>
