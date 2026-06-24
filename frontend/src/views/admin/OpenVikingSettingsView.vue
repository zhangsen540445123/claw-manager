<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { RefreshCw, Save } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const form = reactive({
  baseUrl: "",
  trustedModeEnabled: false,
  accountId: "claw-manager",
  pluginPackage: "npm:@claw-manager/openviking-openclaw-plugin@2026.6.28",
  rootApiKey: "",
  clearRootApiKey: false
});

onMounted(async () => {
  await loadSettings();
});

async function loadSettings() {
  loading.value = true;
  error.value = "";
  try {
    const settings = await admin.loadOpenVikingSettings();
    form.baseUrl = settings.baseUrl || "";
    form.trustedModeEnabled = settings.trustedModeEnabled;
    form.accountId = settings.accountId || "claw-manager";
    form.pluginPackage = settings.pluginPackage || "npm:@claw-manager/openviking-openclaw-plugin@2026.6.28";
    form.rootApiKey = "";
    form.clearRootApiKey = false;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "OpenViking 配置读取失败";
    ElMessage.error(error.value);
  } finally {
    loading.value = false;
  }
}

async function saveSettings() {
  saving.value = true;
  error.value = "";
  try {
    await admin.saveOpenVikingSettings({
      baseUrl: form.baseUrl,
      trustedModeEnabled: form.trustedModeEnabled,
      accountId: form.accountId,
      pluginPackage: form.pluginPackage,
      rootApiKey: form.rootApiKey,
      clearRootApiKey: form.clearRootApiKey
    });
    form.rootApiKey = "";
    form.clearRootApiKey = false;
    ElMessage.success("OpenViking 配置已保存。");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "OpenViking 配置保存失败";
    ElMessage.error(error.value);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="OpenViking 配置" description="配置所有 OpenClaw 实例共享的 OpenViking 服务端和插件安装预置。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadSettings">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never" v-loading="loading">
      <el-form label-position="top" class="settings-form">
        <el-form-item label="OpenViking Base URL">
          <el-input v-model="form.baseUrl" placeholder="http://openviking:1933" />
        </el-form-item>
        <el-form-item label="Trusted Mode">
          <el-switch v-model="form.trustedModeEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="Account ID">
          <el-input v-model="form.accountId" placeholder="claw-manager" />
        </el-form-item>
        <el-form-item label="OpenViking 插件包">
          <el-input v-model="form.pluginPackage" placeholder="npm:@claw-manager/openviking-openclaw-plugin@2026.6.28" />
        </el-form-item>
        <el-form-item label="Root API Key">
          <el-input
            v-model="form.rootApiKey"
            type="password"
            show-password
            :disabled="form.clearRootApiKey"
            placeholder="留空则保持当前 Root API Key"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.clearRootApiKey">清空已保存的 Root API Key</el-checkbox>
        </el-form-item>
        <div class="secret-row">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="Root API Key">
              <el-tag :type="admin.openVikingSettings?.rootApiKeyConfigured ? 'success' : 'danger'" effect="plain">
                {{ admin.openVikingSettings?.rootApiKeyConfigured ? "已配置" : "未配置" }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Root Key 指纹">
              {{ admin.openVikingSettings?.rootApiKeyFingerprint || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="Identity Secret">
              <el-tag :type="admin.openVikingSettings?.identitySecretConfigured ? 'success' : 'danger'" effect="plain">
                {{ admin.openVikingSettings?.identitySecretConfigured ? "已配置" : "不可用" }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Secret 来源">
              {{ admin.openVikingSettings?.identitySecretSource || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="Secret 指纹">
              {{ admin.openVikingSettings?.identitySecretFingerprint || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime(admin.openVikingSettings?.updatedAt) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="form-actions">
          <el-button type="primary" :icon="Save" :loading="saving" @click="saveSettings">保存</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.settings-form {
  max-width: 760px;
}

.secret-row {
  margin-top: 8px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
</style>
