<script setup lang="ts">
import { ref } from "vue";
import { RefreshCw } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();
const actionLoading = ref("");
const error = ref("");

async function runAction(name: string, action: () => Promise<unknown>) {
  actionLoading.value = name;
  error.value = "";
  try {
    await action();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "操作失败";
    ElMessage.error(error.value);
  } finally {
    actionLoading.value = "";
  }
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="系统运维" description="查看 Runner 镜像状态和后端服务日志。" />

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <div class="admin-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-title with-action">
            <span>Runner 镜像</span>
            <el-button
              size="small"
              :icon="RefreshCw"
              :loading="actionLoading === 'runner'"
              @click="runAction('runner', () => admin.refreshRunnerImage())"
            >
              刷新
            </el-button>
          </div>
        </template>
        <div class="runner-box">
          <el-tag :type="admin.runnerImage?.status === 'ready' ? 'success' : 'warning'" effect="plain">
            {{ admin.runnerImage?.status || "-" }}
          </el-tag>
          <span>{{ admin.runnerImage?.image }}</span>
          <p>{{ admin.runnerImage?.message }}</p>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-title with-action">
            <span>服务日志</span>
            <el-button
              size="small"
              :icon="RefreshCw"
              :loading="actionLoading === 'server-logs'"
              @click="runAction('server-logs', () => admin.loadServerLogs())"
            >
              刷新
            </el-button>
          </div>
        </template>
        <pre class="output server-log">{{ admin.serverLogs || "暂无服务日志，请确认后端已重启并启用文件日志。" }}</pre>
      </el-card>
    </div>
  </section>
</template>
