<script setup lang="ts">
defineProps<{
  label: string;
  value: string | number;
  tone?: "neutral" | "success" | "warning" | "danger";
}>();
</script>

<template>
  <section class="metric-card" :class="`tone-${tone || 'neutral'}`">
    <span class="metric-label">{{ label }}</span>
    <strong class="metric-value">{{ value }}</strong>
    <span class="metric-accent" aria-hidden="true" />
  </section>
</template>
