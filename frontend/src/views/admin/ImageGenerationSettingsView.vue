<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { RefreshCw, Save } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
const form = reactive({ enabled: false, providerId: "openai", modelId: "gpt-image-2", baseUrl: "", apiKey: "", providerConfig: "{}", timeoutMs: 180000 });

async function load() {
  loading.value = true;
  try {
    const value = await admin.loadImageGenerationSettings();
    Object.assign(form, { enabled: value.enabled, providerId: value.providerId || "openai", modelId: value.modelId || "gpt-image-2", baseUrl: value.baseUrl || "", apiKey: "", providerConfig: value.providerConfig || "{}", timeoutMs: value.timeoutMs || 180000 });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "图片生成配置读取失败"); }
  finally { loading.value = false; }
}

async function save() {
  saving.value = true;
  try {
    const payload: Record<string, unknown> = { ...form };
    if (!form.apiKey.trim()) delete payload.apiKey;
    const result = await admin.saveImageGenerationSettings(payload);
    form.apiKey = "";
    ElMessage.success(`图片生成配置已保存并同步到 ${result.syncedInstanceIds.length} 个实例。`);
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "图片生成配置保存失败"); }
  finally { saving.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="workspace">
    <PageHeader title="图片生成" description="配置由 Claw Manager 调用的 OpenAI 兼容图片生成服务。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
    </PageHeader>
    <el-card shadow="never" v-loading="loading">
      <el-form label-position="top" class="settings-form">
        <el-form-item label="启用图片生成"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="Provider ID"><el-input v-model="form.providerId" placeholder="openai" /></el-form-item>
        <el-form-item label="模型 ID"><el-input v-model="form.modelId" placeholder="gpt-image-2" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" placeholder="例如 https://image.example.com/v1" /></el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password :placeholder="admin.imageGenerationSettings?.apiKeyPreview ? `已配置 ${admin.imageGenerationSettings.apiKeyPreview}，留空保持` : '请输入图片模型 API Key'" />
        </el-form-item>
        <el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="10000" :max="600000" :step="10000" /></el-form-item>
        <el-form-item label="Provider 扩展配置 JSON"><el-input v-model="form.providerConfig" type="textarea" :rows="5" /></el-form-item>
        <div class="form-actions"><el-button type="primary" :icon="Save" :loading="saving" @click="save">保存并同步</el-button></div>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>.settings-form{max-width:760px}.form-actions{display:flex;justify-content:flex-end;margin-top:18px}</style>
