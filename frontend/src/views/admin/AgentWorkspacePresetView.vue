<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { RefreshCw, Save } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
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
    ElMessage.success(`预设已保存，版本 ${preset.version}。仅新建 Agent 使用，不覆盖已有用户工作区。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Agent 工作区预设保存失败");
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="workspace agent-workspace-preset-page">
    <PageHeader title="Agent 工作区预设" description="配置新微信用户 Agent 初始化时使用的工作区文件，不覆盖已有用户的自定义修改。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
    </PageHeader>
    <el-card shadow="never" v-loading="loading">
      <el-alert type="info" :closable="false" show-icon title="预设只在首次创建用户 Agent 时复制，用户后续可以修改自己的工作区文件。" />
      <el-form label-position="top" class="preset-form">
        <el-form-item label="AGENTS.md"><el-input v-model="form.agentsMd" type="textarea" :rows="10" /></el-form-item>
        <el-form-item label="SOUL.md"><el-input v-model="form.soulMd" type="textarea" :rows="8" /></el-form-item>
        <el-form-item label="IDENTITY.md"><el-input v-model="form.identityMd" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="TOOLS.md"><el-input v-model="form.toolsMd" type="textarea" :rows="8" /></el-form-item>
        <el-form-item label="HEARTBEAT.md"><el-input v-model="form.heartbeatMd" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="USER.md"><el-input v-model="form.userMd" type="textarea" :rows="6" /></el-form-item>
        <div class="form-actions"><el-button type="primary" :icon="Save" :loading="saving" @click="save">保存预设</el-button></div>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.preset-form { max-width: 900px; margin-top: 18px; }
.form-actions { display: flex; justify-content: flex-end; margin-top: 18px; }
</style>
