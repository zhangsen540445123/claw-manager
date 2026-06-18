<script setup lang="ts">
defineProps<{
  label: string;
  value: string | number;
  tone?: "neutral" | "success" | "warning" | "danger";
}>();
</script>

<template>
  <section class="metric-card" :class="`tone-${tone || 'neutral'}`">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
  </section>
</template>
