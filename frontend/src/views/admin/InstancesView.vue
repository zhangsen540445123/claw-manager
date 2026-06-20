<script setup lang="ts">
import { computed, ref } from "vue";
import { Clipboard, ExternalLink, Play, RefreshCw, Square, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicInstance, PublicWechatPairedAccount } from "../../api/types";
import { copyText } from "../../utils/adminUi";
import { resolveControlUiUrl } from "../../utils/controlUi";

const admin = useAdminStore();
const actionLoading = ref("");
const error = ref("");
const selectedInstances = ref<PublicInstance[]>([]);
const selectedWechatAccountsByInstanceId = ref<Record<string, PublicWechatPairedAccount[]>>({});

const selectedGatewayInstanceIds = computed(() => selectedInstances.value.map((instance) => instance.id));
const selectedWechatAccountPayloads = computed(() => {
  return Object.entries(selectedWechatAccountsByInstanceId.value).flatMap(([instanceId, accounts]) =>
    accounts.map((account) => ({ instanceId, accountId: account.accountId }))
  );
});

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

async function confirmThenRun(name: string, title: string, message: string, action: () => Promise<unknown>) {
  try {
    await ElMessageBox.confirm(message, title, { type: "warning", confirmButtonText: "确认", cancelButtonText: "取消" });
  } catch {
    return;
  }
  await runAction(name, action);
}

function canOpenControlUi(instance: PublicInstance) {
  return instance.status === "running" && instance.provisioning?.status === "ready" && Boolean(instance.dashboardUrl);
}

function canRestartGateway(instance: PublicInstance) {
  return instance.status === "running" && instance.provisioning?.status !== "running";
}

function canRestartWechatChannel(instance: PublicInstance) {
  return instance.status === "running" && (instance.wechatBinding?.pairedAccounts?.length || 0) > 0;
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

function openControlUi(instance: PublicInstance) {
  if (!canOpenControlUi(instance)) return;
  window.open(resolveControlUiUrl(instance.dashboardUrl), "_blank", "noopener,noreferrer");
}

function handleInstanceSelectionChange(selection: PublicInstance[]) {
  selectedInstances.value = selection;
}

function handleWechatAccountSelectionChange(instance: PublicInstance, selection: PublicWechatPairedAccount[]) {
  selectedWechatAccountsByInstanceId.value = {
    ...selectedWechatAccountsByInstanceId.value,
    [instance.id]: selection
  };
}

async function restartWechatChannel(instance: PublicInstance, accountId: string) {
  await runAction(`wechat-channel:${accountId}`, async () => {
    const result = await admin.restartWechatAccountChannel(instance.id, accountId);
    ElMessage.success(result.message || "微信通道已重启。");
  });
}

async function batchRestartWechatChannels() {
  if (selectedWechatAccountPayloads.value.length === 0) {
    ElMessage.warning("请先选择微信账号。");
    return;
  }
  await confirmThenRun(
    "batch:wechat-channel",
    "批量重启微信通道",
    `将重启 ${selectedWechatAccountPayloads.value.length} 个微信账号的通道，不会重启 Gateway 或容器。确认继续？`,
    async () => {
      const results = await admin.batchRestartWechatAccountChannels(selectedWechatAccountPayloads.value);
      const failed = results.filter((item) => item.status === "failed");
      if (failed.length > 0) {
        ElMessage.warning(`已提交 ${results.length - failed.length} 个，${failed.length} 个失败。`);
      } else {
        ElMessage.success("微信通道批量重启已完成。");
      }
    }
  );
}

async function batchRestartGateway() {
  if (selectedGatewayInstanceIds.value.length === 0) {
    ElMessage.warning("请先选择实例。");
    return;
  }
  await confirmThenRun(
    "batch:gateway",
    "批量重启 Gateway",
    `将重启 ${selectedGatewayInstanceIds.value.length} 个实例的 Gateway，期间 Control UI 和微信通道可能短暂不可用。确认继续？`,
    async () => {
      const results = await admin.batchRestartGateway(selectedGatewayInstanceIds.value);
      const failed = results.filter((item) => item.status === "failed");
      if (failed.length > 0) {
        ElMessage.warning(`已提交 ${results.length - failed.length} 个，${failed.length} 个失败。`);
      } else {
        ElMessage.success("Gateway 批量重启任务已提交。");
      }
    }
  );
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
      "删除后该微信账号将从当前 OpenClaw 实例解绑，运行中的 Gateway 会自动重启以使变更生效。",
      "删除微信账号",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  await runAction(`delete-account:${accountId}`, async () => {
    const response = await admin.deleteWechatAccount(instance.id, accountId);
    ElMessage.success(response.gatewayRestarted ? "已删除，Gateway 正在重启以使变更生效。" : "已删除微信账号。");
  });
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="实例管理" description="查看实例运行状态，打开 Control UI，维护微信账号与 Gateway。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="admin.loading" @click="admin.loadAll()">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>实例列表</span>
          <div class="button-row">
            <el-button
              :icon="RefreshCw"
              :disabled="selectedWechatAccountPayloads.length === 0"
              :loading="actionLoading === 'batch:wechat-channel'"
              @click="batchRestartWechatChannels"
            >
              批量重启微信通道
            </el-button>
            <el-button
              :icon="RefreshCw"
              :disabled="selectedGatewayInstanceIds.length === 0"
              :loading="actionLoading === 'batch:gateway'"
              @click="batchRestartGateway"
            >
              批量重启 Gateway
            </el-button>
          </div>
        </div>
      </template>
      <el-table :data="admin.instances" row-key="id" @selection-change="handleInstanceSelectionChange">
        <el-table-column type="selection" width="48" :selectable="canRestartGateway" />
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="instance-expand">
              <el-table
                :data="row.wechatBinding?.pairedAccounts || []"
                row-key="accountId"
                @selection-change="(selection: PublicWechatPairedAccount[]) => handleWechatAccountSelectionChange(row, selection)"
              >
                <el-table-column type="selection" width="48" :selectable="() => canRestartWechatChannel(row)" />
                <el-table-column prop="phone" label="手机号" min-width="130" />
                <el-table-column prop="accountId" label="OpenClaw账号" min-width="220" />
                <el-table-column prop="wechatUserId" label="微信 userId" min-width="160" />
                <el-table-column label="通道状态" min-width="180">
                  <template #default="{ row: account }">
                    <div class="channel-status-cell">
                      <el-tag size="small" :type="channelStatusType(account.channelStatus)">
                        {{ channelStatusLabel(account.channelStatus) }}
                      </el-tag>
                      <span v-if="account.channelMessage" class="muted-text">{{ account.channelMessage }}</span>
                    </div>
                  </template>
                </el-table-column>
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
                <el-table-column label="" width="140" align="right">
                  <template #default="{ row: account }">
                    <div class="instance-actions">
                      <el-tooltip content="重启微信通道">
                        <span>
                          <el-button
                            circle
                            :icon="RefreshCw"
                            :disabled="!canRestartWechatChannel(row)"
                            :loading="actionLoading === `wechat-channel:${account.accountId}`"
                            @click="restartWechatChannel(row, account.accountId)"
                          />
                        </span>
                      </el-tooltip>
                      <el-tooltip content="删除微信账号">
                        <span>
                          <el-button
                            circle
                            :icon="Trash2"
                            :loading="actionLoading === `delete-account:${account.accountId}`"
                            @click="deleteWechatAccount(row, account.accountId)"
                          />
                        </span>
                      </el-tooltip>
                    </div>
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
                    @click="confirmThenRun(`start:${row.id}`, '启动实例', '确认启动该 OpenClaw 容器？', () => admin.startInstance(row.id))"
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
                    @click="confirmThenRun(`stop:${row.id}`, '停止实例', '停止后 Control UI 和微信通道会暂时不可用，确认继续？', () => admin.stopInstance(row.id))"
                  />
                </span>
              </el-tooltip>
              <el-tooltip content="重启 Gateway">
                <span>
                  <el-button
                    circle
                    :icon="RefreshCw"
                    :disabled="!canRestartGateway(row)"
                    :loading="actionLoading === `gateway:${row.id}`"
                    @click="confirmThenRun(`gateway:${row.id}`, '重启 Gateway', '重启期间 Control UI 和微信通道可能短暂不可用，确认继续？', () => admin.restartGateway(row.id))"
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
