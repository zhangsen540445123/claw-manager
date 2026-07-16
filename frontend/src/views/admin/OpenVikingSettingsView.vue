<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { RefreshCw, Save } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const form = reactive({
  baseUrl: "",
  trustedModeEnabled: true,
  accountId: "claw-manager",
  pluginPackage: "npm:@claw-manager/openviking-openclaw-plugin@2026.6.36",
  identitySalt: "",
  rootApiKey: "",
  clearRootApiKey: false
});

const statusRows = computed(() => [
  {
    name: "Root API Key",
    status: admin.openVikingSettings?.rootApiKeyConfigured ? "已配置" : "未配置",
    tagType: admin.openVikingSettings?.rootApiKeyConfigured ? "success" : "danger",
    value: admin.openVikingSettings?.rootApiKeyFingerprint || "-"
  },
  {
    name: "身份盐值",
    status: admin.openVikingSettings?.saltConfigured ? "已配置" : "不可用",
    tagType: admin.openVikingSettings?.saltConfigured ? "success" : "danger",
    value: admin.openVikingSettings?.saltFingerprint || "-"
  },
  {
    name: "盐值来源",
    status: admin.openVikingSettings?.saltSource || "-",
    tagType: "info",
    value: formatDateTime(admin.openVikingSettings?.updatedAt)
  }
]);

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
    form.pluginPackage = settings.pluginPackage || "npm:@claw-manager/openviking-openclaw-plugin@2026.6.36";
    form.identitySalt = "";
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
  if (form.identitySalt.trim() && admin.openVikingSettings?.saltConfigured) {
    try {
      await ElMessageBox.confirm(
        "修改身份盐值会让同一个微信用户映射到新的 OpenViking 用户ID。旧记忆不会删除，但将无法按原用户标识召回，除非恢复原盐值。确认继续？",
        "确认修改身份盐值",
        {
          type: "warning",
          confirmButtonText: "确认修改",
          cancelButtonText: "取消"
        }
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  error.value = "";
  try {
    await admin.saveOpenVikingSettings({
      baseUrl: form.baseUrl,
      trustedModeEnabled: form.trustedModeEnabled,
      accountId: form.accountId,
      pluginPackage: form.pluginPackage,
      identitySalt: form.identitySalt,
      rootApiKey: form.rootApiKey,
      clearRootApiKey: form.clearRootApiKey
    });
    form.identitySalt = "";
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
  <section class="workspace openviking-page">
    <PageHeader title="OpenViking预设" description="配置所有 OpenClaw 实例共享的 OpenViking 服务端和插件安装预置。">
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
          <el-input v-model="form.pluginPackage" placeholder="npm:@claw-manager/openviking-openclaw-plugin@2026.6.36" />
        </el-form-item>
        <el-form-item label="身份盐值">
          <el-input
            v-model="form.identitySalt"
            type="password"
            show-password
            placeholder="留空则保持当前身份盐值"
          />
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
        <el-table class="settings-status-table" :data="statusRows" row-key="name">
          <el-table-column prop="name" label="配置项" min-width="140" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.tagType" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="value" label="指纹 / 更新时间" min-width="220" />
        </el-table>
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

.settings-status-table {
  margin-top: 8px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
</style>
