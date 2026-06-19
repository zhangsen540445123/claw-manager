<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Ban, Clipboard, Eye, Link as LinkIcon, QrCode, RefreshCw, Search } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicWechatBindLink, WechatBindingLookup } from "../../api/types";
import {
  bindStatusLabel,
  bindStatusTagType,
  canRevokeBindLink,
  copyText,
  formatDateTime,
  isLinkExpired
} from "../../utils/adminUi";

const admin = useAdminStore();
const error = ref("");
const actionLoading = ref("");
const tableLoading = ref(false);
const detailLoading = ref(false);
const generatedLink = ref<PublicWechatBindLink | null>(null);
const links = ref<PublicWechatBindLink[]>([]);
const total = ref(0);
const detailOpen = ref(false);
const selectedLink = ref<PublicWechatBindLink | null>(null);
const existingPhone = ref("");
const existingBindingOptions = ref<WechatBindingLookup[]>([]);
const existingBindingLoading = ref(false);
const filters = reactive({
  mode: "",
  status: "",
  phone: "",
  page: 1,
  pageSize: 20
});

const modeOptions = [
  { label: "全部类型", value: "" },
  { label: "新用户", value: "new" },
  { label: "老用户", value: "existing" }
];

const statusOptions = [
  { label: "全部状态", value: "" },
  { label: "待填写手机号", value: "phone_required" },
  { label: "已创建", value: "created" },
  { label: "出码中", value: "starting" },
  { label: "等待扫码", value: "waiting_scan" },
  { label: "已扫码", value: "scanned" },
  { label: "已连接", value: "connected" },
  { label: "已过期", value: "expired" },
  { label: "已拒绝", value: "rejected" },
  { label: "出码失败", value: "failed" },
  { label: "已失效", value: "revoked" }
];

const selectedLinkQrSource = computed(() => qrSource(selectedLink.value));

onMounted(() => {
  void loadLinks();
});

async function loadLinks() {
  tableLoading.value = true;
  error.value = "";
  try {
    const response = await admin.loadWechatLinks(filters);
    links.value = response.links;
    total.value = response.total;
    filters.page = response.page;
    filters.pageSize = response.pageSize;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "扫码链接历史读取失败";
    ElMessage.error(error.value);
  } finally {
    tableLoading.value = false;
  }
}

function resetFilters() {
  filters.mode = "";
  filters.status = "";
  filters.phone = "";
  filters.page = 1;
  void loadLinks();
}

function searchLinks() {
  filters.page = 1;
  void loadLinks();
}

function handlePageChange(page: number) {
  filters.page = page;
  void loadLinks();
}

function handlePageSizeChange(size: number) {
  filters.pageSize = size;
  filters.page = 1;
  void loadLinks();
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

async function createNewBindLink() {
  await runAction("bind:new", async () => {
    generatedLink.value = await admin.createBindLink("new");
    ElMessage.success("新用户扫码链接已生成。");
    await loadLinks();
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
    await loadLinks();
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

async function openDetail(token: string) {
  detailOpen.value = true;
  detailLoading.value = true;
  error.value = "";
  try {
    selectedLink.value = await admin.loadWechatLinkDetail(token);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "扫码链接详情读取失败";
    ElMessage.error(error.value);
  } finally {
    detailLoading.value = false;
  }
}

async function revokeLink(link: PublicWechatBindLink) {
  if (!canRevokeBindLink(link.status)) return;
  try {
    await ElMessageBox.confirm("失效后该扫码链接将不可继续用于出码或绑定。", "失效扫码链接", {
      type: "warning",
      confirmButtonText: "确认失效",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runAction(`revoke:${link.token}`, async () => {
    const revoked = await admin.revokeWechatLink(link.token);
    replaceLink(revoked);
    if (selectedLink.value?.token === revoked.token) {
      selectedLink.value = revoked;
    }
    if (generatedLink.value?.token === revoked.token) {
      generatedLink.value = revoked;
    }
    ElMessage.success("扫码链接已失效。");
  });
}

function replaceLink(link: PublicWechatBindLink) {
  const index = links.value.findIndex((item) => item.token === link.token);
  if (index >= 0) {
    links.value[index] = link;
  }
}

function modeLabel(link: PublicWechatBindLink) {
  return link.modeLabel || (link.mode === "existing" ? "老用户" : "新用户");
}

function showQr(link: PublicWechatBindLink | null) {
  return Boolean(link && link.status === "waiting_scan" && !link.qrExpired && (link.qrPayload || link.qrLink));
}

function qrSource(link: PublicWechatBindLink | null) {
  if (!link || link.qrExpired) return "";
  if (link.qrMode === "image" && link.qrPayload) return link.qrPayload;
  if (link.qrLink) {
    return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(link.qrLink)}`;
  }
  return "";
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="微信扫码链接" description="生成扫码入口，查看历史状态，并手动失效未完成的链接。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="tableLoading" @click="loadLinks">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never">
      <template #header>
        <div class="card-title">
          <LinkIcon :size="18" />
          <span>出码入口</span>
        </div>
      </template>
      <div class="bind-link-box">
        <div class="bind-actions">
          <section class="bind-action-panel">
            <strong>新用户出码</strong>
            <el-button type="primary" :loading="actionLoading === 'bind:new'" @click="createNewBindLink">
              为新用户出码
            </el-button>
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
            <el-tag :type="bindStatusTagType(generatedLink.status)" effect="plain">
              {{ bindStatusLabel(generatedLink.status, generatedLink.statusLabel) }}
            </el-tag>
            <el-input :model-value="generatedLink.bindLink" readonly />
            <el-button :icon="Clipboard" @click="copyText(generatedLink!.bindLink, '扫码链接')">复制</el-button>
          </div>
        </el-alert>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>出码历史</span>
          <el-button :icon="Search" @click="searchLinks">筛选</el-button>
        </div>
      </template>

      <el-form class="management-form history-filter-form" label-position="top" @submit.prevent="searchLinks">
        <el-form-item label="类型">
          <el-select v-model="filters.mode">
            <el-option v-for="item in modeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="filters.phone" clearable />
        </el-form-item>
        <el-form-item label=" ">
          <div class="button-row">
            <el-button native-type="submit" type="primary" :loading="tableLoading">查询</el-button>
            <el-button @click="resetFilters">重置</el-button>
          </div>
        </el-form-item>
      </el-form>

      <el-table :data="links" v-loading="tableLoading" row-key="token">
        <el-table-column label="类型" width="96">
          <template #default="{ row }">{{ modeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="bindStatusTagType(row.status)" effect="plain">
              {{ bindStatusLabel(row.status, row.statusLabel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" min-width="130" />
        <el-table-column prop="instanceName" label="实例" min-width="160" />
        <el-table-column label="扫码链接" min-width="260">
          <template #default="{ row }">
            <div class="link-cell">
              <span>{{ row.bindLink }}</span>
              <el-button link :icon="Clipboard" @click="copyText(row.bindLink, '扫码链接')" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="有效期" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.completedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="查看详情">
              <el-button circle :icon="Eye" @click="openDetail(row.token)" />
            </el-tooltip>
            <el-tooltip content="手动失效">
              <span>
                <el-button
                  circle
                  :icon="Ban"
                  :disabled="!canRevokeBindLink(row.status)"
                  :loading="actionLoading === `revoke:${row.token}`"
                  @click="revokeLink(row)"
                />
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :current-page="filters.page"
          :page-size="filters.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="handlePageChange"
          @size-change="handlePageSizeChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailOpen" title="扫码链接详情" size="min(560px, 100vw)">
      <el-skeleton v-if="detailLoading" :rows="6" animated />
      <template v-else-if="selectedLink">
        <div class="drawer-stack">
          <div class="detail-status-row">
            <el-tag :type="bindStatusTagType(selectedLink.status)" effect="plain">
              {{ bindStatusLabel(selectedLink.status, selectedLink.statusLabel) }}
            </el-tag>
            <span>{{ modeLabel(selectedLink) }}</span>
          </div>

          <el-descriptions :column="1" border>
            <el-descriptions-item label="Token">{{ selectedLink.token }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ selectedLink.phone || "-" }}</el-descriptions-item>
            <el-descriptions-item label="实例">{{ selectedLink.instanceName || selectedLink.instanceId || "-" }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(selectedLink.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="有效期">{{ formatDateTime(selectedLink.expiresAt) }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ formatDateTime(selectedLink.completedAt) }}</el-descriptions-item>
            <el-descriptions-item label="状态说明">{{ selectedLink.message || "-" }}</el-descriptions-item>
          </el-descriptions>

          <div class="token-created detail-link-row">
            <span>扫码链接</span>
            <el-input :model-value="selectedLink.bindLink" readonly />
            <el-button :icon="Clipboard" @click="copyText(selectedLink.bindLink, '扫码链接')">复制</el-button>
          </div>

          <section v-if="showQr(selectedLink)" class="qr-detail-panel">
            <div class="card-title">
              <QrCode :size="18" />
              <span>当前二维码</span>
            </div>
            <div class="qr-box">
              <img v-if="selectedLinkQrSource" :src="selectedLinkQrSource" alt="微信二维码" />
              <pre v-else>{{ selectedLink.qrPayload }}</pre>
            </div>
            <el-input v-if="selectedLink.qrLink" :model-value="selectedLink.qrLink" readonly />
          </section>

          <div class="button-row">
            <el-button
              type="danger"
              plain
              :icon="Ban"
              :disabled="!canRevokeBindLink(selectedLink.status) || isLinkExpired(selectedLink.expiresAt)"
              :loading="actionLoading === `revoke:${selectedLink.token}`"
              @click="revokeLink(selectedLink)"
            >
              手动失效
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </section>
</template>
