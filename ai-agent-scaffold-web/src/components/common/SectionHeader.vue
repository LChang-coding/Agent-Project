<template>
  <header class="section-header">
    <div>
      <component :is="levelTag">{{ title }}</component>
      <p v-if="description">{{ description }}</p>
    </div>
    <slot name="actions" />
  </header>
</template>

<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    title: string;
    description?: string;
    level?: 1 | 2;
  }>(),
  {
    description: '',
    level: 1,
  },
);

const levelTag = props.level === 1 ? 'h1' : 'h2';
</script>
