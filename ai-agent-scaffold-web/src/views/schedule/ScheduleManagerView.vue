<template>
  <div class="page page-grid">
    <SectionHeader
      title="定时任务"
      description="配置会收敛为数据库唯一运行态；XXL-JOB 只负责唤醒，对账、抢占、重试和执行历史由业务库控制。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" :disabled="loading" @click="loadSchedules">刷新</button>
          <button class="button button--soft" type="button" @click="resetForm">新建配置</button>
          <button class="button button--primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中...' : editingId ? '保存修改' : '创建任务' }}
          </button>
        </div>
      </template>
    </SectionHeader>

    <div v-if="message" :class="['notice', messageType === 'error' ? 'notice--error' : 'notice--success']">
      {{ message }}
    </div>

    <section class="schedule-grid">
      <div class="card">
        <div class="card__body">
          <SectionHeader
            :title="editingId ? '修改定时配置' : '创建定时配置'"
            description="执行身份固定为当前登录用户；载荷只保存消息文本，不接受客户端传入 runAs。"
            :level="2"
          />
          <div class="form-grid schedule-form">
            <div class="field two-cols">
              <div>
                <label>Agent</label>
                <select v-model="form.agentId" class="select" @change="syncAgentName">
                  <option value="" disabled>选择可用 Agent</option>
                  <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                    {{ agent.agentName }}
                  </option>
                </select>
              </div>
              <div>
                <label>时区</label>
                <select v-model="form.timezone" class="select">
                  <option value="Asia/Shanghai">Asia/Shanghai</option>
                  <option value="UTC">UTC</option>
                  <option :value="browserTimezone">浏览器：{{ browserTimezone }}</option>
                </select>
              </div>
            </div>
            <div class="field">
              <label>定时消息</label>
              <textarea v-model="form.message" class="textarea" maxlength="20000" placeholder="到期时交给 Agent 的完整指令" />
              <small>{{ form.message.length }} / 20000</small>
            </div>
            <div class="field cron-row">
              <div>
                <label>Spring 六段式 Cron</label>
                <input v-model="form.cronExpr" class="input mono" placeholder="0 0 9 * * *" />
              </div>
              <button class="button" type="button" :disabled="previewing" @click="previewCron">
                {{ previewing ? '计算中...' : '预览时间' }}
              </button>
            </div>
            <div class="field two-cols">
              <div>
                <label>错过执行策略</label>
                <select v-model="form.misfirePolicy" class="select">
                  <option value="fire_once_now">立即补一次，再从当前时间推进</option>
                  <option value="skip">跳过错过的触发点</option>
                  <option value="catch_up">按计划时间追赶（受批次上限保护）</option>
                </select>
              </div>
              <div>
                <label>最大重试次数</label>
                <input v-model.number="form.maxRetries" class="input" type="number" min="0" max="10" />
              </div>
            </div>
            <label class="switch-row">
              <input v-model="form.enabled" type="checkbox" />
              <span>保存后立即启用</span>
            </label>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card__body">
          <SectionHeader title="后续触发预览" description="后端按所选时区计算并以 UTC 入库，此处转换为浏览器本地时间。" :level="2" />
          <ol v-if="previewTimes.length" class="timeline-list">
            <li v-for="time in previewTimes" :key="time">
              <strong>{{ formatTime(time) }}</strong>
              <span>{{ normalizeUtc(time) }} UTC</span>
            </li>
          </ol>
          <div v-else class="empty-card">输入 Cron 后点击“预览时间”，保存前即可验证节奏与时区。</div>
          <div class="policy-note">
            <strong>执行语义</strong>
            <p>数据库以 triggerKey 保证同一计划点只有一条逻辑执行；进程宕机后由租约接管。外部模型与工具调用属于至少一次恢复语义。</p>
          </div>
        </div>
      </div>
    </section>

    <div class="table-card">
      <div class="table-toolbar">
        <strong>我的配置</strong>
        <span>{{ schedules.length }} 项</span>
      </div>
      <div class="table-scroll">
        <table class="table">
          <thead>
            <tr>
              <th>任务</th>
              <th>Cron / 时区</th>
              <th>策略</th>
              <th>版本</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="schedule in schedules" :key="schedule.configId">
              <td>
                <strong>{{ schedule.agentName || schedule.agentId }}</strong>
                <small>{{ schedule.message }}</small>
              </td>
              <td><span class="mono">{{ schedule.cronExpr }}</span><small>{{ schedule.timezone }}</small></td>
              <td>{{ policyLabel(schedule.misfirePolicy) }}<small>失败重试 {{ schedule.maxRetries }} 次</small></td>
              <td>v{{ schedule.configVersion }}<small>{{ formatTime(schedule.lastReconciledAt) }}</small></td>
              <td><span :class="['badge', schedule.enabled ? 'badge--green' : 'badge--red']">{{ schedule.enabled ? '已启用' : '已停用' }}</span></td>
              <td>
                <div class="button-row actions">
                  <button class="button" type="button" @click="edit(schedule)">编辑</button>
                  <button class="button" type="button" @click="showExecutions(schedule)">历史</button>
                  <button class="button" type="button" :disabled="!schedule.enabled" @click="trigger(schedule, false)">立即执行</button>
                  <button class="button" type="button" @click="toggleEnabled(schedule)">{{ schedule.enabled ? '停用' : '启用' }}</button>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && schedules.length === 0"><td colspan="6">暂无定时任务，先创建一项配置。</td></tr>
            <tr v-if="loading"><td colspan="6">正在加载定时任务...</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="historyConfig" class="table-card">
      <div class="table-toolbar">
        <div><strong>执行历史</strong><span>{{ historyConfig.agentName || historyConfig.agentId }}</span></div>
        <button class="button" type="button" @click="showExecutions(historyConfig)">刷新历史</button>
      </div>
      <div class="table-scroll">
        <table class="table">
          <thead><tr><th>计划时间</th><th>状态</th><th>尝试</th><th>耗时</th><th>错误</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="execution in executions" :key="execution.executionId">
              <td>{{ formatTime(execution.plannedTime) }}<small>{{ execution.executionId }}</small></td>
              <td><span :class="['badge', executionClass(execution.status)]">{{ executionLabel(execution.status) }}</span></td>
              <td>{{ execution.attemptNo }}</td>
              <td>{{ execution.durationMs == null ? '--' : `${execution.durationMs} ms` }}</td>
              <td><span class="error-cell">{{ execution.errorMessage || '--' }}</span></td>
              <td><button v-if="['failed', 'dead'].includes(execution.status)" class="button" type="button" @click="trigger(historyConfig, true)">重新触发</button></td>
            </tr>
            <tr v-if="executions.length === 0"><td colspan="6">暂无执行记录。</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import { queryAgentConfigs } from '@/api/agent';
import {
  createSchedule,
  previewScheduleCron,
  queryScheduleExecutions,
  querySchedules,
  setScheduleEnabled,
  triggerSchedule,
  updateSchedule,
} from '@/api/schedule';
import SectionHeader from '@/components/common/SectionHeader.vue';
import type {
  AiAgentConfig,
  ScheduleConfig,
  ScheduleExecution,
  ScheduleMisfirePolicy,
  ScheduleSaveRequest,
} from '@/types/api';

const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai';
const agents = ref<AiAgentConfig[]>([]);
const schedules = ref<ScheduleConfig[]>([]);
const executions = ref<ScheduleExecution[]>([]);
const historyConfig = ref<ScheduleConfig | null>(null);
const editingId = ref('');
const previewTimes = ref<string[]>([]);
const loading = ref(false);
const saving = ref(false);
const previewing = ref(false);
const message = ref('');
const messageType = ref<'success' | 'error'>('success');

const form = reactive<ScheduleSaveRequest>({
  agentId: '',
  agentName: '',
  message: '',
  cronExpr: '0 0 9 * * *',
  timezone: 'Asia/Shanghai',
  enabled: true,
  misfirePolicy: 'fire_once_now',
  maxRetries: 3,
});

onMounted(async () => {
  loading.value = true;
  try {
    const [agentItems, scheduleItems] = await Promise.all([queryAgentConfigs(), querySchedules()]);
    agents.value = agentItems || [];
    schedules.value = scheduleItems || [];
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
});

async function loadSchedules() {
  loading.value = true;
  try {
    schedules.value = (await querySchedules()) || [];
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!form.agentId || !form.message.trim() || !form.cronExpr.trim()) {
    return setMessage('请选择 Agent，并填写消息与 Cron。', 'error');
  }
  saving.value = true;
  try {
    const payload = { ...form, message: form.message.trim(), cronExpr: form.cronExpr.trim() };
    const saved = editingId.value
      ? await updateSchedule(editingId.value, payload)
      : await createSchedule(payload);
    setMessage(`定时任务已${editingId.value ? '更新' : '创建'}，配置版本 v${saved.configVersion}。`, 'success');
    resetForm();
    await loadSchedules();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function previewCron() {
  previewing.value = true;
  try {
    previewTimes.value = await previewScheduleCron(form.cronExpr.trim(), form.timezone, 5);
  } catch (error) {
    previewTimes.value = [];
    showError(error);
  } finally {
    previewing.value = false;
  }
}

function edit(schedule: ScheduleConfig) {
  editingId.value = schedule.configId;
  Object.assign(form, {
    agentId: schedule.agentId,
    agentName: schedule.agentName || '',
    message: schedule.message,
    cronExpr: schedule.cronExpr,
    timezone: schedule.timezone,
    enabled: schedule.enabled,
    misfirePolicy: schedule.misfirePolicy,
    maxRetries: schedule.maxRetries,
  });
  previewTimes.value = [];
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function resetForm() {
  editingId.value = '';
  Object.assign(form, {
    agentId: '', agentName: '', message: '', cronExpr: '0 0 9 * * *', timezone: 'Asia/Shanghai',
    enabled: true, misfirePolicy: 'fire_once_now' as ScheduleMisfirePolicy, maxRetries: 3,
  });
  previewTimes.value = [];
}

function syncAgentName() {
  form.agentName = agents.value.find((agent) => agent.agentId === form.agentId)?.agentName || '';
}

async function toggleEnabled(schedule: ScheduleConfig) {
  try {
    await setScheduleEnabled(schedule.configId, !schedule.enabled);
    setMessage(`任务已${schedule.enabled ? '停用' : '启用'}。`, 'success');
    await loadSchedules();
  } catch (error) {
    showError(error);
  }
}

async function trigger(schedule: ScheduleConfig, retry: boolean) {
  try {
    await triggerSchedule(schedule.configId, retry);
    setMessage(retry ? '失败任务已重新排入派发。' : '任务已标记为立即到期。', 'success');
    if (historyConfig.value?.configId === schedule.configId) await showExecutions(schedule);
  } catch (error) {
    showError(error);
  }
}

async function showExecutions(schedule: ScheduleConfig) {
  historyConfig.value = schedule;
  try {
    executions.value = (await queryScheduleExecutions(schedule.configId, 50)) || [];
  } catch (error) {
    showError(error);
  }
}

function normalizeUtc(value?: string) {
  return value ? value.replace('T', ' ') : '--';
}

function formatTime(value?: string) {
  if (!value) return '--';
  const utcValue = /Z$|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`;
  const date = new Date(utcValue);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function policyLabel(value: ScheduleMisfirePolicy) {
  return ({ fire_once_now: '补一次', skip: '跳过', catch_up: '追赶' })[value] || value;
}

function executionLabel(status: string) {
  return ({ running: '执行中', success: '成功', failed: '待重试', dead: '重试耗尽' } as Record<string, string>)[status] || status;
}

function executionClass(status: string) {
  if (status === 'success') return 'badge--green';
  if (status === 'running') return 'badge--gold';
  return 'badge--red';
}

function setMessage(text: string, type: 'success' | 'error') {
  message.value = text;
  messageType.value = type;
}

function showError(error: unknown) {
  setMessage(error instanceof Error ? error.message : '定时任务操作失败', 'error');
}
</script>

<style scoped>
.schedule-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr); gap: 16px; }
.schedule-form { margin-top: 18px; }
.two-cols { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.field small, .table small { display: block; margin-top: 5px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.cron-row { grid-template-columns: minmax(0, 1fr) auto; align-items: end; }
.mono { font-family: "IBM Plex Mono", "SFMono-Regular", Consolas, monospace; font-size: 12px; }
.switch-row { display: flex; align-items: center; gap: 9px; color: var(--ink-soft); font-size: 13px; font-weight: 700; }
.timeline-list { display: grid; gap: 10px; padding: 0; margin: 18px 0 0; list-style: none; }
.timeline-list li { display: grid; gap: 4px; padding: 12px; border-left: 3px solid var(--accent); border-radius: 0 10px 10px 0; background: var(--surface-muted); }
.timeline-list span { color: var(--muted); font-family: monospace; font-size: 12px; }
.policy-note { margin-top: 18px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--gold-soft); }
.policy-note p { margin: 7px 0 0; color: var(--ink-soft); font-size: 13px; line-height: 1.7; }
.notice { padding: 11px 14px; border-radius: 10px; font-size: 13px; }
.notice--success { color: var(--success); background: var(--success-soft); }
.notice--error { color: var(--danger); background: var(--danger-soft); }
.table-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 52px; padding: 10px 14px; border-bottom: 1px solid var(--line); }
.table-toolbar > div { display: flex; align-items: center; gap: 10px; }
.table-toolbar span { color: var(--muted); font-size: 12px; }
.table-scroll { overflow-x: auto; }
.table { min-width: 940px; }
.table td:first-child { max-width: 300px; }
.table td:first-child small { overflow: hidden; max-width: 300px; text-overflow: ellipsis; white-space: nowrap; }
.actions { min-width: 285px; }
.error-cell { display: block; max-width: 280px; color: var(--danger); font-size: 12px; white-space: normal; }
@media (max-width: 980px) { .schedule-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .two-cols, .cron-row { grid-template-columns: 1fr; } }
</style>
