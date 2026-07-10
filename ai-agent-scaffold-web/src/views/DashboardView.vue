<template>
  <div class="page page-grid">
    <SectionHeader
      title="平台总览"
      description="这里汇总当前已经打通的身份、会话和观测能力，也给后续 RAG、MCP、Skill、工作流留下入口。"
    >
      <template #actions>
        <RouterLink class="button button--primary" to="/chat">进入会话</RouterLink>
      </template>
    </SectionHeader>

    <section class="stat-grid">
      <StatCard label="可用智能体" :value="chatStore.agents.length || '--'" hint="来自后端装配配置" />
      <StatCard label="当前身份" :value="authStore.roleCode || '--'" hint="JWT claims 恢复" />
      <StatCard label="当前会话" :value="chatStore.sessionId ? '已创建' : '未创建'" hint="chat_session 持久化" />
      <StatCard label="观测链路" value="Loki" hint="日志经 Alloy 推送" />
    </section>

    <section class="page-grid page-grid--two">
      <div class="card">
        <div class="card__body">
          <SectionHeader
            title="当前建设节奏"
            description="先把企业平台的身份边界、会话边界、观测边界做稳，再逐步打开可配置的 Agent 生态。"
            :level="2"
          />
          <div class="roadmap">
            <div v-for="item in roadmap" :key="item.title" class="roadmap__item">
              <span :class="['badge', item.badgeClass]">{{ item.status }}</span>
              <div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.desc }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card__body">
          <SectionHeader title="租户身份" description="后续所有资源授权都会优先使用这里的 tenantId / userId。" :level="2" />
          <div class="identity-list">
            <span>tenantId</span>
            <strong>{{ authStore.tenantId || '--' }}</strong>
            <span>userId</span>
            <strong>{{ authStore.userId || '--' }}</strong>
            <span>username</span>
            <strong>{{ authStore.auth?.username || '--' }}</strong>
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
const roadmap = [
  { status: '已接入', badgeClass: 'badge--green', title: '登录注册与 JWT', desc: '请求身份从后端 JWT 恢复，不再信任前端手传 userId。' },
  { status: '已接入', badgeClass: 'badge--green', title: '会话持久化', desc: '创建会话和消息写入数据库，租户和用户隔离。' },
  { status: '已接入', badgeClass: 'badge--green', title: '日志观测', desc: 'Grafana + Loki 查看 token_usage、model_error 与业务日志。' },
  { status: '占位中', badgeClass: 'badge--gold', title: '企业 Agent 生态', desc: 'MCP、Skill、RAG、工作流和附件资产即将在此统一配置。' },
];

onMounted(() => {
  if (chatStore.agents.length === 0) {
    chatStore.loadAgents();
  }
});
</script>

<style scoped>
.roadmap {
  display: grid;
  gap: 1px;
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--line);
}

.roadmap__item {
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 12px;
  background: var(--surface);
}

.roadmap__item p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.identity-list {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px;
  margin-top: 16px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-muted);
}

.identity-list span {
  color: var(--muted);
}

.identity-list strong {
  overflow-wrap: anywhere;
}
</style>
