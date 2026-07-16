<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { Plus } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";

const admin = useAdminStore();
const actionLoading = ref("");
const error = ref("");
const createForm = reactive({ name: "OpenClaw 实例", presetId: "" });

watch(
  () => admin.defaultPreset?.id,
  (presetId) => {
    if (!createForm.presetId) {
      createForm.presetId = presetId || "";
    }
  },
  { immediate: true }
);

async function createInstance() {
  if (!createForm.presetId) {
    error.value = "请先选择模型预设。";
    ElMessage.warning(error.value);
    return;
  }
  actionLoading.value = "instance:create";
  error.value = "";
  try {
    await admin.createInstance(createForm.name, createForm.presetId);
    createForm.name = "OpenClaw 实例";
    ElMessage.success("实例创建任务已提交，启动进度会在实例列表中更新。");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "实例创建失败";
    ElMessage.error(error.value);
  } finally {
    actionLoading.value = "";
  }
}
</script>

<template>
  <section class="workspace instance-create-page">
    <PageHeader title="创建实例" description="从已配置的模型预设启动新的 OpenClaw 实例。" />

    <el-alert v-if="error || admin.error" :title="error || admin.error" type="error" show-icon />

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>实例信息</span>
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

    <el-card shadow="never">
      <template #header>
        <div class="card-title with-action">
          <span>可用模型预设</span>
          <el-tag effect="plain">{{ admin.configuredPresets.length }} 个已配置</el-tag>
        </div>
      </template>
      <el-table :data="admin.configuredPresets" row-key="id">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="模型" min-width="220">
          <template #default="{ row }">{{ row.providerId }}/{{ row.modelId }}</template>
        </el-table-column>
        <el-table-column label="Context Window" width="150" align="right">
          <template #default="{ row }">{{ row.contextWindow }}</template>
        </el-table-column>
        <el-table-column label="Max Tokens" width="130" align="right">
          <template #default="{ row }">{{ row.maxTokens }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.isDefault ? 'success' : 'info'" effect="plain">
              {{ row.isDefault ? "默认" : "可用" }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>
