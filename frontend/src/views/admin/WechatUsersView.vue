<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { RefreshCw, RotateCcw, Search, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicWechatUser } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const tableLoading = ref(false);
const actionLoading = ref("");
const error = ref("");
const selectedUsers = ref<PublicWechatUser[]>([]);
const remarkDrafts = ref<Record<string, string>>({});
const phoneDrafts = ref<Record<string, string>>({});
const filters = reactive({
  keyword: "",
  instanceId: "",
  channelStatus: "",
  recordState: ""
});

const channelStatusOptions = [
  { label: "全部状态", value: "" },
  { label: "已就绪", value: "ready" },
  { label: "启动中", value: "starting" },
  { label: "异常", value: "error" },
  { label: "未知", value: "unknown" }
];

const recordStateOptions = [
  { label: "全部清理状态", value: "" },
  { label: "正常", value: "active" },
  { label: "清理中", value: "cleaning" },
  { label: "清理失败", value: "cleanup_failed" }
];

const instanceOptions = computed(() => {
  return admin.instances.map((instance) => ({ label: instance.name, value: instance.id }));
});

const users = computed(() => admin.wechatUsers);
const filteredUsers = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return users.value.filter((user) => {
    if (filters.instanceId && user.instanceId !== filters.instanceId) {
      return false;
    }
    if (filters.channelStatus && user.channelStatus !== filters.channelStatus) {
      return false;
    }
    if (filters.recordState && user.recordState !== filters.recordState) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    return [
      user.phone,
      user.accountId,
      user.wechatUserId,
      user.agentId,
      user.openVikingUserId,
      user.remark,
      user.instanceName,
      user.miniappOpenid,
      user.miniappBindStatus,
      user.miniappKeyPreview,
      user.cleanupOperationId,
      user.cleanupStage,
      ...(user.residueTypes || [])
    ].some((value) => String(value || "").toLowerCase().includes(keyword));
  });
});

const selectedRestartableUsers = computed(() => selectedUsers.value.filter(canRestartWechatChannel));
const activeUsers = computed(() => users.value.filter((user) => user.recordState === "active").length);
const cleaningUsers = computed(() => users.value.filter((user) => user.recordState === "cleaning").length);
const failedUsers = computed(() => users.value.filter((user) => user.recordState === "cleanup_failed").length);

onMounted(() => {
  void loadUsers();
});

async function loadUsers() {
  tableLoading.value = true;
  error.value = "";
  try {
    await Promise.all([admin.loadInstances(), admin.loadWechatUsers()]);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "微信用户读取失败";
    ElMessage.error(error.value);
  } finally {
    tableLoading.value = false;
  }
}

function userKey(user: PublicWechatUser) {
  return user.cleanupOperationId || `${user.instanceId}:${user.accountId || user.agentId || user.wechatUserId}`;
}

function resetFilters() {
  filters.keyword = "";
  filters.instanceId = "";
  filters.channelStatus = "";
  filters.recordState = "";
}

function handleSelectionChange(selection: PublicWechatUser[]) {
  selectedUsers.value = selection;
}

function isActive(user: PublicWechatUser) {
  return user.recordState === "active";
}

function canEdit(user: PublicWechatUser) {
  return isActive(user) && Boolean(user.accountId);
}

function canRestartWechatChannel(user: PublicWechatUser) {
  return canEdit(user) && user.instanceStatus === "running";
}

function canUnbind(user: PublicWechatUser) {
  return canEdit(user);
}

function channelStatusLabel(status: string) {
  switch (status) {
    case "ready": return "已就绪";
    case "starting": return "启动中";
    case "error": return "异常";
    case "unknown": return "未知";
    default: return status || "未知";
  }
}

function channelStatusType(status: string): "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "ready": return "success";
    case "starting": return "warning";
    case "error": return "danger";
    default: return "info";
  }
}

function recordStateLabel(state: string) {
  switch (state) {
    case "active": return "正常";
    case "cleaning": return "清理中";
    case "cleanup_failed": return "清理失败";
    default: return state || "未知";
  }
}

function recordStateType(state: string): "success" | "warning" | "danger" | "info" {
  switch (state) {
    case "active": return "success";
    case "cleaning": return "warning";
    case "cleanup_failed": return "danger";
    default: return "info";
  }
}

function cleanupStageLabel(stage: string) {
  const labels: Record<string, string> = {
    validated: "身份已校验",
    channels_stopped: "消息入口已停止",
    routing_deleted: "路由已删除",
    local_agent_data_deleted: "Agent 本地数据已删除",
    wechat_files_deleted: "微信凭证已删除",
    database_identity_deleted: "数据库身份已删除",
    history_redacted: "历史记录已脱敏",
    gateway_restarted: "Gateway 已恢复",
    completed: "清理完成"
  };
  return labels[stage] || stage || "等待开始";
}

function draftRemark(user: PublicWechatUser) {
  return remarkDrafts.value[userKey(user)] ?? user.remark;
}

function draftPhone(user: PublicWechatUser) {
  return phoneDrafts.value[userKey(user)] ?? user.phone;
}

function setDraftRemark(user: PublicWechatUser, value: string) {
  remarkDrafts.value = { ...remarkDrafts.value, [userKey(user)]: value };
}

function setDraftPhone(user: PublicWechatUser, value: string) {
  phoneDrafts.value = { ...phoneDrafts.value, [userKey(user)]: value };
}

function miniappStatusLabel(status: string) {
  switch (status) {
    case "connected": return "已绑定";
    case "waiting_scan": return "待扫码";
    case "scanned": return "已扫码";
    case "initializing": return "初始化中";
    case "rejected": return "已拒绝";
    default: return status || "未接入";
  }
}

function miniappStatusType(status: string): "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "connected": return "success";
    case "waiting_scan":
    case "scanned":
    case "initializing": return "warning";
    case "rejected": return "danger";
    default: return "info";
  }
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

async function restartWechatChannel(user: PublicWechatUser) {
  if (!canRestartWechatChannel(user)) return;
  await runAction(`wechat-channel:${userKey(user)}`, async () => {
    const result = await admin.restartWechatAccountChannel(user.instanceId, user.accountId);
    ElMessage.success(result.message || "微信通道已重启。");
  });
}

async function batchRestartWechatChannels() {
  if (selectedRestartableUsers.value.length === 0) {
    ElMessage.warning("请先选择运行中实例下的正常微信用户。");
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将重启 ${selectedRestartableUsers.value.length} 个微信用户的通道，不会重启 Gateway 或容器。确认继续？`,
      "批量重启微信通道",
      { type: "warning", confirmButtonText: "确认", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  await runAction("batch:wechat-channel", async () => {
    const results = await admin.batchRestartWechatAccountChannels(
      selectedRestartableUsers.value.map((user) => ({ instanceId: user.instanceId, accountId: user.accountId }))
    );
    const failed = results.filter((item) => item.status === "failed");
    if (failed.length > 0) {
      ElMessage.warning(`已提交 ${results.length - failed.length} 个，${failed.length} 个失败。`);
    } else {
      ElMessage.success("微信通道批量重启已完成。");
    }
  });
}

async function saveProfile(user: PublicWechatUser) {
  if (!canEdit(user)) return;
  await runAction(`profile:${userKey(user)}`, async () => {
    await admin.saveWechatProfile(user.instanceId, user.accountId, {
      phone: draftPhone(user),
      remark: draftRemark(user)
    });
    const remarks = { ...remarkDrafts.value };
    delete remarks[userKey(user)];
    remarkDrafts.value = remarks;
    const phones = { ...phoneDrafts.value };
    delete phones[userKey(user)];
    phoneDrafts.value = phones;
    ElMessage.success("用户资料已保存");
  });
}

async function deleteWechatAccount(user: PublicWechatUser) {
  if (!canUnbind(user)) return;
  try {
    await ElMessageBox.confirm(
      [
        `将彻底解绑手机号 ${user.phone || "-"} 对应的当前系统用户，并删除：`,
        "• 微信账号状态和凭证",
        "• Agent 配置、会话、trajectory 和 workspace",
        "• 小程序绑定和 Key",
        "• 本地 OpenViking Key 和运行状态",
        "• 当前系统数据库身份数据",
        "",
        "OpenViking 服务端记忆不会删除；用户以后重新绑定时可以继续使用原远端记忆。"
      ].join("\n"),
      "彻底解绑微信用户",
      { type: "warning", confirmButtonText: "确认彻底解绑", cancelButtonText: "取消", dangerouslyUseHTMLString: false }
    );
  } catch {
    return;
  }
  await runAction(`delete-account:${userKey(user)}`, async () => {
    const response = await admin.deleteWechatAccount(user.instanceId, user.accountId);
    ElMessage.success(`已提交用户全量清理任务（阶段：${cleanupStageLabel(response.operation.stage)}）。`);
  });
}

async function retryCleanup(user: PublicWechatUser) {
  if (!user.cleanupOperationId || !user.retryable) return;
  await runAction(`retry-cleanup:${user.cleanupOperationId}`, async () => {
    const operation = await admin.retryWechatUserCleanup(user.cleanupOperationId);
    ElMessage.success(`清理任务已重试（阶段：${cleanupStageLabel(operation.stage)}）。`);
  });
}
</script>

<template>
  <section class="workspace wechat-users-page">
    <PageHeader title="用户中心" description="统一查看有效用户、清理进度和可确认归属的历史残留。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="tableLoading" @click="loadUsers">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <section class="metric-grid">
      <MetricCard label="用户记录" :value="users.length" />
      <MetricCard label="正常用户" :value="activeUsers" tone="success" />
      <MetricCard label="清理中" :value="cleaningUsers" />
      <MetricCard label="清理失败" :value="failedUsers" tone="warning" />
    </section>

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>微信用户与清理任务</span>
          <el-button
            :icon="RefreshCw"
            :disabled="selectedRestartableUsers.length === 0"
            :loading="actionLoading === 'batch:wechat-channel'"
            @click="batchRestartWechatChannels"
          >
            批量重启微信通道
          </el-button>
        </div>
      </template>

      <el-form class="management-form history-filter-form" label-position="top" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" placeholder="手机号 / accountId / 微信 userId / Agent / OpenViking / 残留类型" clearable />
        </el-form-item>
        <el-form-item label="实例">
          <el-select v-model="filters.instanceId" clearable placeholder="全部实例">
            <el-option v-for="item in instanceOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="通道状态">
          <el-select v-model="filters.channelStatus">
            <el-option v-for="item in channelStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="清理状态">
          <el-select v-model="filters.recordState">
            <el-option v-for="item in recordStateOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <div class="inline-actions">
          <el-button :icon="Search" @click="loadUsers">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </el-form>

      <el-table
        v-loading="tableLoading"
        :data="filteredUsers"
        :row-key="userKey"
        empty-text="暂无微信用户或清理记录"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" :selectable="canRestartWechatChannel" />
        <el-table-column label="清理状态" min-width="240">
          <template #default="{ row }">
            <div class="model-stack">
              <span>
                <el-tag size="small" :type="recordStateType(row.recordState)">{{ recordStateLabel(row.recordState) }}</el-tag>
              </span>
              <span v-if="row.recordState !== 'active'">{{ cleanupStageLabel(row.cleanupStage) }}</span>
              <span v-if="row.cleanupError" class="danger-text">{{ row.cleanupError }}</span>
              <span v-if="row.residueTypes?.length" class="muted-text">残留：{{ row.residueTypes.join("、") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" min-width="210">
          <template #default="{ row }">
            <el-input
              v-if="canEdit(row)"
              :model-value="draftPhone(row)"
              placeholder="可为空"
              clearable
              @update:model-value="(value: string) => setDraftPhone(row, value)"
            />
            <span v-else>{{ row.phone || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定实例名称" min-width="170">
          <template #default="{ row }">
            <div class="model-stack">
              <strong>{{ row.instanceName || row.instanceId || "-" }}</strong>
              <span>{{ row.instanceStatus || "unknown" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="微信插件账号ID" min-width="230">
          <template #default="{ row }">{{ row.accountId || "-" }}</template>
        </el-table-column>
        <el-table-column label="微信 userId" min-width="180">
          <template #default="{ row }">{{ row.wechatUserId || "-" }}</template>
        </el-table-column>
        <el-table-column label="Agent / OpenViking" min-width="260">
          <template #default="{ row }">
            <div class="model-stack">
              <strong>{{ row.agentId || "-" }}</strong>
              <span>{{ row.openVikingUserId || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="小程序/API" min-width="260">
          <template #default="{ row }">
            <div class="model-stack">
              <strong>{{ row.miniappOpenid || "-" }}</strong>
              <span>
                <el-tag size="small" :type="miniappStatusType(row.miniappBindStatus)">{{ miniappStatusLabel(row.miniappBindStatus) }}</el-tag>
                <el-tag v-if="row.miniappKeyPreview" size="small" :type="row.miniappKeyEnabled ? 'success' : 'info'" effect="plain">
                  {{ row.miniappKeyEnabled ? "Key启用" : "Key停用" }}
                </el-tag>
              </span>
              <span>{{ row.miniappKeyPreview || "未生成 Key" }}</span>
              <span v-if="row.miniappLastUsedAt">最近 API：{{ formatDateTime(row.miniappLastUsedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="通道状态" min-width="190">
          <template #default="{ row }">
            <div class="channel-status-cell">
              <el-tag size="small" :type="channelStatusType(row.channelStatus)">{{ channelStatusLabel(row.channelStatus) }}</el-tag>
              <span v-if="row.channelMessage" class="muted-text">{{ row.channelMessage }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="240">
          <template #default="{ row }">
            <div v-if="canEdit(row)" class="remark-row">
              <el-input :model-value="draftRemark(row)" @update:model-value="(value: string) => setDraftRemark(row, value)" />
              <el-button :loading="actionLoading === `profile:${userKey(row)}`" @click="saveProfile(row)">保存</el-button>
            </div>
            <span v-else>{{ row.remark || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.boundAt) }}</template>
        </el-table-column>
        <el-table-column label="最近启动" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastStartedAt) }}</template>
        </el-table-column>
        <el-table-column label="最近错误" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastErrorAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" align="right" fixed="right">
          <template #default="{ row }">
            <div class="instance-actions">
              <el-tooltip v-if="row.recordState === 'cleanup_failed'" content="重试清理">
                <span>
                  <el-button
                    circle
                    :icon="RotateCcw"
                    :disabled="!row.retryable || !row.cleanupOperationId"
                    :loading="actionLoading === `retry-cleanup:${row.cleanupOperationId}`"
                    @click="retryCleanup(row)"
                  />
                </span>
              </el-tooltip>
              <el-tooltip v-else content="重启微信通道">
                <span>
                  <el-button
                    circle
                    :icon="RefreshCw"
                    :disabled="!canRestartWechatChannel(row)"
                    :loading="actionLoading === `wechat-channel:${userKey(row)}`"
                    @click="restartWechatChannel(row)"
                  />
                </span>
              </el-tooltip>
              <el-tooltip content="彻底解绑微信用户">
                <span>
                  <el-button
                    circle
                    :icon="Trash2"
                    :disabled="!canUnbind(row)"
                    :loading="actionLoading === `delete-account:${userKey(row)}`"
                    @click="deleteWechatAccount(row)"
                  />
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>
