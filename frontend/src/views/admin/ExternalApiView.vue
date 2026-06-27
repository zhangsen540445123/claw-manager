<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { KeyRound, RefreshCw, RotateCw, Save } from "lucide-vue-next";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicExternalApiUserRoute } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const saving = ref(false);
const routeLoading = ref(false);
const routes = ref<PublicExternalApiUserRoute[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const filters = reactive({ keyword: "", instanceId: "" });
const form = reactive({ enabled: false, apiKey: "" });

const runningInstances = computed(() => admin.instances.filter((instance) => instance.status === "running"));

onMounted(async () => {
  await loadPage();
});

async function loadPage() {
  loading.value = true;
  try {
    await Promise.all([admin.loadInstances(), admin.loadExternalApiSettings()]);
    form.enabled = Boolean(admin.externalApiSettings?.enabled);
    form.apiKey = admin.externalApiSettings?.apiKey || "";
    await loadRoutes();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "API接入信息读取失败");
  } finally {
    loading.value = false;
  }
}

async function saveSettings(regenerateApiKey = false) {
  if (regenerateApiKey) {
    try {
      await ElMessageBox.confirm("重新生成后，旧的全局 API Key 将立即失效。", "重新生成 API Key", {
        type: "warning",
        confirmButtonText: "重新生成",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    const settings = await admin.saveExternalApiSettings({
      enabled: form.enabled,
      apiKey: form.apiKey,
      regenerateApiKey
    });
    form.enabled = settings.enabled;
    form.apiKey = settings.apiKey;
    ElMessage.success("API接入预设已保存。");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  } finally {
    saving.value = false;
  }
}

async function loadRoutes() {
  routeLoading.value = true;
  try {
    const response = await admin.loadExternalApiUsers({
      keyword: filters.keyword,
      instanceId: filters.instanceId,
      page: page.value,
      pageSize: pageSize.value
    });
    routes.value = response.routes;
    total.value = response.total;
    page.value = response.page;
    pageSize.value = response.pageSize;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "API用户路由读取失败");
  } finally {
    routeLoading.value = false;
  }
}

async function migrate(route: PublicExternalApiUserRoute, instanceId: string) {
  if (!instanceId || instanceId === route.instanceId) return;
  try {
    await ElMessageBox.confirm(`确认将 openid ${route.openid} 的后续请求迁移到 ${instanceId}？`, "迁移 API 用户路由", {
      type: "warning",
      confirmButtonText: "确认迁移",
      cancelButtonText: "取消"
    });
    await admin.migrateExternalApiUser(route.openidHash, instanceId);
    ElMessage.success("路由已迁移。");
    await loadRoutes();
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message);
    }
  }
}
</script>

<template>
  <section class="workspace">
    <PageHeader title="API接入" description="配置外部 API 全局鉴权，并查看 openid 到 OpenClaw 实例的自动路由。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadPage">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>全局 API Key</span>
          <div class="button-row">
            <el-button :icon="RotateCw" :loading="saving" @click="saveSettings(true)">重新生成</el-button>
            <el-button type="primary" :icon="Save" :loading="saving" @click="saveSettings(false)">保存</el-button>
          </div>
        </div>
      </template>
      <el-form label-width="120px">
        <el-form-item label="启用接入">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" :prefix-icon="KeyRound" placeholder="点击重新生成或手动填写" />
        </el-form-item>
        <el-form-item label="当前状态">
          <el-tag :type="admin.externalApiSettings?.enabled ? 'success' : 'info'" effect="plain">
            {{ admin.externalApiSettings?.enabled ? "已启用" : "未启用" }}
          </el-tag>
          <span class="muted inline-help"> {{ admin.externalApiSettings?.apiKeyPreview || "未配置" }}</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>API 用户路由</span>
          <div class="button-row">
            <el-input v-model="filters.keyword" clearable placeholder="搜索 openid / OpenViking ID" />
            <el-select v-model="filters.instanceId" clearable placeholder="全部实例">
              <el-option v-for="instance in admin.instances" :key="instance.id" :label="instance.name" :value="instance.id" />
            </el-select>
            <el-button :icon="RefreshCw" :loading="routeLoading" @click="loadRoutes">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="routes" v-loading="routeLoading" row-key="openidHash">
        <el-table-column prop="openid" label="openid" min-width="180" />
        <el-table-column prop="openvikingUserId" label="OpenViking 用户ID" min-width="260" />
        <el-table-column label="绑定实例" min-width="220">
          <template #default="{ row }">
            <el-select :model-value="row.instanceId" @change="(value: string) => migrate(row, value)">
              <el-option v-for="instance in runningInstances" :key="instance.id" :label="instance.name" :value="instance.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="首次调用" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最近调用" width="180">
          <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @change="loadRoutes"
        />
      </div>
    </el-card>
  </section>
</template>
