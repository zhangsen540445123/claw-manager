<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { DownloadCloud, Edit3, GitBranch, Plus, RefreshCw, Trash2, UploadCloud } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { useAdminStore } from "../../stores/admin";
import type { PublicSkillDefinition, PublicSkillRepository, SkillSyncResult } from "../../api/types";
import { formatDateTime } from "../../utils/adminUi";

const admin = useAdminStore();
const loading = ref(false);
const actionLoading = ref("");
const repositoryDialogVisible = ref(false);
const editingRepository = ref<PublicSkillRepository | null>(null);
const selectedSkills = ref<PublicSkillDefinition[]>([]);
const selectedInstanceIds = ref<string[]>([]);
const lastSyncResults = ref<SkillSyncResult[]>([]);
const repositoryForm = reactive({
  name: "",
  repoUrl: "",
  branch: "main",
  authType: "none",
  accessToken: ""
});

const syncableSkills = computed(() => admin.skillDefinitions.filter((skill) => skill.syncable));
const instanceNameById = computed(() => {
  return Object.fromEntries(admin.instances.map((instance) => [instance.id, instance.name]));
});
const syncCountBySkill = computed(() => {
  const result: Record<string, number> = {};
  for (const sync of admin.skillInstanceSyncs) {
    result[sync.skillName] = (result[sync.skillName] || 0) + 1;
  }
  return result;
});

onMounted(() => {
  void loadAll();
});

async function loadAll() {
  loading.value = true;
  try {
    await Promise.all([
      admin.loadSkillRepositories(),
      admin.loadSkills(),
      admin.loadInstances()
    ]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Skill 管理数据读取失败");
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

function openCreateRepository() {
  editingRepository.value = null;
  repositoryForm.name = "";
  repositoryForm.repoUrl = "";
  repositoryForm.branch = "main";
  repositoryForm.authType = "none";
  repositoryForm.accessToken = "";
  repositoryDialogVisible.value = true;
}

function openEditRepository(repository: PublicSkillRepository) {
  editingRepository.value = repository;
  repositoryForm.name = repository.name;
  repositoryForm.repoUrl = repository.repoUrl;
  repositoryForm.branch = repository.branch || "main";
  repositoryForm.authType = repository.authType || "none";
  repositoryForm.accessToken = "";
  repositoryDialogVisible.value = true;
}

async function submitRepository() {
  const payload = {
    name: repositoryForm.name,
    repoUrl: repositoryForm.repoUrl,
    branch: repositoryForm.branch || "main",
    authType: repositoryForm.authType,
    accessToken: repositoryForm.accessToken || undefined
  };
  await runAction("save-repository", async () => {
    if (editingRepository.value) {
      await admin.updateSkillRepository(editingRepository.value.id, payload);
      ElMessage.success("Skill 仓库已更新");
    } else {
      await admin.createSkillRepository(payload);
      ElMessage.success("Skill 仓库已创建");
    }
    repositoryDialogVisible.value = false;
  });
}

async function pullRepository(repository: PublicSkillRepository) {
  await runAction(`pull:${repository.id}`, async () => {
    await admin.pullSkillRepository(repository.id);
    ElMessage.success("Skill 仓库已拉取并扫描");
  });
}

async function deleteRepository(repository: PublicSkillRepository) {
  try {
    await ElMessageBox.confirm(`确认删除 Skill 仓库 ${repository.name}？已扫描的 skill 和同步记录会一起移除。`, "删除 Skill 仓库", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await runAction(`delete:${repository.id}`, async () => {
    await admin.deleteSkillRepository(repository.id);
    ElMessage.success("Skill 仓库已删除");
  });
}

async function renameSkill(skill: PublicSkillDefinition) {
  try {
    const result = await ElMessageBox.prompt("同步到实例时会使用这个名称作为 workspace/skills 下的目录名。", "修改 Skill 名称", {
      inputValue: skill.skillName,
      confirmButtonText: "保存",
      cancelButtonText: "取消",
      inputPattern: /^(?!.*[\\/])(?!.*\.\.).{1,120}$/,
      inputErrorMessage: "名称不能为空，且不能包含路径分隔符或 .."
    });
    await runAction(`rename:${skill.id}`, async () => {
      await admin.updateSkillName(skill.id, result.value);
      ElMessage.success("Skill 名称已更新");
    });
  } catch {
    // 用户取消时不提示。
  }
}

function handleSkillSelectionChange(selection: PublicSkillDefinition[]) {
  selectedSkills.value = selection;
}

async function syncSelected() {
  if (selectedSkills.value.length === 0 || selectedInstanceIds.value.length === 0) {
    ElMessage.warning("请先选择要同步的 Skill 和目标实例");
    return;
  }
  await runAction("sync", async () => {
    lastSyncResults.value = await admin.syncSkills(selectedSkills.value.map((skill) => ({
      skillId: skill.id,
      instanceIds: selectedInstanceIds.value
    })));
    const failed = lastSyncResults.value.filter((result) => result.status !== "success").length;
    if (failed > 0) {
      ElMessage.warning(`同步完成，其中 ${failed} 项失败`);
    } else {
      ElMessage.success("Skill 同步完成");
    }
  });
}

function shortCommit(value: string) {
  return value ? value.slice(0, 10) : "-";
}

function pullStatusType(status: string) {
  if (status === "success") return "success";
  if (status === "failed") return "danger";
  return "info";
}

function syncStatusType(status: string) {
  return status === "success" ? "success" : "danger";
}
</script>

<template>
  <section class="workspace skills-page">
    <PageHeader title="Skill 管理" description="从 GitHub 仓库拉取 OpenClaw 本地 Skill，并按实例选择同步。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateRepository">新增仓库</el-button>
      </template>
    </PageHeader>

    <section class="metric-grid">
      <MetricCard label="Skill 仓库" :value="admin.skillRepositories.length" />
      <MetricCard label="已发现 Skill" :value="admin.skillDefinitions.length" />
      <MetricCard label="可同步" :value="syncableSkills.length" tone="success" />
      <MetricCard label="实例同步记录" :value="admin.skillInstanceSyncs.length" />
    </section>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-title">
          <strong>Skill 仓库</strong>
          <span>支持公开仓库和 HTTPS Token 私库</span>
        </div>
      </template>
      <el-table :data="admin.skillRepositories" v-loading="loading" row-key="id">
        <el-table-column label="仓库" min-width="220">
          <template #default="{ row }">
            <div class="primary-cell">
              <strong>{{ row.name }}</strong>
              <small>{{ row.repoUrl }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分支" width="120">
          <template #default="{ row }">
            <span class="inline-icon"><GitBranch :size="14" />{{ row.branch }}</span>
          </template>
        </el-table-column>
        <el-table-column label="认证" width="130">
          <template #default="{ row }">
            <el-tag :type="row.authType === 'token' ? 'warning' : 'info'" effect="plain">
              {{ row.authType === "token" ? "Token" : "公开" }}
            </el-tag>
            <div v-if="row.tokenPreview" class="muted mono">{{ row.tokenPreview }}</div>
          </template>
        </el-table-column>
        <el-table-column label="拉取状态" width="150">
          <template #default="{ row }">
            <el-tag :type="pullStatusType(row.lastPullStatus)" effect="plain">
              {{ row.lastPullStatus || "never" }}
            </el-tag>
            <div class="muted">{{ row.lastPullMessage || "-" }}</div>
          </template>
        </el-table-column>
        <el-table-column label="最近 commit" width="130">
          <template #default="{ row }">
            <span class="mono">{{ shortCommit(row.lastCommitSha) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="right" fixed="right">
          <template #default="{ row }">
            <div class="instance-actions">
              <el-tooltip content="拉取更新">
                <span>
                  <el-button
                    circle
                    :icon="DownloadCloud"
                    :loading="actionLoading === `pull:${row.id}`"
                    @click="pullRepository(row)"
                  />
                </span>
              </el-tooltip>
              <el-tooltip content="编辑仓库">
                <span><el-button circle :icon="Edit3" @click="openEditRepository(row)" /></span>
              </el-tooltip>
              <el-tooltip content="删除仓库">
                <span>
                  <el-button
                    circle
                    :icon="Trash2"
                    :loading="actionLoading === `delete:${row.id}`"
                    @click="deleteRepository(row)"
                  />
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-title">
          <strong>已扫描 Skill</strong>
          <span>勾选要同步的 Skill，再选择目标实例</span>
        </div>
      </template>
      <el-table
        :data="admin.skillDefinitions"
        v-loading="loading"
        row-key="id"
        @selection-change="handleSkillSelectionChange"
      >
        <el-table-column type="selection" width="44" :selectable="(row: PublicSkillDefinition) => row.syncable" />
        <el-table-column label="Skill" min-width="220">
          <template #default="{ row }">
            <div class="primary-cell">
              <strong>{{ row.skillName }}</strong>
              <small>{{ row.description || "-" }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="220">
          <template #default="{ row }">
            <div class="primary-cell">
              <span>{{ row.repositoryName }}</span>
              <small>{{ row.relativePath }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="row.syncable ? 'success' : 'danger'" effect="plain">
              {{ row.syncable ? "可同步" : "不可同步" }}
            </el-tag>
            <div v-if="row.warnings?.length" class="muted">{{ row.warnings.join("；") }}</div>
          </template>
        </el-table-column>
        <el-table-column label="已同步实例" width="110">
          <template #default="{ row }">{{ syncCountBySkill[row.skillName] || 0 }}</template>
        </el-table-column>
        <el-table-column label="commit" width="120">
          <template #default="{ row }">
            <span class="mono">{{ shortCommit(row.lastCommitSha) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="修改名称">
              <span>
                <el-button
                  circle
                  :icon="Edit3"
                  :loading="actionLoading === `rename:${row.id}`"
                  @click="renameSkill(row)"
                />
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-title">
          <strong>同步到实例</strong>
          <span>不会主动重启实例；运行中实例依赖 OpenClaw watcher 生效</span>
        </div>
      </template>
      <div class="sync-toolbar">
        <el-select
          v-model="selectedInstanceIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择目标实例"
        >
          <el-option
            v-for="instance in admin.instances"
            :key="instance.id"
            :label="`${instance.name} · ${instance.status}`"
            :value="instance.id"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="UploadCloud"
          :loading="actionLoading === 'sync'"
          :disabled="selectedSkills.length === 0 || selectedInstanceIds.length === 0"
          @click="syncSelected"
        >
          同步选中 Skill
        </el-button>
      </div>
      <div class="sync-summary">
        已选择 {{ selectedSkills.length }} 个 Skill，{{ selectedInstanceIds.length }} 个实例。
        可同步 Skill 共 {{ syncableSkills.length }} 个。
      </div>
      <el-table v-if="lastSyncResults.length" :data="lastSyncResults" row-key="skillId + instanceId" class="result-table">
        <el-table-column label="Skill" prop="skillName" min-width="160" />
        <el-table-column label="实例" min-width="180">
          <template #default="{ row }">{{ row.instanceName || instanceNameById[row.instanceId] || row.instanceId }}</template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <el-tag :type="syncStatusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="消息" prop="message" min-width="220" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-title">
          <strong>实例同步记录</strong>
          <span>按实例和 Skill 名称记录当前来源</span>
        </div>
      </template>
      <el-table :data="admin.skillInstanceSyncs" v-loading="loading" row-key="instanceId + skillName">
        <el-table-column label="实例" min-width="180">
          <template #default="{ row }">{{ instanceNameById[row.instanceId] || row.instanceId }}</template>
        </el-table-column>
        <el-table-column label="Skill" prop="skillName" min-width="160" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="syncStatusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="commit" width="120">
          <template #default="{ row }">
            <span class="mono">{{ shortCommit(row.sourceCommitSha) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="同步时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.syncedAt || row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="消息" prop="message" min-width="220" />
      </el-table>
    </el-card>

    <el-dialog v-model="repositoryDialogVisible" :title="editingRepository ? '编辑 Skill 仓库' : '新增 Skill 仓库'" width="620px">
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="repositoryForm.name" placeholder="例如 产品技能仓库" />
        </el-form-item>
        <el-form-item label="GitHub URL" required>
          <el-input v-model="repositoryForm.repoUrl" placeholder="https://github.com/org/repo.git" />
        </el-form-item>
        <el-form-item label="分支" required>
          <el-input v-model="repositoryForm.branch" placeholder="main" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-radio-group v-model="repositoryForm.authType">
            <el-radio-button label="none">公开仓库</el-radio-button>
            <el-radio-button label="token">HTTPS Token</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="repositoryForm.authType === 'token'" label="Token">
          <el-input
            v-model="repositoryForm.accessToken"
            type="password"
            show-password
            placeholder="编辑时留空表示继续使用已保存 token"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="repositoryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading === 'save-repository'" @click="submitRepository">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.panel {
  margin-bottom: 14px;
}

.panel-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.panel-title span,
.muted,
.primary-cell small {
  color: #6b7280;
  font-size: 12px;
}

.primary-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.primary-cell strong,
.primary-cell span,
.primary-cell small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inline-icon {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
}

.sync-toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.sync-summary {
  margin-top: 10px;
  color: #6b7280;
  font-size: 13px;
}

.result-table {
  margin-top: 12px;
}

@media (max-width: 760px) {
  .sync-toolbar {
    grid-template-columns: 1fr;
  }

  .panel-title {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
