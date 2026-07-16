<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Copy, KeyRound, Plus, RefreshCw, RotateCw, Trash2 } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicMiniappClient } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const actionLoading = ref("");
const createDialogVisible = ref(false);
const createForm = reactive({ appId: "", enabled: true });
const enabledClients = computed(() => admin.miniappClients.filter((client) => client.enabled).length);
const disabledClients = computed(() => admin.miniappClients.filter((client) => !client.enabled).length);

onMounted(() => {
  void loadClients();
});

async function loadClients() {
  loading.value = true;
  try {
    await admin.loadMiniappClients();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "小程序接入信息读取失败");
  } finally {
    loading.value = false;
  }
}

async function runAction(name: string, action: () => Promise<unknown>) {
  actionLoading.value = name;
  try {
    await action();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "操作失败");
  } finally {
    actionLoading.value = "";
  }
}

async function createClient() {
  await runAction("create", async () => {
    const client = await admin.createMiniappClient({ appId: createForm.appId, enabled: createForm.enabled });
    createDialogVisible.value = false;
    createForm.appId = "";
    createForm.enabled = true;
    showSecretOnce("小程序 SK 已生成", client);
  });
}

async function toggleClient(client: PublicMiniappClient, enabled: boolean) {
  await runAction(`toggle:${client.appId}`, async () => {
    await admin.updateMiniappClient(client.appId, enabled);
    ElMessage.success(enabled ? "小程序 AK 已启用" : "小程序 AK 已停用");
  });
}

async function resetSecret(client: PublicMiniappClient) {
  try {
    await ElMessageBox.confirm("重置后旧 SK 会立即失效，请确认小程序后端会同步更新。", "重置小程序 SK", {
      type: "warning",
      confirmButtonText: "重置",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runAction(`reset:${client.appId}`, async () => {
    const next = await admin.resetMiniappClientSecret(client.appId);
    showSecretOnce("新的小程序 SK 已生成", next);
  });
}

async function deleteClient(client: PublicMiniappClient) {
  try {
    await ElMessageBox.confirm(`确认删除小程序 AK ${client.appId}？删除后该调用方将无法再访问小程序接入接口。`, "删除小程序 AK", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runAction(`delete:${client.appId}`, async () => {
    await admin.deleteMiniappClient(client.appId);
    ElMessage.success("小程序 AK 已删除");
  });
}

function showSecretOnce(title: string, client: PublicMiniappClient) {
  ElMessageBox.alert(
    `<div class="secret-once"><p>完整 SK 只在本次返回，请保存到小程序后端配置。</p><code>${escapeHtml(client.appSecret || "")}</code></div>`,
    title,
    {
      dangerouslyUseHTMLString: true,
      confirmButtonText: "我已保存"
    }
  );
}

async function copyText(value: string) {
  if (!value) return;
  await navigator.clipboard.writeText(value);
  ElMessage.success("已复制");
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
</script>

<template>
  <section class="workspace miniapp-clients-page">
    <PageHeader title="小程序接入" description="管理小程序后端调用 Claw Manager 的 AK/SK。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadClients">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="createDialogVisible = true">新增 AK</el-button>
      </template>
    </PageHeader>

    <section class="metric-grid compact-metric-grid">
      <MetricCard label="接入方总数" :value="admin.miniappClients.length" />
      <MetricCard label="已启用" :value="enabledClients" tone="success" />
      <MetricCard label="已停用" :value="disabledClients" tone="warning" />
    </section>

    <el-card shadow="never">
      <el-table :data="admin.miniappClients" v-loading="loading" row-key="appId">
        <el-table-column label="AK" min-width="220">
          <template #default="{ row }">
            <div class="copy-line">
              <strong>{{ row.appId }}</strong>
              <el-button text :icon="Copy" @click="copyText(row.appId)" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="SK preview" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.appSecretPreview || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="actionLoading === `toggle:${row.appId}`"
              @change="(value: boolean) => toggleClient(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right" fixed="right">
          <template #default="{ row }">
            <div class="instance-actions">
              <el-tooltip content="重置 SK">
                <span>
                  <el-button
                    circle
                    :icon="RotateCw"
                    :loading="actionLoading === `reset:${row.appId}`"
                    @click="resetSecret(row)"
                  />
                </span>
              </el-tooltip>
              <el-tooltip content="删除 AK">
                <span>
                  <el-button
                    circle
                    :icon="Trash2"
                    :loading="actionLoading === `delete:${row.appId}`"
                    @click="deleteClient(row)"
                  />
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="新增小程序 AK" width="460px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="AK" required>
          <el-input v-model="createForm.appId" :prefix-icon="KeyRound" placeholder="例如 miniapp_main" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="createForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading === 'create'" @click="createClient">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.copy-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
}
</style>
