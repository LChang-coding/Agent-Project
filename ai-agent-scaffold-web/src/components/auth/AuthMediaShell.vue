<template>
  <aside class="auth-hero" :class="{ 'auth-hero--paused': paused }">
    <RouterLink class="auth-hero__brand" to="/auth/login" aria-label="Agent OS 首页">
      <span class="brand-mark" aria-hidden="true">A</span>
      <span>
        <strong>Agent OS</strong>
        <small>Enterprise runtime</small>
      </span>
    </RouterLink>

    <div class="auth-hero__copy">
      <span class="auth-eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>

    <div class="auth-runtime" aria-label="智能体运行时动画示意">
      <div class="auth-runtime__head">
        <span><i aria-hidden="true" /> LIVE EXECUTION GRAPH</span>
        <button type="button" :aria-pressed="paused" @click="paused = !paused">
          {{ paused ? '继续动画' : '暂停动画' }}
        </button>
      </div>
      <div class="auth-runtime__graph" aria-hidden="true">
        <div class="runtime-node runtime-node--input"><span>01</span><strong>Input</strong><small>validated</small></div>
        <span class="runtime-link runtime-link--one" />
        <div class="runtime-node runtime-node--reason"><span>02</span><strong>Reason</strong><small>running</small></div>
        <span class="runtime-link runtime-link--two" />
        <div class="runtime-node runtime-node--tools"><span>03</span><strong>Tools</strong><small>3 ready</small></div>
        <span class="runtime-link runtime-link--three" />
        <div class="runtime-node runtime-node--output"><span>04</span><strong>Output</strong><small>streaming</small></div>
        <span class="runtime-packet runtime-packet--one" />
        <span class="runtime-packet runtime-packet--two" />
      </div>
      <div class="auth-runtime__events" aria-hidden="true">
        <span><i>14:32:08</i> route.selected <b>support</b></span>
        <span><i>14:32:09</i> rag.retrieve <b>12 hits</b></span>
        <span><i>14:32:10</i> answer.stream <b>active</b></span>
      </div>
    </div>

    <div class="auth-hero__metrics" aria-label="平台能力">
      <div v-for="metric in metrics" :key="metric.value" class="auth-metric">
        <strong>{{ metric.value }}</strong>
        <span>{{ metric.label }}</span>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { RouterLink } from 'vue-router';

defineProps<{
  eyebrow: string;
  title: string;
  description: string;
  metrics: Array<{ value: string; label: string }>;
}>();

const paused = ref(false);
</script>
