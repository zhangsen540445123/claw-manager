<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Download, RefreshCw, RotateCcw, Search, Trash2, Upload } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicInstance, PublicWechatPluginStatus } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const versionsLoading = ref(false);
const actionLoading = ref("");
const selectedInstances = ref<PublicInstance[]>([]);
const error = ref("");
const selectedVersion = ref("");

const runningStatuses = new Set(["installing", "uninstalling", "upgrading", "reinstalling"]);

const tableRows = computed(() => admin.instances);
const selectedIds = computed(() => selectedInstances.value.map((instance) => instance.id));
const versionOptions = computed(() => {
  const latest = admin.wechatPluginVersions.latest;
  const options = [
    { value: "", label: "默认版本" },
    { value: "latest", label: latest ? `最新版本 ${latest}` : "最新版本" }
  ];
  const seen = new Set(options.map((item) => item.value));
  for (const version of admin.wechatPluginVersions.versions || []) {
    if (!version || seen.has(version) || version === latest) continue;
    options.push({ value: version, label: version });
    seen.add(version);
  }
  return options;
});

onMounted(async () => {
  await loadPage();
});

async function loadPage() {
  loading.value = true;
  error.value = "";
  try {
    await admin.loadInstances();
    if (admin.instances.length > 0) {
      await admin.batchCheckWechatPlugins(admin.instances.map((instance) => instance.id));
    }
    void refreshVersions(false);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "微信插件状态读取失败";
    ElMessage.error(error.value);
  } finally {
    loading.value = false;
  }
}

async function refreshVersions(showMessage = true) {
  versionsLoading.value = true;
  try {
    const versions = await admin.loadWechatPluginVersions();
    if (showMessage) {
      ElMessage.success(versions.latest ? "微信插件官方版本已刷新。" : "官方版本暂不可用，请稍后重试。");
    }
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : "微信插件官方版本读取失败";
    if (showMessage) {
      ElMessage.error(message);
    }
  } finally {
    versionsLoading.value = false;
  }
}

function pluginStatus(instanceId: string) {
  return admin.wechatPluginStatusByInstanceId[instanceId] || null;
}

function isRunning(instance: PublicInstance) {
  return instance.status === "running";
}

function isTaskRunning(status: PublicWechatPluginStatus | null) {
  return Boolean(status && runningStatuses.has(status.status));
}

function statusTagType(status: PublicWechatPluginStatus | null) {
  if (!status) return "info";
  if (status.status === "failed") return "danger";
  if (isTaskRunning(status)) return "warning";
  if (status.installed) return "success";
  return "info";
}

function statusText(status: PublicWechatPluginStatus | null) {
  if (!status) return "未检测";
  if (status.status === "installing") return "安装中";
  if (status.status === "uninstalling") return "卸载中";
  if (status.status === "upgrading") return "升级中";
  if (status.status === "reinstalling") return "重装中";
  if (status.status === "failed") return "失败";
  if (status.status === "missing") return "未安装";
  if (status.installed) return "已安装";
  return status.status || "未知";
}

function canInstall(instance: PublicInstance) {
  const status = pluginStatus(instance.id);
  return isRunning(instance) && !status?.installed && !isTaskRunning(status);
}

function canUninstall(instance: PublicInstance) {
  const status = pluginStatus(instance.id);
  return isRunning(instance) && Boolean(status?.installed) && !isTaskRunning(status);
}

function canUpgrade(instance: PublicInstance) {
  const status = pluginStatus(instance.id);
  return isRunning(instance) && Boolean(status?.installed && status.upgradable) && !isTaskRunning(status);
}

function canReinstall(instance: PublicInstance) {
  const status = pluginStatus(instance.id);
  return isRunning(instance) && Boolean(status?.installed) && !isTaskRunning(status);
}

function canRunBatch(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall") {
  if (selectedInstances.value.length === 0 || actionLoading.value.startsWith("batch:")) {
    return false;
  }
  if (action === "check") return true;
  if (action === "install") return selectedInstances.value.some(canInstall);
  if (action === "uninstall") return selectedInstances.value.some(canUninstall);
  if (action === "upgrade") return selectedInstances.value.some(canUpgrade);
  return selectedInstances.value.some(canReinstall);
}

function actionVersion() {
  return selectedVersion.value;
}

async function runSingle(
  name: string,
  instance: PublicInstance,
  action: () => Promise<PublicWechatPluginStatus>,
  successMessage: string
) {
  actionLoading.value = `${name}:${instance.id}`;
  error.value = "";
  try {
    await action();
    ElMessage.success(successMessage);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "微信插件操作失败";
    ElMessage.error(error.value);
  } finally {
    actionLoading.value = "";
  }
}

async function checkOne(instance: PublicInstance) {
  await runSingle("check", instance, () => admin.loadWechatPluginStatus(instance.id, false), "微信插件状态已刷新。");
}

async function installOne(instance: PublicInstance) {
  await runSingle("install", instance, () => admin.installWechatPlugin(instance.id, actionVersion()), "微信插件安装任务已开始。");
}

async function uninstallOne(instance: PublicInstance) {
  try {
    await ElMessageBox.confirm("卸载只移除微信插件和启用配置，保留微信账号状态与绑定历史。", "卸载微信插件", {
      type: "warning",
      confirmButtonText: "确认卸载",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runSingle("uninstall", instance, () => admin.uninstallWechatPlugin(instance.id), "微信插件卸载任务已开始。");
}

async function upgradeOne(instance: PublicInstance) {
  await runSingle("upgrade", instance, () => admin.upgradeWechatPlugin(instance.id, actionVersion()), "微信插件升级任务已开始。");
}

async function reinstallOne(instance: PublicInstance) {
  try {
    await ElMessageBox.confirm("重新安装只覆盖微信插件包和启用配置，保留微信账号状态与绑定历史。", "重新安装微信插件", {
      type: "info",
      confirmButtonText: "确认重装",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runSingle("reinstall", instance, () => admin.reinstallWechatPlugin(instance.id, actionVersion()), "微信插件重新安装任务已开始。");
}

async function runBatch(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall") {
  if (selectedIds.value.length === 0) {
    ElMessage.warning("请先选择实例。");
    return;
  }
  if (action === "uninstall") {
    try {
      await ElMessageBox.confirm("批量卸载会保留微信账号状态与绑定历史，只移除插件和启用配置。", "批量卸载微信插件", {
        type: "warning",
        confirmButtonText: "确认卸载",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  if (action === "reinstall") {
    try {
      await ElMessageBox.confirm("批量重新安装只覆盖微信插件包和启用配置，保留微信账号状态与绑定历史。", "批量重新安装微信插件", {
        type: "info",
        confirmButtonText: "确认重装",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  actionLoading.value = `batch:${action}`;
  error.value = "";
  try {
    if (action === "check") await admin.batchCheckWechatPlugins(selectedIds.value);
    if (action === "install") await admin.batchInstallWechatPlugins(selectedIds.value, actionVersion());
    if (action === "uninstall") await admin.batchUninstallWechatPlugins(selectedIds.value);
    if (action === "upgrade") await admin.batchUpgradeWechatPlugins(selectedIds.value, actionVersion());
    if (action === "reinstall") await admin.batchReinstallWechatPlugins(selectedIds.value, actionVersion());
    ElMessage.success("批量任务已提交。");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "批量操作失败";
    ElMessage.error(error.value);
  } finally {
    actionLoading.value = "";
  }
}

function handleSelectionChange(selection: PublicInstance[]) {
  selectedInstances.value = selection;
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="微信插件管理" description="批量检测、安装、卸载和升级各 OpenClaw 实例内的微信渠道插件。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadPage">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>插件实例</span>
          <div class="button-row">
            <el-select v-model="selectedVersion" class="plugin-version-select" placeholder="版本">
              <el-option
                v-for="option in versionOptions"
                :key="option.value || 'default'"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-button :icon="RefreshCw" :loading="versionsLoading" @click="refreshVersions(true)">
              刷新版本
            </el-button>
            <el-button
              :icon="Search"
              :disabled="!canRunBatch('check')"
              :loading="actionLoading === 'batch:check'"
              @click="runBatch('check')"
            >
              检测
            </el-button>
            <el-button
              type="primary"
              :icon="Download"
              :disabled="!canRunBatch('install')"
              :loading="actionLoading === 'batch:install'"
              @click="runBatch('install')"
            >
              安装
            </el-button>
            <el-button
              :icon="Upload"
              :disabled="!canRunBatch('upgrade')"
              :loading="actionLoading === 'batch:upgrade'"
              @click="runBatch('upgrade')"
            >
              升级
            </el-button>
            <el-button
              :icon="RotateCcw"
              :disabled="!canRunBatch('reinstall')"
              :loading="actionLoading === 'batch:reinstall'"
              @click="runBatch('reinstall')"
            >
              重新安装
            </el-button>
            <el-button
              type="danger"
              plain
              :icon="Trash2"
              :disabled="!canRunBatch('uninstall')"
              :loading="actionLoading === 'batch:uninstall'"
              @click="runBatch('uninstall')"
            >
              卸载
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableRows" v-loading="loading" row-key="id" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="name" label="实例名称" min-width="160" />
        <el-table-column label="运行状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'running' ? 'success' : 'info'" effect="plain">
              {{ row.status === "running" ? "运行中" : row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="插件状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(pluginStatus(row.id))" effect="plain">
              {{ statusText(pluginStatus(row.id)) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="120">
          <template #default="{ row }">{{ pluginStatus(row.id)?.currentVersion || "-" }}</template>
        </el-table-column>
        <el-table-column label="最新版本" width="120">
          <template #default="{ row }">{{ pluginStatus(row.id)?.latestVersion || "-" }}</template>
        </el-table-column>
        <el-table-column label="可升级" width="90">
          <template #default="{ row }">
            <el-tag :type="pluginStatus(row.id)?.upgradable ? 'warning' : 'info'" effect="plain">
              {{ pluginStatus(row.id)?.upgradable ? "是" : "否" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近消息" min-width="220">
          <template #default="{ row }">{{ pluginStatus(row.id)?.message || "-" }}</template>
        </el-table-column>
        <el-table-column label="输出片段" min-width="240">
          <template #default="{ row }">
            <pre v-if="pluginStatus(row.id)?.outputSnippet" class="plugin-output-cell">{{ pluginStatus(row.id)?.outputSnippet }}</pre>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(pluginStatus(row.id)?.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link :loading="actionLoading === `check:${row.id}`" @click="checkOne(row)">检测</el-button>
            <el-button link type="primary" :disabled="!canInstall(row)" :loading="actionLoading === `install:${row.id}`" @click="installOne(row)">
              安装
            </el-button>
            <el-button link :disabled="!canUpgrade(row)" :loading="actionLoading === `upgrade:${row.id}`" @click="upgradeOne(row)">
              升级
            </el-button>
            <el-button link :disabled="!canReinstall(row)" :loading="actionLoading === `reinstall:${row.id}`" @click="reinstallOne(row)">
              重新安装
            </el-button>
            <el-button link type="danger" :disabled="!canUninstall(row)" :loading="actionLoading === `uninstall:${row.id}`" @click="uninstallOne(row)">
              卸载
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<style scoped>
.plugin-version-select {
  width: 220px;
}

.plugin-output-cell {
  max-height: 90px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}
</style>
