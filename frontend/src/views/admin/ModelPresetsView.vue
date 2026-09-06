<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ArrowDown, ArrowUp, Plus, Save, Star, Trash2, X } from "lucide-vue-next";
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
  fallbackPresetIds: [] as string[],
  isDefault: false
});
const pendingFallbackId = ref("");

const selectedProvider = computed(() => admin.providers.find((provider) => provider.key === presetForm.providerKey));
const selectedProviderFields = computed(() => selectedProvider.value?.fields ?? []);
const configuredPresets = computed(() => admin.presets.filter((preset) => preset.isConfigured).length);
const defaultPresets = computed(() => admin.presets.filter((preset) => preset.isDefault).length);
const fallbackCandidates = computed(() =>
  admin.presets.filter(
    (preset) => preset.id !== presetForm.id && !presetForm.fallbackPresetIds.includes(preset.id)
  )
);

function presetName(presetId: string) {
  return admin.presets.find((preset) => preset.id === presetId)?.name || presetId;
}

function presetModelLabel(presetId: string) {
  const preset = admin.presets.find((item) => item.id === presetId);
  return preset ? `${preset.providerId}/${preset.modelId}` : "";
}

function fallbackOptionLabel(preset: PublicModelPreset) {
  const model = `${preset.providerId}/${preset.modelId}`;
  return preset.isConfigured ? `${preset.name}（${model}）` : `${preset.name}（${model}） · 需先配置`;
}

function presetChainLabel() {
  const chain = [presetForm.name || "当前预设", ...presetForm.fallbackPresetIds.map(presetName)];
  return chain.join(" → ");
}

function addFallback(presetId: string | number | boolean | undefined) {
  if (typeof presetId !== "string" || !presetId) {
    return;
  }
  const candidate = admin.presets.find((preset) => preset.id === presetId);
  if (!candidate || !candidate.isConfigured) {
    ElMessage.warning("只能引用已配置完成的预设作为 Fallback。");
    pendingFallbackId.value = "";
    return;
  }
  if (presetForm.fallbackPresetIds.includes(presetId)) {
    pendingFallbackId.value = "";
    return;
  }
  presetForm.fallbackPresetIds = [...presetForm.fallbackPresetIds, presetId];
  pendingFallbackId.value = "";
}

function moveFallback(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= presetForm.fallbackPresetIds.length) {
    return;
  }
  const next = [...presetForm.fallbackPresetIds];
  const [moved] = next.splice(index, 1);
  next.splice(target, 0, moved);
  presetForm.fallbackPresetIds = next;
}

function removeFallback(index: number) {
  presetForm.fallbackPresetIds = presetForm.fallbackPresetIds.filter((_, itemIndex) => itemIndex !== index);
}

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
    fallbackPresetIds: [],
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
    fallbackPresetIds: [...(preset.fallbackPresetIds ?? [])],
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
    await admin.loadInstances();
  } catch {
    // 实例列表加载失败时按当前已加载数量判断，接口保存失败会另行提示。
  }
  if (admin.instances.length === 0) {
    return false;
  }
  try {
    await ElMessageBox.confirm(
      `将用该预设的完整模型链「${presetChainLabel()}」覆盖全部 ${admin.instances.length} 个实例的当前模型配置；运行中的实例将自动重启，不可撤销。\n\n确认保存并同步？`,
      "保存并同步",
      { type: "warning", confirmButtonText: "保存并同步", cancelButtonText: "取消" }
    );
    return true;
  } catch {
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
    fallbackPresetIds: presetForm.fallbackPresetIds,
    isDefault: presetForm.isDefault,
    syncReferencedInstances: shouldSync
  };
  await runAction("preset:save", async () => {
    if (presetMode.value === "edit") {
      const response = await admin.updatePreset(presetForm.id, payload);
      if (response.sync?.requested) {
        const restarted = response.sync.restartedInstanceIds.length;
        ElMessage.success(
          `预设已保存，已同步 ${response.sync.updatedInstanceIds.length} 个实例${restarted ? `，其中 ${restarted} 个运行中实例已自动重启` : ""}。`
        );
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

async function deletePreset(preset: PublicModelPreset) {
  try {
    await ElMessageBox.confirm(
      `确定删除模型预设「${preset.name}」吗？该操作不可恢复。`,
      "删除模型预设",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  await runAction(`preset:delete:${preset.id}`, async () => {
    const result = await admin.deletePreset(preset.id);
    if (result.removedFromFallbackPresets?.length) {
      ElMessage.info(`该预设曾作为 Fallback 被引用，已自动从「${result.removedFromFallbackPresets.join("、")}」的 Fallback 列表中移除。`);
    }
    ElMessage.success("预设已删除");
  });
}

function isPositiveInteger(value: unknown) {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}
</script>

<template>
  <section class="workspace presets-page">
    <PageHeader title="LLM 模型预设" description="维护实例创建和运行时使用的模型供应商配置。">
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
        <el-table-column label="Fallback" min-width="170">
          <template #default="{ row }">
            <span v-if="row.fallbackPresetIds?.length" class="fallback-cell">
              {{ row.fallbackPresetIds.map(presetName).join("、") }}
            </span>
            <span v-else class="muted">—</span>
          </template>
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
                @click="deletePreset(row)"
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
        <el-divider content-position="left">Fallback 模型</el-divider>
        <el-form-item label="Fallback 列表（顺序即回退优先级，越靠前越优先）">
          <div class="fallback-panel">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="Fallback 仅在认证或运行故障、且用户未显式选择模型时按顺序自动切换；上下文溢出不会回退。仅已配置完成的预设可作为 Fallback。"
            />
            <div v-if="presetForm.fallbackPresetIds.length" class="fallback-list">
              <div v-for="(fallbackId, index) in presetForm.fallbackPresetIds" :key="fallbackId" class="fallback-row">
                <el-tag type="warning" effect="plain" class="fallback-order">{{ index + 1 }}</el-tag>
                <div class="fallback-info">
                  <div class="fallback-name">{{ presetName(fallbackId) }}</div>
                  <div class="fallback-model">{{ presetModelLabel(fallbackId) }}</div>
                </div>
                <div class="fallback-actions">
                  <el-button size="small" :icon="ArrowUp" :disabled="index === 0" @click="moveFallback(index, -1)" />
                  <el-button size="small" :icon="ArrowDown" :disabled="index === presetForm.fallbackPresetIds.length - 1" @click="moveFallback(index, 1)" />
                  <el-button size="small" :icon="X" @click="removeFallback(index)" />
                </div>
              </div>
            </div>
            <div v-else class="fallback-empty">未设置 Fallback：主模型故障时不会自动回退。</div>
            <el-select
              v-model="pendingFallbackId"
              filterable
              placeholder="添加其它预设作为 Fallback"
              class="fallback-add"
              @change="addFallback"
            >
              <el-option
                v-for="candidate in fallbackCandidates"
                :key="candidate.id"
                :value="candidate.id"
                :label="fallbackOptionLabel(candidate)"
                :disabled="!candidate.isConfigured"
              />
            </el-select>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="presetDialogOpen = false">取消</el-button>
        <el-button type="primary" :icon="Save" :loading="actionLoading === 'preset:save'" @click="savePreset">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.fallback-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.fallback-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.fallback-row {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 6px 8px;
}

.fallback-order {
  flex: 0 0 auto;
}

.fallback-info {
  flex: 1;
  min-width: 0;
}

.fallback-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fallback-model {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fallback-empty {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
  padding: 2px 0;
}

.fallback-add {
  width: 100%;
}

.fallback-cell {
  color: var(--el-text-color-regular, #606266);
  font-size: 13px;
}

.muted {
  color: var(--el-text-color-placeholder, #a8abb2);
}

.token-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.token-grid :deep(.el-input-number) {
  width: 100%;
}
</style>
