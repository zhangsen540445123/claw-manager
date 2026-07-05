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
const overviewRows = computed(() => admin.instances.map((instance) => ({
  id: instance.id,
  name: instance.name,
  status: instance.status,
  gatewayStatus: instance.provisioning?.status || "-",
  gatewayPercent: instance.provisioning?.percent || 0,
  model: instance.model ? `${instance.model.providerId}/${instance.model.modelId}` : "-",
  wechatAccounts: instance.wechatBinding?.pairedAccounts?.length || 0
})));

function statusType(status: string): "success" | "warning" | "danger" | "info" {
  if (status === "running" || status === "ready") return "success";
  if (status === "error" || status === "failed") return "danger";
  if (status === "starting" || status === "gateway-starting") return "warning";
  return "info";
}
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

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>实例概览</span>
          <el-tag effect="plain">{{ overviewRows.length }} 个实例</el-tag>
        </div>
      </template>
      <el-table :data="overviewRows" row-key="id">
        <el-table-column prop="name" label="实例名称" min-width="180" />
        <el-table-column label="运行状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Gateway" min-width="180">
          <template #default="{ row }">
            <div class="gateway-overview-cell">
              <el-tag :type="statusType(row.gatewayStatus)" effect="plain">{{ row.gatewayStatus }}</el-tag>
              <el-progress :percentage="row.gatewayPercent" :show-text="false" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="220" />
        <el-table-column prop="wechatAccounts" label="微信账号" width="110" align="right" />
      </el-table>
    </el-card>
  </section>
</template>

<style scoped>
.gateway-overview-cell {
  display: grid;
  gap: 6px;
}
</style>
