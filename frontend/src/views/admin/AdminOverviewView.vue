<script setup lang="ts">
import { computed } from "vue";
import { RefreshCw } from "lucide-vue-next";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();

const runningInstances = computed(() => admin.instances.filter((instance) => instance.status === "running").length);
const readyInstances = computed(() => admin.instances.filter((instance) => instance.provisioning?.status === "ready").length);
const boundAccounts = computed(() => admin.instances.reduce((sum, instance) => sum + (instance.wechatBinding?.pairedAccounts?.length || 0), 0));
const configuredPresets = computed(() => admin.presets.filter((preset) => preset.isConfigured).length);
</script>

<template>
  <section class="workspace">
    <PageHeader title="运行总览" description="OpenClaw 实例、模型预设和微信绑定的当前状态。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="admin.loading" @click="admin.loadAll()">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="admin.error" :title="admin.error" type="error" show-icon />

    <section class="metric-grid">
      <MetricCard label="运行实例" :value="runningInstances" tone="success" />
      <MetricCard label="就绪实例" :value="readyInstances" />
      <MetricCard label="微信账号" :value="boundAccounts" tone="warning" />
      <MetricCard label="可用预设" :value="configuredPresets" />
    </section>
  </section>
</template>
