<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { RefreshCw, Save, Send } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
const pushing = ref(false);
const form = reactive({ agentsMd: "", soulMd: "", identityMd: "", toolsMd: "", heartbeatMd: "", userMd: "" });

async function load() {
  loading.value = true;
  try {
    const preset = await admin.loadAgentWorkspacePreset();
    Object.assign(form, preset);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Agent 工作区预设读取失败");
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    const preset = await admin.saveAgentWorkspacePreset({ ...form });
    ElMessage.success(`预设已保存，版本 ${preset.version}。仅影响之后新建的 Agent；已有 Agent 需手动推送。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Agent 工作区预设保存失败");
  } finally {
    saving.value = false;
  }
}

async function push() {
  try {
    await ElMessageBox.confirm(
      "将使用当前最新预设，覆盖全部实例中所有已创建 Agent 的 6 个工作区文件（AGENTS.md / SOUL.md / IDENTITY.md / TOOLS.md / HEARTBEAT.md / USER.md）。用户对这些文件的自定义修改会被覆盖，且不可撤销。确定要推送到全部 Agent 吗？",
      "推送到全部 Agent",
      { type: "warning", confirmButtonText: "确认推送", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  pushing.value = true;
  try {
    const result = await admin.pushAgentWorkspacePreset();
    const summary = `已处理 ${result.instancesProcessed} 个实例、更新 ${result.agentsUpdated} 个 Agent、写入 ${result.filesWritten} 个文件`;
    if (result.failures.length > 0) {
      ElMessage.warning(`${summary}；失败 ${result.failures.length} 个，可稍后重试。`);
    } else {
      ElMessage.success(`${summary}。`);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "推送到全部 Agent 失败");
  } finally {
    pushing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="workspace agent-workspace-preset-page">
    <PageHeader title="Agent 工作区预设" description="配置新 Agent 初始化时使用的工作区文件；可手动将最新预设推送到全部已有 Agent。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
    </PageHeader>
    <el-card shadow="never" v-loading="loading">
      <el-alert type="info" :closable="false" show-icon title="保存的预设只影响之后新建的 Agent；要对已有 Agent 生效，请点击「推送到全部 Agent」（会覆盖其工作区文件）。" />
      <el-form label-position="top" class="preset-form">
        <el-form-item label="AGENTS.md"><el-input v-model="form.agentsMd" type="textarea" :rows="10" /></el-form-item>
        <el-form-item label="SOUL.md"><el-input v-model="form.soulMd" type="textarea" :rows="8" /></el-form-item>
        <el-form-item label="IDENTITY.md"><el-input v-model="form.identityMd" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="TOOLS.md"><el-input v-model="form.toolsMd" type="textarea" :rows="8" /></el-form-item>
        <el-form-item label="HEARTBEAT.md"><el-input v-model="form.heartbeatMd" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="USER.md"><el-input v-model="form.userMd" type="textarea" :rows="6" /></el-form-item>
        <div class="form-actions">
          <el-button :icon="Send" type="warning" :loading="pushing" @click="push">推送到全部 Agent</el-button>
          <el-button type="primary" :icon="Save" :loading="saving" @click="save">保存预设</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.preset-form { max-width: 900px; margin-top: 18px; }
.form-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
</style>
