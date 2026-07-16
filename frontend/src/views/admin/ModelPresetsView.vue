<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Plus, Save, Star, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { ModelProviderField, PublicModelPreset } from "../../api/types";

const admin = useAdminStore();
const actionLoading = ref("");
const error = ref("");
const presetDialogOpen = ref(false);
const presetMode = ref<"create" | "edit">("create");
const presetForm = reactive({
  id: "",
  name: "",
  providerKey: "custom-provider",
  providerId: "openai",
  modelId: "gpt-5.5",
  apiMode: "openai-completions",
  baseUrl: "",
  apiKey: "",
  contextWindow: undefined as number | undefined,
  maxTokens: undefined as number | undefined,
  isDefault: false
});

const selectedProvider = computed(() => admin.providers.find((provider) => provider.key === presetForm.providerKey));
const selectedProviderFields = computed(() => selectedProvider.value?.fields ?? []);
const configuredPresets = computed(() => admin.presets.filter((preset) => preset.isConfigured).length);
const defaultPresets = computed(() => admin.presets.filter((preset) => preset.isDefault).length);

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

function providerName(key: string) {
  return admin.providers.find((provider) => provider.key === key)?.label || key;
}

function openCreatePreset() {
  presetMode.value = "create";
  Object.assign(presetForm, {
    id: "",
    name: "",
    providerKey: "custom-provider",
    providerId: "openai",
    modelId: "gpt-5.5",
    apiMode: "openai-completions",
    baseUrl: "",
    apiKey: "",
    contextWindow: undefined,
    maxTokens: undefined,
    isDefault: admin.presets.length === 0
  });
  applyProviderDefaults(false);
  presetDialogOpen.value = true;
}

function openEditPreset(preset: PublicModelPreset) {
  presetMode.value = "edit";
  Object.assign(presetForm, {
    id: preset.id,
    name: preset.name,
    providerKey: preset.providerKey || "custom-provider",
    providerId: preset.providerId,
    modelId: preset.modelId,
    apiMode: preset.apiMode,
    baseUrl: preset.baseUrl,
    apiKey: "",
    contextWindow: preset.contextWindow,
    maxTokens: preset.maxTokens,
    isDefault: preset.isDefault
  });
  presetDialogOpen.value = true;
}

function applyProviderDefaults(overwrite = true) {
  const provider = selectedProvider.value;
  if (!provider) return;
  if (overwrite || !presetForm.providerId) presetForm.providerId = provider.providerId || presetForm.providerId;
  if (overwrite || !presetForm.modelId) presetForm.modelId = provider.defaultModelId || presetForm.modelId;
  if (overwrite || !presetForm.apiMode) presetForm.apiMode = provider.apiMode || presetForm.apiMode;
  if (overwrite || !presetForm.baseUrl) presetForm.baseUrl = provider.defaultBaseUrl || presetForm.baseUrl;
}

function presetFieldValue(field: ModelProviderField) {
  return String((presetForm as unknown as Record<string, string | number | boolean | undefined>)[field.name] ?? "");
}

function setPresetFieldValue(field: ModelProviderField, value: string | number | boolean) {
  (presetForm as unknown as Record<string, string | number | boolean | undefined>)[field.name] = String(value);
}

function presetFieldPlaceholder(field: ModelProviderField) {
  if (presetMode.value === "edit" && field.name === "apiKey") {
    return "留空则保留原值";
  }
  return field.placeholder || "";
}

async function confirmPresetSyncIfNeeded() {
  if (presetMode.value !== "edit" || !presetForm.id) {
    return false;
  }
  try {
    const usage = await admin.getPresetUsage(presetForm.id);
    if (!usage.instances.length) {
      return false;
    }
    const instanceLines = usage.instances
      .map((instance) => `${instance.name}（${instance.status}，模型位置 ${instance.modelIndexes.map((index) => index + 1).join("、")}）`)
      .join("\n");
    await ElMessageBox.confirm(
      `该预设正在被以下 OpenClaw 实例引用，保存后会同步模型配置并重启运行中的实例：\n\n${instanceLines}`,
      "同步模型预设",
      { type: "warning", confirmButtonText: "保存并同步", cancelButtonText: "取消" }
    );
    return true;
  } catch (cause) {
    if (cause instanceof Error) {
      error.value = cause.message;
      ElMessage.error(error.value);
    }
    return null;
  }
}

async function savePreset() {
  if (!isPositiveInteger(presetForm.contextWindow) || !isPositiveInteger(presetForm.maxTokens)) {
    ElMessage.warning("请填写正整数 Context Window 和 Max Tokens。");
    return;
  }
  const shouldSync = await confirmPresetSyncIfNeeded();
  if (shouldSync === null) {
    return;
  }
  const payload = {
    name: presetForm.name,
    providerKey: presetForm.providerKey,
    providerId: presetForm.providerId,
    modelId: presetForm.modelId,
    apiMode: presetForm.apiMode,
    baseUrl: presetForm.baseUrl,
    apiKey: presetForm.apiKey,
    contextWindow: presetForm.contextWindow,
    maxTokens: presetForm.maxTokens,
    isDefault: presetForm.isDefault,
    syncReferencedInstances: shouldSync
  };
  await runAction("preset:save", async () => {
    if (presetMode.value === "edit") {
      const response = await admin.updatePreset(presetForm.id, payload);
      if (response.sync?.requested) {
        ElMessage.success(`预设已保存，已同步 ${response.sync.updatedInstanceIds.length} 个实例。`);
      } else {
        ElMessage.success("预设已保存");
      }
    } else {
      await admin.createPreset(payload);
      ElMessage.success("预设已创建");
    }
    presetDialogOpen.value = false;
  });
}

function isPositiveInteger(value: unknown) {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}
</script>

<template>
  <section class="workspace presets-page">
    <PageHeader title="模型预设" description="维护实例创建和运行时使用的模型供应商配置。">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreatePreset">新增预设</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <section class="metric-grid compact-metric-grid">
      <MetricCard label="模型预设" :value="admin.presets.length" />
      <MetricCard label="配置可用" :value="configuredPresets" tone="success" />
      <MetricCard label="默认预设" :value="defaultPresets" />
    </section>

    <el-card shadow="never">
      <el-table :data="admin.presets">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="Provider" min-width="160">
          <template #default="{ row }">{{ providerName(row.providerKey) }}</template>
        </el-table-column>
        <el-table-column label="模型" min-width="220">
          <template #default="{ row }">{{ row.providerId }}/{{ row.modelId }}</template>
        </el-table-column>
        <el-table-column prop="apiMode" label="API Mode" min-width="150" />
        <el-table-column label="Context Window" width="150" align="right">
          <template #default="{ row }">{{ row.contextWindow }}</template>
        </el-table-column>
        <el-table-column label="Max Tokens" width="130" align="right">
          <template #default="{ row }">{{ row.maxTokens }}</template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <div class="tag-row">
              <el-tag v-if="row.isDefault" type="success" effect="plain">默认</el-tag>
              <el-tag :type="row.isConfigured ? 'success' : 'warning'" effect="plain">
                {{ row.isConfigured ? "已配置" : "待配置" }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="" width="190" align="right">
          <template #default="{ row }">
            <el-tooltip content="设为默认">
              <span>
                <el-button
                  circle
                  :disabled="row.isDefault"
                  :icon="Star"
                  :loading="actionLoading === `preset:default:${row.id}`"
                  @click="runAction(`preset:default:${row.id}`, () => admin.setDefaultPreset(row.id))"
                />
              </span>
            </el-tooltip>
            <el-button @click="openEditPreset(row)">编辑</el-button>
            <el-tooltip content="删除预设">
              <el-button
                circle
                :icon="Trash2"
                :loading="actionLoading === `preset:delete:${row.id}`"
                @click="runAction(`preset:delete:${row.id}`, () => admin.deletePreset(row.id))"
              />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="presetDialogOpen" :title="presetMode === 'edit' ? '编辑模型预设' : '新增模型预设'" width="560px">
      <el-form class="preset-form" label-position="top" @submit.prevent="savePreset">
        <el-form-item label="名称">
          <el-input v-model="presetForm.name" />
        </el-form-item>
        <el-form-item label="Provider 变体">
          <el-select v-model="presetForm.providerKey" filterable @change="applyProviderDefaults(true)">
            <el-option v-for="provider in admin.providers" :key="provider.key" :label="provider.label" :value="provider.key" />
          </el-select>
        </el-form-item>
        <el-form-item v-for="field in selectedProviderFields" :key="field.name" :label="field.label">
          <el-select
            v-if="field.type === 'select'"
            :model-value="presetFieldValue(field)"
            filterable
            allow-create
            default-first-option
            :placeholder="presetFieldPlaceholder(field)"
            @update:model-value="setPresetFieldValue(field, $event)"
          >
            <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input
            v-else
            :model-value="field.type === 'password' ? presetForm.apiKey : presetFieldValue(field)"
            :type="field.type === 'password' ? 'password' : 'text'"
            :show-password="field.type === 'password'"
            :placeholder="presetFieldPlaceholder(field)"
            @update:model-value="setPresetFieldValue(field, $event)"
          />
        </el-form-item>
        <div class="token-grid">
          <el-form-item label="Context Window" required>
            <el-input-number v-model="presetForm.contextWindow" :min="1" :controls="false" placeholder="请输入上下文窗口" />
          </el-form-item>
          <el-form-item label="Max Tokens" required>
            <el-input-number v-model="presetForm.maxTokens" :min="1" :controls="false" placeholder="请输入最大输出 tokens" />
          </el-form-item>
        </div>
        <el-checkbox v-model="presetForm.isDefault">设为默认预设</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="presetDialogOpen = false">取消</el-button>
        <el-button type="primary" :icon="Save" :loading="actionLoading === 'preset:save'" @click="savePreset">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.token-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.token-grid :deep(.el-input-number) {
  width: 100%;
}
</style>
