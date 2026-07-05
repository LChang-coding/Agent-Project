<template>
  <section class="placeholder">
    <div class="placeholder__body">
      <span class="placeholder__eyebrow">{{ status }}</span>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
      <ul v-if="items.length" class="placeholder__list">
        <li v-for="item in items" :key="item">{{ item }}</li>
      </ul>
      <div v-if="$slots.default" style="margin-top: 22px">
        <slot />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string;
    description: string;
    status?: string;
    items?: string[];
  }>(),
  {
    status: '功能占位',
    items: () => [],
  },
);
</script>
