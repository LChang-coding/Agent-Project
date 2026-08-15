<template>
  <div class="page dashboard-page">
    <section class="dashboard-hero">
      <div class="dashboard-hero__copy">
        <span class="dashboard-eyebrow">CONTROL PLANE / OVERVIEW</span>
        <h1>让智能体系统<br />可构建、可运行、可追溯。</h1>
        <p>Agent、工作流、RAG 与工具使用同一租户边界和链路标识，从配置到执行保持一致。</p>
        <div class="dashboard-hero__actions">
          <RouterLink class="button button--primary" to="/chat">进入 Agent 编排</RouterLink>
          <RouterLink class="button button--dark" to="/workflow">编排工作流</RouterLink>
        </div>
      </div>
      <div class="dashboard-hero__signal" aria-label="平台能力状态">
        <div class="signal-head"><span><i /> PLATFORM READY</span><b>v1</b></div>
        <div class="signal-row"><span>Identity boundary</span><strong>JWT / Tenant</strong><b>READY</b></div>
        <div class="signal-row"><span>Execution plane</span><strong>Agent / DAG</strong><b>READY</b></div>
        <div class="signal-row"><span>Knowledge layer</span><strong>RAG / Tools</strong><b>READY</b></div>
        <div class="signal-row"><span>Trace pipeline</span><strong>Trace ID / Loki</strong><b>READY</b></div>
      </div>
    </section>

    <section class="stat-grid">
      <StatCard label="可用智能体" :value="chatStore.agents.length || '--'" hint="当前作用域的运行目标" />
      <StatCard label="当前角色" :value="authStore.roleCode || '--'" hint="由 JWT 恢复的权限身份" />
      <StatCard label="会话状态" :value="chatStore.sessionId ? '进行中' : '待创建'" hint="按租户与用户隔离" />
      <StatCard label="链路观测" value="ACTIVE" hint="Trace ID 贯穿业务节点" />
    </section>

    <section class="dashboard-grid">
      <div class="card capability-card">
        <div class="card__body">
          <SectionHeader
            title="能力矩阵"
            description="已打通的生产能力和对应管理入口。"
            :level="2"
          />
          <div class="capability-list">
            <RouterLink v-for="(item, index) in capabilities" :key="item.title" :to="item.path" class="capability-item">
              <span class="capability-item__index">0{{ index + 1 }}</span>
              <div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.desc }}</p>
              </div>
              <span class="capability-item__status"><i /> {{ item.status }}</span>
            </RouterLink>
          </div>
        </div>
      </div>

      <div class="card identity-card">
        <div class="card__body">
          <SectionHeader title="当前作用域" description="所有资源与执行都绑定到当前身份。" :level="2" />
          <div class="identity-list">
            <div><span>TENANT</span><strong>{{ authStore.tenantId || '--' }}</strong></div>
            <div><span>USER</span><strong>{{ authStore.userId || '--' }}</strong></div>
            <div><span>ACCOUNT</span><strong>{{ authStore.auth?.username || '--' }}</strong></div>
          </div>
          <RouterLink class="identity-link" to="/tenant">查看租户与成员 <span>→</span></RouterLink>
        </div>
      </div>

      <div class="card quick-card">
        <div class="card__body">
          <SectionHeader title="快速通道" description="按当前任务直达专业工作面。" :level="2" />
          <div class="quick-links">
            <RouterLink v-for="item in quickLinks" :key="item.path" :to="item.path">
              <span>{{ item.code }}</span><strong>{{ item.label }}</strong><small>{{ item.hint }}</small>
            </RouterLink>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { RouterLink } from 'vue-router';

import SectionHeader from '@/components/common/SectionHeader.vue';
import StatCard from '@/components/common/StatCard.vue';
import { useAuthStore } from '@/stores/auth';
import { useChatStore } from '@/stores/chat';

const authStore = useAuthStore();
const chatStore = useChatStore();
const capabilities = [
  { status: '已接入', title: '单 / Multi-Agent 编排', desc: '统一承载单 Agent 对话、子 Agent 委派、回调汇总与 Trace 追踪。', path: '/chat' },
  { status: '已接入', title: 'DAG 与智能工作流', desc: '固定图执行与 Agent 动态路由使用统一节点事件。', path: '/workflow' },
  { status: '已接入', title: 'RAG 知识库', desc: '文档摄取、绑定策略、检索实验与会话按需调用。', path: '/rag' },
  { status: '已接入', title: 'MCP 与 Skill 工具层', desc: '外部工具登记、测试、发布与节点白名单控制。', path: '/mcp' },
];
const quickLinks = [
  { code: '01', label: 'Agent 编排', hint: '委派子 Agent / 运行 DAG', path: '/chat' },
  { code: '02', label: '管理知识', hint: '摄取与检索实验', path: '/rag' },
  { code: '03', label: '查看链路', hint: 'Trace ID / Grafana', path: '/observability' },
];

onMounted(() => {
  if (chatStore.agents.length === 0) {
    chatStore.loadAgents();
  }
});
</script>

<style scoped>
.dashboard-page {
  display: grid;
  gap: 14px;
}

.dashboard-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, .55fr);
  min-height: 330px;
  color: #f3f3ed;
  border: 1px solid #202820;
  background: var(--surface-ink);
}

.dashboard-hero__copy {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  justify-content: center;
  padding: clamp(30px, 5vw, 68px);
}

.dashboard-eyebrow {
  color: #f08a6d;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .13em;
}

.dashboard-hero h1 {
  margin: 18px 0 0;
  font-family: "Newsreader Variable", "Songti SC", serif;
  font-size: clamp(38px, 3.45vw, 50px);
  font-weight: 520;
  line-height: .98;
  letter-spacing: -.055em;
}

.dashboard-hero__copy > p {
  max-width: 720px;
  margin: 22px 0 0;
  color: #b9c0b8;
  font-size: 14px;
  line-height: 1.75;
}

.dashboard-hero__actions {
  display: flex;
  gap: 8px;
  margin-top: 26px;
}

.dashboard-hero__signal {
  align-self: center;
  margin: 24px 24px 24px 0;
  border: 1px solid #465148;
  background: #202720;
}

.signal-head,
.signal-row {
  display: grid;
  align-items: center;
  border-bottom: 1px solid #3a443b;
}

.signal-head {
  grid-template-columns: 1fr auto;
  padding: 11px 13px;
  color: #d5dbd3;
  font-size: 10px;
  letter-spacing: .11em;
}

.signal-head span,
.signal-row b {
  display: flex;
  align-items: center;
  gap: 7px;
}

.signal-head i,
.signal-row b::before {
  width: 6px;
  height: 6px;
  content: "";
  border-radius: 50%;
  background: #7bc797;
}

.signal-row {
  grid-template-columns: minmax(0, 1fr) minmax(0, .85fr) auto;
  gap: 12px;
  padding: 13px;
  font-size: 11px;
}

.signal-row:last-child {
  border-bottom: 0;
}

.signal-row span { color: #8e998f; }
.signal-row strong { color: #e5e8e2; font-weight: 620; }
.signal-row b { color: #8dd2a6; font-size: 9px; letter-spacing: .08em; }

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(310px, .65fr);
  gap: 12px;
}

.capability-card {
  grid-row: span 2;
}

.capability-list {
  display: grid;
  margin-top: 18px;
  border-top: 1px solid var(--line);
}

.capability-item {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 17px 4px;
  border-bottom: 1px solid var(--line);
  transition: background var(--motion-fast), padding var(--motion-fast);
}

.capability-item:hover {
  padding-right: 10px;
  padding-left: 10px;
  background: var(--surface-muted);
}

.capability-item__index {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
}

.capability-item p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.capability-item__status {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--success);
  font-size: 10px;
  font-weight: 800;
}

.capability-item__status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.identity-list {
  display: grid;
  gap: 0;
  margin-top: 16px;
  border-top: 1px solid var(--line);
}

.identity-list div {
  display: grid;
  gap: 5px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}

.identity-list span {
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: .11em;
}

.identity-list strong {
  overflow-wrap: anywhere;
  font-family: ui-monospace, "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
}

.identity-link {
  display: flex;
  justify-content: space-between;
  padding-top: 15px;
  color: var(--accent-deep);
  font-size: 12px;
  font-weight: 700;
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

.quick-links a {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--line);
  background: var(--surface-muted);
}

.quick-links span {
  color: var(--accent);
  font-size: 9px;
  font-weight: 800;
}

.quick-links strong { font-size: 12px; }
.quick-links small { color: var(--muted); font-size: 10px; }

@media (max-width: 1100px) {
  .dashboard-hero,
  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .dashboard-hero__signal {
    margin: 0 24px 24px;
  }

  .capability-card {
    grid-row: auto;
  }
}

@media (max-width: 680px) {
  .dashboard-hero {
    min-height: 0;
  }

  .dashboard-hero__copy {
    padding: 30px 22px;
  }

  .dashboard-hero h1 {
    font-size: 38px;
  }

  .dashboard-hero__signal {
    margin: 0 12px 12px;
  }

  .signal-row {
    grid-template-columns: 1fr auto;
  }

  .signal-row strong { display: none; }
  .quick-links { grid-template-columns: 1fr; }
  .capability-item { grid-template-columns: 28px minmax(0, 1fr); }
  .capability-item__status { grid-column: 2; }
}
</style>
