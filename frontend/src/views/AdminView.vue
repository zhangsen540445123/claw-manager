<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { Clipboard, ExternalLink, Link, Play, Plus, RefreshCw, Save, Square, Star, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import { ApiError } from "../api/http";
import MetricCard from "../components/MetricCard.vue";
import PageHeader from "../components/PageHeader.vue";
import { useAdminStore } from "../stores/admin";
import type { ModelProviderField, PublicInstance, PublicModelPreset, PublicWechatBindLink, WechatBindingLookup } from "../api/types";

const admin = useAdminStore();
const actionLoading = ref("");
const error = ref("");
const presetDialogOpen = ref(false);
const presetMode = ref<"create" | "edit">("create");
const generatedLink = ref<PublicWechatBindLink | null>(null);
const newPhone = ref("");
const existingPhone = ref("");
const existingBindingOptions = ref<WechatBindingLookup[]>([]);
const existingBindingLoading = ref(false);
const createForm = reactive({ name: "OpenClaw 实例", presetId: "" });
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

const runningInstances = computed(() => admin.instances.filter((instance) => instance.status === "running").length);
const readyInstances = computed(() => admin.instances.filter((instance) => instance.provisioning?.status === "ready").length);
const boundAccounts = computed(() => admin.instances.reduce((sum, instance) => sum + (instance.wechatBinding?.pairedAccounts?.length || 0), 0));
const configuredPresets = computed(() => admin.presets.filter((preset) => preset.isConfigured).length);
const selectedProvider = computed(() => admin.providers.find((provider) => provider.key === presetForm.providerKey));
const selectedProviderFields = computed(() => selectedProvider.value?.fields ?? []);

onMounted(() => {
  void admin.loadAll();
});

watch(
  () => admin.defaultPreset?.id,
  (presetId) => {
    if (!createForm.presetId) {
      createForm.presetId = presetId || "";
    }
  },
  { immediate: true }
);

async function runAction(name: string, action: () => Promise<void>) {
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

async function createInstance() {
  if (!createForm.presetId) {
    error.value = "请先选择模型预设。";
    ElMessage.warning(error.value);
    return;
  }
  await runAction("instance:create", async () => {
    await admin.createInstance(createForm.name, createForm.presetId);
    createForm.name = "OpenClaw 实例";
    ElMessage.success("实例创建任务已提交，启动进度会在实例列表中更新。");
  });
}

async function confirmAction(title: string, message: string, action: () => Promise<void>) {
  await ElMessageBox.confirm(message, title, { type: "warning", confirmButtonText: "确认", cancelButtonText: "取消" });
  await action();
}

async function copyText(value: string, label = "链接") {
  if (!value) return;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value);
    } else {
      const input = document.createElement("textarea");
      input.value = value;
      input.setAttribute("readonly", "readonly");
      input.style.position = "fixed";
      input.style.left = "-9999px";
      document.body.appendChild(input);
      input.select();
      document.execCommand("copy");
      document.body.removeChild(input);
    }
    ElMessage.success(`${label}已复制`);
  } catch {
    error.value = `复制失败，请手动选中${label}复制。`;
    ElMessage.error(error.value);
  }
}

async function createNewBindLink() {
  await runAction("bind:new", async () => {
    generatedLink.value = await admin.createBindLink("new", newPhone.value);
    newPhone.value = "";
    ElMessage.success("新用户二维码已生成。");
  });
}

async function createExistingBindLink() {
  await runAction("bind:existing", async () => {
    const binding = await admin.findBindingByPhone(existingPhone.value);
    if (!binding) {
      throw new Error("该手机号尚未绑定微信账号。");
    }
    generatedLink.value = await admin.createBindLink("existing", existingPhone.value);
    ElMessage.success("老用户扫码链接已生成。");
  });
}

async function searchExistingBindings(query: string) {
  const keyword = query.trim();
  if (!keyword) {
    existingBindingOptions.value = [];
    return;
  }
  existingBindingLoading.value = true;
  try {
    existingBindingOptions.value = await admin.searchBindingsByPhone(keyword);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "手机号搜索失败";
    ElMessage.error(error.value);
  } finally {
    existingBindingLoading.value = false;
  }
}

function existingBindingLabel(binding: WechatBindingLookup) {
  const remark = binding.remark ? ` · ${binding.remark}` : "";
  return `${binding.phone}${remark}`;
}

async function saveRemark(instance: PublicInstance, accountId: string, remark: string) {
  await runAction(`remark:${accountId}`, async () => {
    await admin.saveWechatRemark(instance.id, accountId, remark);
    ElMessage.success("备注已保存");
  });
}

async function deleteWechatAccount(instance: PublicInstance, accountId: string) {
  try {
    await ElMessageBox.confirm(
      [
        "将删除该用户在当前系统中的微信凭证、Agent 配置、会话、trajectory、workspace、小程序绑定与 Key、本地 OpenViking Key 和数据库身份数据。",
        "",
        "OpenViking 服务端记忆不会删除；用户以后重新绑定时可以继续使用原远端记忆。"
      ].join("\n"),
      "彻底解绑微信用户",
      { type: "warning", confirmButtonText: "确认彻底解绑", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  await runAction(`delete-account:${accountId}`, async () => {
    await admin.deleteWechatAccount(instance.id, accountId);
    ElMessage.success("已提交用户全量清理任务，可在用户中心查看进度或重试失败任务。");
  });
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

function providerName(key: string) {
  return admin.providers.find((provider) => provider.key === key)?.label || key;
}

function isPositiveInteger(value: unknown) {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function canOpenControlUi(instance: PublicInstance) {
  return instance.status === "running" && instance.provisioning?.status === "ready" && Boolean(instance.dashboardUrl);
}

function openControlUi(instance: PublicInstance) {
  if (!canOpenControlUi(instance)) return;
  window.open(instance.dashboardUrl, "_blank", "noopener,noreferrer");
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="后台管理" description="集中管理 OpenClaw 实例、模型预设和微信扫码绑定。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="admin.loading" @click="admin.loadAll()">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <div class="module-content">
        <section id="admin-overview" class="module-section">
          <div class="module-heading">
            <h2>运行总览</h2>
          </div>
          <section class="metric-grid">
            <MetricCard label="运行实例" :value="runningInstances" tone="success" />
            <MetricCard label="就绪实例" :value="readyInstances" />
            <MetricCard label="微信账号" :value="boundAccounts" tone="warning" />
            <MetricCard label="可用预设" :value="configuredPresets" />
          </section>
        </section>

        <section id="admin-wechat" class="module-section">
          <el-card shadow="never">
            <template #header>
              <div class="card-title">
                <Link :size="18" />
                <span>微信扫码链接</span>
              </div>
            </template>
            <div class="bind-link-box">
              <div class="bind-actions">
                <section class="bind-action-panel">
                  <strong>新用户出码</strong>
                  <div class="existing-bind-row">
                    <el-input v-model="newPhone" inputmode="tel" placeholder="输入新用户手机号" clearable />
                    <el-button type="primary" :loading="actionLoading === 'bind:new'" @click="createNewBindLink">
                      为新用户出码
                    </el-button>
                  </div>
                </section>
                <section class="bind-action-panel">
                  <strong>老用户出码</strong>
                  <div class="existing-bind-row">
                    <el-select
                      v-model="existingPhone"
                      filterable
                      remote
                      reserve-keyword
                      clearable
                      placeholder="输入手机号片段搜索已绑定用户"
                      :remote-method="searchExistingBindings"
                      :loading="existingBindingLoading"
                      no-match-text="没有匹配手机号"
                    >
                      <el-option
                        v-for="binding in existingBindingOptions"
                        :key="binding.accountId"
                        :label="existingBindingLabel(binding)"
                        :value="binding.phone"
                      />
                    </el-select>
                    <el-button :loading="actionLoading === 'bind:existing'" @click="createExistingBindLink">
                      为老用户出码
                    </el-button>
                  </div>
                </section>
              </div>
              <el-alert v-if="generatedLink" type="success" show-icon :closable="false">
                <div class="token-created">
                  <span>扫码链接</span>
                  <el-input :model-value="generatedLink.bindLink" readonly />
                  <el-button :icon="Clipboard" @click="copyText(generatedLink!.bindLink, '链接')">复制</el-button>
                </div>
              </el-alert>
            </div>
          </el-card>
        </section>

        <section id="admin-presets" class="module-section">
          <el-card shadow="never">
            <template #header>
              <div class="card-title with-action">
                <span>模型预设</span>
                <el-button size="small" type="primary" :icon="Plus" @click="openCreatePreset">新增</el-button>
              </div>
            </template>
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
                    <el-button
                      circle
                      :disabled="row.isDefault"
                      :icon="Star"
                      :loading="actionLoading === `preset:default:${row.id}`"
                      @click="runAction(`preset:default:${row.id}`, () => admin.setDefaultPreset(row.id))"
                    />
                  </el-tooltip>
                  <el-button @click="openEditPreset(row)">编辑</el-button>
                  <el-tooltip content="删除预设">
                    <el-button
                      circle
                      :icon="Trash2"
                      :loading="actionLoading === `preset:delete:${row.id}`"
                      @click="runAction(`preset:delete:${row.id}`, async () => { await admin.deletePreset(row.id); })"
                    />
                  </el-tooltip>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </section>

        <section id="admin-create" class="module-section">
          <el-card shadow="never">
            <template #header>
              <div class="card-title with-action">
                <span>创建实例</span>
                <el-button type="primary" :icon="Plus" :loading="actionLoading === 'instance:create'" @click="createInstance">
                  创建
                </el-button>
              </div>
            </template>
            <el-form class="management-form compact-form" label-position="top" @submit.prevent="createInstance">
              <el-form-item label="实例名称">
                <el-input v-model="createForm.name" />
              </el-form-item>
              <el-form-item label="模型预设">
                <el-select v-model="createForm.presetId" filterable placeholder="请选择模型预设">
                  <el-option
                    v-for="preset in admin.configuredPresets"
                    :key="preset.id"
                    :label="preset.isDefault ? `${preset.name} · 默认` : preset.name"
                    :value="preset.id"
                  />
                </el-select>
              </el-form-item>
            </el-form>
          </el-card>
        </section>

        <section id="admin-instances" class="module-section">
          <el-card shadow="never">
            <template #header>
              <div class="card-title">
                <span>实例管理</span>
              </div>
            </template>
            <el-table :data="admin.instances" row-key="id">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="instance-expand">
                    <el-table :data="row.wechatBinding?.pairedAccounts || []">
                      <el-table-column prop="phone" label="手机号" min-width="130" />
                      <el-table-column prop="accountId" label="微信唯一标识" min-width="220" />
                      <el-table-column prop="wechatUserId" label="微信 userId" min-width="160" />
                      <el-table-column label="备注" min-width="220">
                        <template #default="{ row: account }">
                          <div class="remark-row">
                            <el-input v-model="account.remark" />
                            <el-button
                              :loading="actionLoading === `remark:${account.accountId}`"
                              @click="saveRemark(row, account.accountId, account.remark)"
                            >
                              保存
                            </el-button>
                          </div>
                        </template>
                      </el-table-column>
                      <el-table-column prop="boundAt" label="绑定时间" min-width="180" />
                      <el-table-column label="" width="90" align="right">
                        <template #default="{ row: account }">
                          <el-button
                            circle
                            :icon="Trash2"
                            :loading="actionLoading === `delete-account:${account.accountId}`"
                            @click="deleteWechatAccount(row, account.accountId)"
                          />
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="实例" min-width="160" />
              <el-table-column label="Control UI" min-width="260">
                <template #default="{ row }">
                  <div class="control-ui-cell">
                    <el-tooltip :content="canOpenControlUi(row) ? '打开 Control UI' : '实例就绪后可访问 Control UI'">
                      <span>
                        <el-button
                          type="primary"
                          plain
                          :icon="ExternalLink"
                          :disabled="!canOpenControlUi(row)"
                          @click="openControlUi(row)"
                        >
                          打开
                        </el-button>
                      </span>
                    </el-tooltip>
                    <el-button
                      :icon="Clipboard"
                      :disabled="!row.gatewayToken"
                      @click="copyText(row.gatewayToken, 'Control UI 访问令牌')"
                    >
                      复制令牌
                    </el-button>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100" />
              <el-table-column label="容器名称" min-width="220">
                <template #default="{ row }">
                  <el-tooltip :content="row.containerName" placement="top">
                    <span class="container-name-cell">{{ row.containerName }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="Gateway" min-width="180">
                <template #default="{ row }">
                  <el-progress :percentage="row.provisioning?.percent || 0" />
                </template>
              </el-table-column>
              <el-table-column prop="port" label="端口" width="90" />
              <el-table-column label="模型" min-width="220">
                <template #default="{ row }">
                  {{ row.model?.providerId || "-" }}/{{ row.model?.modelId || "-" }}
                </template>
              </el-table-column>
              <el-table-column label="微信账号" width="110">
                <template #default="{ row }">{{ row.wechatBinding?.pairedAccounts?.length || 0 }}</template>
              </el-table-column>
              <el-table-column label="操作" width="150" align="right">
                <template #default="{ row }">
                  <div class="instance-actions">
                    <el-tooltip content="启动实例">
                      <span>
                        <el-button
                          circle
                          :icon="Play"
                          :disabled="row.status === 'running' || row.provisioning?.status === 'running'"
                          :loading="actionLoading === `start:${row.id}`"
                          @click="runAction(`start:${row.id}`, () => confirmAction('启动实例', '确认启动该 OpenClaw 容器？', () => admin.startInstance(row.id)))"
                        />
                      </span>
                    </el-tooltip>
                    <el-tooltip content="停止实例">
                      <span>
                        <el-button
                          circle
                          :icon="Square"
                          :disabled="row.status !== 'running'"
                          :loading="actionLoading === `stop:${row.id}`"
                          @click="runAction(`stop:${row.id}`, () => confirmAction('停止实例', '停止后 Control UI 和微信通道会暂时不可用，确认继续？', () => admin.stopInstance(row.id)))"
                        />
                      </span>
                    </el-tooltip>
                    <el-tooltip content="重启 Gateway">
                      <span>
                        <el-button
                          circle
                          :icon="RefreshCw"
                          :disabled="row.status !== 'running' || row.provisioning?.status === 'running'"
                          :loading="actionLoading === `gateway:${row.id}`"
                          @click="runAction(`gateway:${row.id}`, () => confirmAction('重启 Gateway', '重启期间 Control UI 和微信通道可能短暂不可用，确认继续？', () => admin.restartGateway(row.id)))"
                        />
                      </span>
                    </el-tooltip>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </section>

        <section id="admin-ops" class="module-section">
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
    </div>

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
