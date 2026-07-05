<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { RefreshCw, Search, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicInstance, PublicWechatPairedAccount } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

interface WechatUserRow {
  key: string;
  instanceId: string;
  instanceName: string;
  instanceStatus: string;
  accountId: string;
  phone: string;
  wechatUserId: string;
  openVikingUserId: string;
  remark: string;
  baseUrl: string;
  boundAt?: string | null;
  updatedAt?: string | null;
  channelStatus: string;
  channelMessage: string;
  channelUpdatedAt?: string | null;
  lastStartedAt?: string | null;
  lastErrorAt?: string | null;
  miniappOpenid: string;
  miniappBindStatus: string;
  miniappKeyPreview: string;
  miniappKeyEnabled: boolean;
  miniappLastUsedAt?: string | null;
}

const admin = useAdminStore();
const tableLoading = ref(false);
const actionLoading = ref("");
const error = ref("");
const selectedUsers = ref<WechatUserRow[]>([]);
const remarkDrafts = ref<Record<string, string>>({});
const phoneDrafts = ref<Record<string, string>>({});
const filters = reactive({
  keyword: "",
  instanceId: "",
  channelStatus: ""
});

const channelStatusOptions = [
  { label: "全部状态", value: "" },
  { label: "已就绪", value: "ready" },
  { label: "启动中", value: "starting" },
  { label: "异常", value: "error" },
  { label: "未知", value: "unknown" }
];

const instanceOptions = computed(() => {
  return admin.instances.map((instance) => ({
    label: instance.name,
    value: instance.id
  }));
});

const users = computed(() => {
  return admin.instances.flatMap((instance) => {
    return (instance.wechatBinding?.pairedAccounts || []).map((account) => toUserRow(instance, account));
  });
});

const filteredUsers = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return users.value.filter((user) => {
    if (filters.instanceId && user.instanceId !== filters.instanceId) {
      return false;
    }
    if (filters.channelStatus && user.channelStatus !== filters.channelStatus) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    return [
      user.phone,
      user.accountId,
      user.wechatUserId,
      user.openVikingUserId,
      user.remark,
      user.instanceName,
      user.miniappOpenid,
      user.miniappBindStatus,
      user.miniappKeyPreview
    ].some((value) => value.toLowerCase().includes(keyword));
  });
});

const selectedRestartableUsers = computed(() => {
  return selectedUsers.value.filter(canRestartWechatChannel);
});

onMounted(() => {
  void loadUsers();
});

async function loadUsers() {
  tableLoading.value = true;
  error.value = "";
  try {
    await admin.loadInstances();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "微信用户读取失败";
    ElMessage.error(error.value);
  } finally {
    tableLoading.value = false;
  }
}

function toUserRow(instance: PublicInstance, account: PublicWechatPairedAccount): WechatUserRow {
  return {
    key: `${instance.id}:${account.accountId}`,
    instanceId: instance.id,
    instanceName: instance.name,
    instanceStatus: instance.status,
    accountId: account.accountId,
    phone: account.phone,
    wechatUserId: account.wechatUserId,
    openVikingUserId: account.openVikingUserId,
    remark: account.remark,
    baseUrl: account.baseUrl,
    boundAt: account.boundAt,
    updatedAt: account.updatedAt,
    channelStatus: account.channelStatus || "unknown",
    channelMessage: account.channelMessage,
    channelUpdatedAt: account.channelUpdatedAt,
    lastStartedAt: account.lastStartedAt,
    lastErrorAt: account.lastErrorAt,
    miniappOpenid: account.miniappOpenid || "",
    miniappBindStatus: account.miniappBindStatus || "",
    miniappKeyPreview: account.miniappKeyPreview || "",
    miniappKeyEnabled: Boolean(account.miniappKeyEnabled),
    miniappLastUsedAt: account.miniappLastUsedAt
  };
}

function resetFilters() {
  filters.keyword = "";
  filters.instanceId = "";
  filters.channelStatus = "";
}

function handleSelectionChange(selection: WechatUserRow[]) {
  selectedUsers.value = selection;
}

function canRestartWechatChannel(user: WechatUserRow) {
  return user.instanceStatus === "running";
}

function channelStatusLabel(status: string) {
  switch (status) {
    case "ready":
      return "已就绪";
    case "starting":
      return "启动中";
    case "error":
      return "异常";
    case "unknown":
      return "未知";
    default:
      return status || "未知";
  }
}

function channelStatusType(status: string): "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "ready":
      return "success";
    case "starting":
      return "warning";
    case "error":
      return "danger";
    default:
      return "info";
  }
}

function draftRemark(user: WechatUserRow) {
  return remarkDrafts.value[user.key] ?? user.remark;
}

function draftPhone(user: WechatUserRow) {
  return phoneDrafts.value[user.key] ?? user.phone;
}

function setDraftRemark(user: WechatUserRow, value: string) {
  remarkDrafts.value = {
    ...remarkDrafts.value,
    [user.key]: value
  };
}

function setDraftPhone(user: WechatUserRow, value: string) {
  phoneDrafts.value = {
    ...phoneDrafts.value,
    [user.key]: value
  };
}

function miniappStatusLabel(status: string) {
  switch (status) {
    case "connected":
      return "已绑定";
    case "waiting_scan":
      return "待扫码";
    case "scanned":
      return "已扫码";
    case "initializing":
      return "初始化中";
    case "rejected":
      return "已拒绝";
    default:
      return status || "未接入";
  }
}

function miniappStatusType(status: string): "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "connected":
      return "success";
    case "waiting_scan":
    case "scanned":
    case "initializing":
      return "warning";
    case "rejected":
      return "danger";
    default:
      return "info";
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

async function restartWechatChannel(user: WechatUserRow) {
  await runAction(`wechat-channel:${user.key}`, async () => {
    const result = await admin.restartWechatAccountChannel(user.instanceId, user.accountId);
    ElMessage.success(result.message || "微信通道已重启。");
  });
}

async function batchRestartWechatChannels() {
  if (selectedRestartableUsers.value.length === 0) {
    ElMessage.warning("请先选择运行中实例下的微信用户。");
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

async function saveProfile(user: WechatUserRow) {
  await runAction(`profile:${user.key}`, async () => {
    await admin.saveWechatProfile(user.instanceId, user.accountId, {
      phone: draftPhone(user),
      remark: draftRemark(user)
    });
    const next = { ...remarkDrafts.value };
    delete next[user.key];
    remarkDrafts.value = next;
    const nextPhones = { ...phoneDrafts.value };
    delete nextPhones[user.key];
    phoneDrafts.value = nextPhones;
    ElMessage.success("用户资料已保存");
  });
}

async function deleteWechatAccount(user: WechatUserRow) {
  try {
    await ElMessageBox.confirm(
      `将解绑手机号 ${user.phone || "-"} 对应的微信用户，并删除当前 OpenClaw 账号状态文件。运行中的 Gateway 会自动重启以使变更生效。`,
      "解绑微信用户",
      { type: "warning", confirmButtonText: "解绑", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  await runAction(`delete-account:${user.key}`, async () => {
    const response = await admin.deleteWechatAccount(user.instanceId, user.accountId);
    ElMessage.success(response.gatewayRestarted ? "已解绑，Gateway 正在重启以使变更生效。" : "已解绑微信用户。");
  });
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="用户中心" description="查看全系统微信用户、小程序绑定与 API Key 状态。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="tableLoading" @click="loadUsers">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>微信用户</span>
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
          <el-input v-model="filters.keyword" placeholder="手机号 / 微信插件账号ID / 微信 userId / OpenViking 用户ID / 小程序 openid / 备注" clearable />
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
        <el-form-item label=" ">
          <div class="button-row">
            <el-button :icon="Search" type="primary">筛选</el-button>
            <el-button @click="resetFilters">重置</el-button>
          </div>
        </el-form-item>
      </el-form>

      <el-table
        :data="filteredUsers"
        v-loading="tableLoading"
        row-key="key"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" :selectable="canRestartWechatChannel" />
        <el-table-column label="手机号" min-width="210">
          <template #default="{ row }">
            <el-input
              :model-value="draftPhone(row)"
              placeholder="可为空"
              clearable
              @update:model-value="(value: string) => setDraftPhone(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="绑定实例名称" min-width="170">
          <template #default="{ row }">
            <div class="model-stack">
              <strong>{{ row.instanceName }}</strong>
              <span>{{ row.instanceStatus }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="accountId" label="微信插件账号ID" min-width="230" />
        <el-table-column min-width="180">
          <template #header>
            <span>微信 userId</span>
            <el-tooltip content="微信插件登录完成后返回的真实微信用户标识。">
              <span class="help-dot">?</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.wechatUserId || "-" }}</template>
        </el-table-column>
        <el-table-column prop="openVikingUserId" label="OpenViking 用户ID" min-width="230" />
        <el-table-column label="小程序/API" min-width="260">
          <template #default="{ row }">
            <div class="model-stack">
              <strong>{{ row.miniappOpenid || "-" }}</strong>
              <span>
                <el-tag size="small" :type="miniappStatusType(row.miniappBindStatus)">
                  {{ miniappStatusLabel(row.miniappBindStatus) }}
                </el-tag>
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
              <el-tag size="small" :type="channelStatusType(row.channelStatus)">
                {{ channelStatusLabel(row.channelStatus) }}
              </el-tag>
              <span v-if="row.channelMessage" class="muted-text">{{ row.channelMessage }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="240">
          <template #default="{ row }">
            <div class="remark-row">
              <el-input :model-value="draftRemark(row)" @update:model-value="(value: string) => setDraftRemark(row, value)" />
              <el-button
                :loading="actionLoading === `profile:${row.key}`"
                @click="saveProfile(row)"
              >
                保存
              </el-button>
            </div>
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
        <el-table-column label="操作" width="150" align="right" fixed="right">
          <template #default="{ row }">
            <div class="instance-actions">
              <el-tooltip content="重启微信通道">
                <span>
                  <el-button
                    circle
                    :icon="RefreshCw"
                    :disabled="!canRestartWechatChannel(row)"
                    :loading="actionLoading === `wechat-channel:${row.key}`"
                    @click="restartWechatChannel(row)"
                  />
                </span>
              </el-tooltip>
              <el-tooltip content="解绑微信用户">
                <span>
                  <el-button
                    circle
                    :icon="Trash2"
                    :loading="actionLoading === `delete-account:${row.key}`"
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
