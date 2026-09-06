import { defineStore } from "pinia";
import { api, jsonBody } from "../api/http";
import type {
  AppEvent,
  InstanceStats,
  ModelPresetDeleteResult,
  ModelPresetSyncResult,
  ModelPresetUsage,
  ModelProviderDefinition,
  PublicApiChannelPluginStatus,
  PublicApiChannelPluginVersions,
  PublicAgentWorkspacePreset,
  AgentWorkspacePresetPushResult,
  PublicMiniappClient,
  PublicOpenVikingPluginStatus,
  PublicOpenVikingPluginVersions,
  PublicOpenVikingSettings,
  PublicInstance,
  PublicInstanceDeleteOperation,
  PublicImageGenerationSettings,
  PublicInstanceModelAuth,
  PublicInstanceProvisioning,
  PublicModelPreset,
  PublicWechatBindLink,
  PublicWechatBinding,
  PublicWechatPluginStatus,
  PublicWechatPluginVersions,
  PublicWechatUser,
  PublicWechatUserCleanupOperation,
  PublicSkillDefinition,
  PublicSkillInstanceSync,
  PublicSkillRepository,
  SkillSyncResult,
  WechatBindLinkPage,
  WechatBindingLookup
} from "../api/types";

interface RunnerImageStatus {
  image: string;
  status: string;
  message: string;
  present: boolean;
  imageId: string;
  updatedAt: string;
}

interface InstanceResponse {
  instance: PublicInstance;
  gatewayRestarted?: boolean;
}

interface WechatUserCleanupResponse {
  operation: PublicWechatUserCleanupOperation;
  instance: PublicInstance;
}

interface InstanceDeleteResponse {
  operation: PublicInstanceDeleteOperation;
}

interface WechatUserCleanupBatchResponse {
  operations: PublicWechatUserCleanupOperation[];
  instance: PublicInstance;
}

interface BatchInstanceOperationItem {
  instanceId: string;
  status: string;
  message: string;
  instance?: PublicInstance;
}

interface WechatChannelRestartItem {
  instanceId: string;
  accountId: string;
  status: string;
  message: string;
}

interface ModelPresetResponse {
  preset: PublicModelPreset;
  sync?: ModelPresetSyncResult;
}

interface WechatPluginBatchItem {
  instanceId: string;
  plugin: PublicWechatPluginStatus;
}

interface OpenVikingPluginBatchItem {
  instanceId: string;
  plugin: PublicOpenVikingPluginStatus;
}

interface ApiChannelPluginBatchItem {
  instanceId: string;
  plugin: PublicApiChannelPluginStatus;
}

interface MiniappBridgePluginBatchItem {
  instanceId: string;
  plugin: PublicApiChannelPluginStatus;
}

export const useAdminStore = defineStore("admin", {
  state: () => ({
    instances: [] as PublicInstance[],
    wechatUsers: [] as PublicWechatUser[],
    presets: [] as PublicModelPreset[],
    imageGenerationSettings: null as PublicImageGenerationSettings | null,
    agentWorkspacePreset: null as PublicAgentWorkspacePreset | null,
    providers: [] as ModelProviderDefinition[],
    runnerImage: null as RunnerImageStatus | null,
    serverLogs: "",
    statsByInstanceId: {} as Record<string, InstanceStats | null>,
    wechatPluginStatusByInstanceId: {} as Record<string, PublicWechatPluginStatus>,
    wechatPluginVersions: { latest: "", versions: [] } as PublicWechatPluginVersions,
    openVikingSettings: null as PublicOpenVikingSettings | null,
    openVikingPluginStatusByInstanceId: {} as Record<string, PublicOpenVikingPluginStatus>,
    openVikingPluginVersions: { latest: "", versions: [] } as PublicOpenVikingPluginVersions,
    miniappClients: [] as PublicMiniappClient[],
    skillRepositories: [] as PublicSkillRepository[],
    skillDefinitions: [] as PublicSkillDefinition[],
    skillInstanceSyncs: [] as PublicSkillInstanceSync[],
    apiChannelPluginStatusByInstanceId: {} as Record<string, PublicApiChannelPluginStatus>,
    apiChannelPluginVersions: { latest: "", versions: [] } as PublicApiChannelPluginVersions,
    miniappBridgePluginStatusByInstanceId: {} as Record<string, PublicApiChannelPluginStatus>,
    miniappBridgePluginVersions: { latest: "", versions: [] } as PublicApiChannelPluginVersions,
    workspaceFilePluginStatusByInstanceId: {} as Record<string, PublicApiChannelPluginStatus>,
    workspaceFilePluginVersions: { latest: "", versions: [] } as PublicApiChannelPluginVersions,
    wechatBindLinkByToken: {} as Record<string, PublicWechatBindLink>,
    wsConnected: false,
    catalogLoaded: false,
    loading: false,
    error: ""
  }),
  getters: {
    configuredPresets: (state) => state.presets.filter((preset) => preset.isConfigured),
    defaultPreset: (state) => state.presets.find((preset) => preset.isDefault && preset.isConfigured)
      ?? state.presets.find((preset) => preset.isConfigured)
      ?? null
  },
  actions: {
    async loadAll() {
      this.loading = true;
      this.error = "";
      try {
        const [instances, image, logs, presets, providers] = await Promise.all([
          api<{ instances: PublicInstance[] }>("/api/admin/instances"),
          api<{ image: RunnerImageStatus }>("/api/admin/runner-image"),
          api<{ logs: { text: string } }>("/api/admin/server-logs"),
          api<{ presets: PublicModelPreset[] }>("/api/model-presets"),
          api<{ providers: ModelProviderDefinition[] }>("/api/model-providers")
        ]);
        this.instances = instances.instances;
        this.runnerImage = image.image;
        this.serverLogs = logs.logs.text;
        this.presets = presets.presets;
        this.providers = providers.providers;
        this.catalogLoaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : "后台数据读取失败";
      } finally {
        this.loading = false;
      }
    },
    async loadInstances() {
      const response = await api<{ instances: PublicInstance[] }>("/api/admin/instances");
      this.instances = response.instances;
    },
    async loadWechatUsers() {
      const response = await api<{ users: PublicWechatUser[] }>("/api/admin/wechat-users");
      this.wechatUsers = response.users;
      return response.users;
    },
    async loadRunnerImage() {
      const response = await api<{ image: RunnerImageStatus }>("/api/admin/runner-image");
      this.runnerImage = response.image;
    },
    async loadServerLogs() {
      const response = await api<{ logs: { text: string } }>("/api/admin/server-logs");
      this.serverLogs = response.logs.text;
    },
    async refreshRunnerImage() {
      const response = await api<{ image: RunnerImageStatus }>("/api/admin/runner-image/refresh", { method: "POST" });
      this.runnerImage = response.image;
    },
    async createInstance(name: string, presetId: string) {
      const response = await api<InstanceResponse>("/api/admin/instances", {
        method: "POST",
        ...jsonBody({ name, presetId })
      });
      this.upsert(response.instance);
    },
    async startInstance(instanceId: string) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/start`, { method: "POST" });
      this.upsert(response.instance);
    },
    async stopInstance(instanceId: string) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/stop`, { method: "POST" });
      this.upsert(response.instance);
    },
    async restartGateway(instanceId: string) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/restart-gateway`, { method: "POST" });
      this.upsert(response.instance);
    },
    async deleteInstance(instanceId: string, force = false) {
      const response = await api<InstanceDeleteResponse>(
        `/api/admin/instances/${instanceId}?force=${force ? "true" : "false"}`,
        { method: "DELETE" }
      );
      this.instances = this.instances.map((instance) =>
        instance.id === instanceId ? { ...instance, status: "deleting" } : instance
      );
      return response.operation;
    },
    async loadInstanceDeleteOperation(operationId: string) {
      const response = await api<InstanceDeleteResponse>(`/api/admin/instance-delete-operations/${encodeURIComponent(operationId)}`);
      return response.operation;
    },
    async retryInstanceDelete(operationId: string) {
      const response = await api<InstanceDeleteResponse>(
        `/api/admin/instance-delete-operations/${encodeURIComponent(operationId)}/retry`,
        { method: "POST" }
      );
      await this.loadInstances();
      return response.operation;
    },
    async batchRestartGateway(instanceIds: string[]) {
      const response = await api<{ instances: BatchInstanceOperationItem[] }>("/api/admin/instances/batch/restart-gateway", {
        method: "POST",
        ...jsonBody({ instanceIds })
      });
      for (const item of response.instances) {
        if (item.instance) {
          this.upsert(item.instance);
        }
      }
      return response.instances;
    },
    async unbindWechat(instanceId: string) {
      const response = await api<WechatUserCleanupBatchResponse>(`/api/admin/instances/${instanceId}/wechat-unbind`, { method: "POST" });
      this.upsert(response.instance);
      await this.loadWechatUsers();
      return response;
    },
    async saveWechatRemark(instanceId: string, accountId: string, remark: string) {
      await this.saveWechatProfile(instanceId, accountId, { remark });
    },
    async saveWechatProfile(instanceId: string, accountId: string, payload: { phone?: string; remark?: string }) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}`, {
        method: "PUT",
        ...jsonBody(payload)
      });
      this.upsert(response.instance);
      await this.loadWechatUsers();
    },
    async deleteWechatAccount(instanceId: string, accountId: string) {
      const response = await api<WechatUserCleanupResponse>(`/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}`, {
        method: "DELETE"
      });
      this.upsert(response.instance);
      await this.loadWechatUsers();
      return response;
    },
    async retryWechatUserCleanup(operationId: string) {
      const response = await api<{ operation: PublicWechatUserCleanupOperation }>(
        `/api/admin/wechat-user-cleanups/${encodeURIComponent(operationId)}/retry`,
        { method: "POST" }
      );
      await Promise.all([this.loadWechatUsers(), this.loadInstances()]);
      return response.operation;
    },
    async restartWechatAccountChannel(instanceId: string, accountId: string) {
      const response = await api<{ account: WechatChannelRestartItem }>(
        `/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}/restart-channel`,
        { method: "POST" }
      );
      await Promise.all([this.loadInstances(), this.loadWechatUsers()]);
      return response.account;
    },
    async batchRestartWechatAccountChannels(accounts: Array<{ instanceId: string; accountId: string }>) {
      const response = await api<{ accounts: WechatChannelRestartItem[] }>("/api/admin/wechat-accounts/restart-channel", {
        method: "POST",
        ...jsonBody({ accounts })
      });
      await Promise.all([this.loadInstances(), this.loadWechatUsers()]);
      return response.accounts;
    },
    async createBindLink(mode: "new" | "existing", phone = "") {
      const response = await api<{ link: PublicWechatBindLink }>("/api/admin/wechat-bind-links", {
        method: "POST",
        ...jsonBody({ mode, phone })
      });
      return response.link;
    },
    async loadWechatPluginStatus(instanceId: string, checkLatest = false) {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin?checkLatest=${checkLatest ? "true" : "false"}`
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: withWechatPluginVersion(response.plugin, this.wechatPluginVersions)
      };
      return this.wechatPluginStatusByInstanceId[instanceId];
    },
    async loadWechatPluginVersions() {
      const response = await api<{ versions: PublicWechatPluginVersions }>("/api/admin/wechat-plugins/versions");
      this.wechatPluginVersions = response.versions;
      this.applyWechatPluginVersions(response.versions);
      return response.versions;
    },
    async loadOpenVikingSettings() {
      const response = await api<{ settings: PublicOpenVikingSettings }>("/api/admin/openviking-settings");
      this.openVikingSettings = response.settings;
      return response.settings;
    },
    async loadImageGenerationSettings() {
      const response = await api<{ settings: PublicImageGenerationSettings }>("/api/admin/image-generation-settings");
      this.imageGenerationSettings = response.settings;
      return response.settings;
    },
    async loadAgentWorkspacePreset() {
      const response = await api<{ preset: PublicAgentWorkspacePreset }>('/api/admin/agent-workspace-preset');
      this.agentWorkspacePreset = response.preset;
      return response.preset;
    },
    async saveAgentWorkspacePreset(payload: Record<string, unknown>) {
      const response = await api<{ preset: PublicAgentWorkspacePreset }>('/api/admin/agent-workspace-preset', {
        method: 'PUT',
        ...jsonBody(payload)
      });
      this.agentWorkspacePreset = response.preset;
      return response.preset;
    },
    async pushAgentWorkspacePreset() {
      const response = await api<{ result: AgentWorkspacePresetPushResult }>(
        '/api/admin/agent-workspace-preset/push',
        { method: 'POST' }
      );
      return response.result;
    },
    async saveImageGenerationSettings(payload: Record<string, unknown>) {
      const response = await api<{ settings: PublicImageGenerationSettings; syncedInstanceIds: string[]; restartRequired: boolean }>(
        "/api/admin/image-generation-settings", { method: "PUT", ...jsonBody(payload) }
      );
      this.imageGenerationSettings = response.settings;
      return response;
    },
    async saveOpenVikingSettings(payload: {
      baseUrl: string;
      trustedModeEnabled: boolean;
      accountId: string;
      pluginPackage: string;
      identitySalt?: string;
      rootApiKey?: string;
      clearRootApiKey?: boolean;
    }) {
      const response = await api<{ settings: PublicOpenVikingSettings }>("/api/admin/openviking-settings", {
        method: "PUT",
        ...jsonBody(payload)
      });
      this.openVikingSettings = response.settings;
      return response.settings;
    },
    async loadOpenVikingPluginStatus(instanceId: string, checkLatest = false) {
      const response = await api<{ plugin: PublicOpenVikingPluginStatus }>(
        `/api/admin/instances/${instanceId}/openviking-plugin?checkLatest=${checkLatest ? "true" : "false"}`
      );
      this.openVikingPluginStatusByInstanceId = {
        ...this.openVikingPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.openVikingPluginVersions)
      };
      return this.openVikingPluginStatusByInstanceId[instanceId];
    },
    async loadOpenVikingPluginVersions(forceRefresh = false) {
      const response = await api<{ versions: PublicOpenVikingPluginVersions }>(
        `/api/admin/openviking-plugins/versions${forceRefresh ? "?forceRefresh=true" : ""}`
      );
      this.openVikingPluginVersions = response.versions;
      this.applyOpenVikingPluginVersions(response.versions);
      return response.versions;
    },
    async installOpenVikingPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicOpenVikingPluginStatus }>(
        `/api/admin/instances/${instanceId}/openviking-plugin/install`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.openVikingPluginStatusByInstanceId = {
        ...this.openVikingPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.openVikingPluginVersions)
      };
      return this.openVikingPluginStatusByInstanceId[instanceId];
    },
    async uninstallOpenVikingPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicOpenVikingPluginStatus }>(
        `/api/admin/instances/${instanceId}/openviking-plugin/uninstall`,
        { method: "POST" }
      );
      this.openVikingPluginStatusByInstanceId = {
        ...this.openVikingPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.openVikingPluginVersions)
      };
      return this.openVikingPluginStatusByInstanceId[instanceId];
    },
    async upgradeOpenVikingPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicOpenVikingPluginStatus }>(
        `/api/admin/instances/${instanceId}/openviking-plugin/upgrade`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.openVikingPluginStatusByInstanceId = {
        ...this.openVikingPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.openVikingPluginVersions)
      };
      return this.openVikingPluginStatusByInstanceId[instanceId];
    },
    async reinstallOpenVikingPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicOpenVikingPluginStatus }>(
        `/api/admin/instances/${instanceId}/openviking-plugin/reinstall`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.openVikingPluginStatusByInstanceId = {
        ...this.openVikingPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.openVikingPluginVersions)
      };
      return this.openVikingPluginStatusByInstanceId[instanceId];
    },
    async batchCheckOpenVikingPlugins(instanceIds: string[]) {
      return this.batchOpenVikingPlugins("check", instanceIds);
    },
    async batchInstallOpenVikingPlugins(instanceIds: string[], version = "") {
      return this.batchOpenVikingPlugins("install", instanceIds, version);
    },
    async batchUninstallOpenVikingPlugins(instanceIds: string[]) {
      return this.batchOpenVikingPlugins("uninstall", instanceIds);
    },
    async batchUpgradeOpenVikingPlugins(instanceIds: string[], version = "") {
      return this.batchOpenVikingPlugins("upgrade", instanceIds, version);
    },
    async batchReinstallOpenVikingPlugins(instanceIds: string[], version = "") {
      return this.batchOpenVikingPlugins("reinstall", instanceIds, version);
    },
    async batchOpenVikingPlugins(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall", instanceIds: string[], version = "") {
      const response = await api<{ plugins: OpenVikingPluginBatchItem[] }>(`/api/admin/openviking-plugins/${action}`, {
        method: "POST",
        ...jsonBody({ instanceIds, version })
      });
      const next = { ...this.openVikingPluginStatusByInstanceId };
      for (const item of response.plugins) {
        next[item.instanceId] = withPluginVersion(item.plugin, this.openVikingPluginVersions);
      }
      this.openVikingPluginStatusByInstanceId = next;
      return response.plugins;
    },
    applyOpenVikingPluginVersions(versions: PublicOpenVikingPluginVersions) {
      const next = { ...this.openVikingPluginStatusByInstanceId };
      for (const [instanceId, plugin] of Object.entries(next)) {
        next[instanceId] = withPluginVersion(plugin, versions);
      }
      this.openVikingPluginStatusByInstanceId = next;
    },
    async loadMiniappClients() {
      const response = await api<{ clients: PublicMiniappClient[] }>("/api/admin/miniapp-clients");
      this.miniappClients = response.clients;
      return response.clients;
    },
    async createMiniappClient(payload: { appId: string; enabled: boolean }) {
      const response = await api<{ client: PublicMiniappClient }>("/api/admin/miniapp-clients", {
        method: "POST",
        ...jsonBody(payload)
      });
      await this.loadMiniappClients();
      return response.client;
    },
    async updateMiniappClient(appId: string, enabled: boolean) {
      const response = await api<{ client: PublicMiniappClient }>(`/api/admin/miniapp-clients/${encodeURIComponent(appId)}`, {
        method: "PUT",
        ...jsonBody({ enabled })
      });
      await this.loadMiniappClients();
      return response.client;
    },
    async resetMiniappClientSecret(appId: string) {
      const response = await api<{ client: PublicMiniappClient }>(
        `/api/admin/miniapp-clients/${encodeURIComponent(appId)}/secret/reset`,
        { method: "POST" }
      );
      await this.loadMiniappClients();
      return response.client;
    },
    async deleteMiniappClient(appId: string) {
      await api<{ ok: boolean }>(`/api/admin/miniapp-clients/${encodeURIComponent(appId)}`, { method: "DELETE" });
      await this.loadMiniappClients();
    },
    async loadSkillRepositories() {
      const response = await api<{ repositories: PublicSkillRepository[] }>("/api/admin/skill-repositories");
      this.skillRepositories = response.repositories;
      return response.repositories;
    },
    async createSkillRepository(payload: {
      name: string;
      repoUrl: string;
      branch: string;
      authType: string;
      accessToken?: string;
    }) {
      const response = await api<{ repository: PublicSkillRepository }>("/api/admin/skill-repositories", {
        method: "POST",
        ...jsonBody(payload)
      });
      await this.loadSkillRepositories();
      return response.repository;
    },
    async updateSkillRepository(repositoryId: string, payload: {
      name: string;
      repoUrl: string;
      branch: string;
      authType: string;
      accessToken?: string;
    }) {
      const response = await api<{ repository: PublicSkillRepository }>(`/api/admin/skill-repositories/${encodeURIComponent(repositoryId)}`, {
        method: "PATCH",
        ...jsonBody(payload)
      });
      await this.loadSkillRepositories();
      return response.repository;
    },
    async deleteSkillRepository(repositoryId: string) {
      await api<{ ok: boolean }>(`/api/admin/skill-repositories/${encodeURIComponent(repositoryId)}`, { method: "DELETE" });
      await this.loadSkillRepositories();
      await this.loadSkills();
    },
    async pullSkillRepository(repositoryId: string) {
      const response = await api<{ repository: PublicSkillRepository }>(
        `/api/admin/skill-repositories/${encodeURIComponent(repositoryId)}/pull`,
        { method: "POST" }
      );
      await this.loadSkillRepositories();
      await this.loadSkills();
      return response.repository;
    },
    async loadSkills() {
      const response = await api<{ skills: PublicSkillDefinition[]; syncs: PublicSkillInstanceSync[] }>("/api/admin/skills");
      this.skillDefinitions = response.skills;
      this.skillInstanceSyncs = response.syncs;
      return response;
    },
    async updateSkillName(skillId: string, skillName: string) {
      const response = await api<{ skill: PublicSkillDefinition }>(`/api/admin/skills/${encodeURIComponent(skillId)}`, {
        method: "PATCH",
        ...jsonBody({ skillName })
      });
      await this.loadSkills();
      return response.skill;
    },
    async syncSkills(items: Array<{ skillId: string; instanceIds: string[] }>) {
      const response = await api<{ results: SkillSyncResult[] }>("/api/admin/skills/sync", {
        method: "POST",
        ...jsonBody({ items })
      });
      await this.loadSkills();
      return response.results;
    },
    async loadApiChannelPluginStatus(instanceId: string, checkLatest = false) {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/api-channel-plugin?checkLatest=${checkLatest ? "true" : "false"}`
      );
      this.apiChannelPluginStatusByInstanceId = {
        ...this.apiChannelPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.apiChannelPluginVersions)
      };
      return this.apiChannelPluginStatusByInstanceId[instanceId];
    },
    async loadApiChannelPluginVersions(forceRefresh = false) {
      const response = await api<{ versions: PublicApiChannelPluginVersions }>(
        `/api/admin/api-channel-plugins/versions${forceRefresh ? "?forceRefresh=true" : ""}`
      );
      this.apiChannelPluginVersions = response.versions;
      this.applyApiChannelPluginVersions(response.versions);
      return response.versions;
    },
    async installApiChannelPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/api-channel-plugin/install`,
        { method: "POST", ...jsonBody({ version }) }
      );
      this.apiChannelPluginStatusByInstanceId = {
        ...this.apiChannelPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.apiChannelPluginVersions)
      };
      return this.apiChannelPluginStatusByInstanceId[instanceId];
    },
    async uninstallApiChannelPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/api-channel-plugin/uninstall`,
        { method: "POST" }
      );
      this.apiChannelPluginStatusByInstanceId = {
        ...this.apiChannelPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.apiChannelPluginVersions)
      };
      return this.apiChannelPluginStatusByInstanceId[instanceId];
    },
    async upgradeApiChannelPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/api-channel-plugin/upgrade`,
        { method: "POST", ...jsonBody({ version }) }
      );
      this.apiChannelPluginStatusByInstanceId = {
        ...this.apiChannelPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.apiChannelPluginVersions)
      };
      return this.apiChannelPluginStatusByInstanceId[instanceId];
    },
    async reinstallApiChannelPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/api-channel-plugin/reinstall`,
        { method: "POST", ...jsonBody({ version }) }
      );
      this.apiChannelPluginStatusByInstanceId = {
        ...this.apiChannelPluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.apiChannelPluginVersions)
      };
      return this.apiChannelPluginStatusByInstanceId[instanceId];
    },
    async batchCheckApiChannelPlugins(instanceIds: string[]) {
      return this.batchApiChannelPlugins("check", instanceIds);
    },
    async batchInstallApiChannelPlugins(instanceIds: string[], version = "") {
      return this.batchApiChannelPlugins("install", instanceIds, version);
    },
    async batchUninstallApiChannelPlugins(instanceIds: string[]) {
      return this.batchApiChannelPlugins("uninstall", instanceIds);
    },
    async batchUpgradeApiChannelPlugins(instanceIds: string[], version = "") {
      return this.batchApiChannelPlugins("upgrade", instanceIds, version);
    },
    async batchReinstallApiChannelPlugins(instanceIds: string[], version = "") {
      return this.batchApiChannelPlugins("reinstall", instanceIds, version);
    },
    async batchApiChannelPlugins(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall", instanceIds: string[], version = "") {
      const response = await api<{ plugins: ApiChannelPluginBatchItem[] }>(`/api/admin/api-channel-plugins/${action}`, {
        method: "POST",
        ...jsonBody({ instanceIds, version })
      });
      const next = { ...this.apiChannelPluginStatusByInstanceId };
      for (const item of response.plugins) {
        next[item.instanceId] = withPluginVersion(item.plugin, this.apiChannelPluginVersions);
      }
      this.apiChannelPluginStatusByInstanceId = next;
      return response.plugins;
    },
    applyApiChannelPluginVersions(versions: PublicApiChannelPluginVersions) {
      const next = { ...this.apiChannelPluginStatusByInstanceId };
      for (const [instanceId, plugin] of Object.entries(next)) {
        next[instanceId] = withPluginVersion(plugin, versions);
      }
      this.apiChannelPluginStatusByInstanceId = next;
    },
    async loadMiniappBridgePluginStatus(instanceId: string, checkLatest = false) {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/miniapp-bridge-plugin?checkLatest=${checkLatest ? "true" : "false"}`
      );
      this.miniappBridgePluginStatusByInstanceId = {
        ...this.miniappBridgePluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.miniappBridgePluginVersions)
      };
      return this.miniappBridgePluginStatusByInstanceId[instanceId];
    },
    async loadMiniappBridgePluginVersions(forceRefresh = false) {
      const response = await api<{ versions: PublicApiChannelPluginVersions }>(
        `/api/admin/miniapp-bridge-plugins/versions${forceRefresh ? "?forceRefresh=true" : ""}`
      );
      this.miniappBridgePluginVersions = response.versions;
      this.applyMiniappBridgePluginVersions(response.versions);
      return response.versions;
    },
    async operateMiniappBridgePlugin(instanceId: string, action: "install" | "uninstall" | "upgrade" | "reinstall", version = "") {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/miniapp-bridge-plugin/${action}`,
        { method: "POST", ...jsonBody({ version }) }
      );
      this.miniappBridgePluginStatusByInstanceId = {
        ...this.miniappBridgePluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.miniappBridgePluginVersions)
      };
      return this.miniappBridgePluginStatusByInstanceId[instanceId];
    },
    async batchMiniappBridgePlugins(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall", instanceIds: string[], version = "") {
      const response = await api<{ plugins: MiniappBridgePluginBatchItem[] }>(`/api/admin/miniapp-bridge-plugins/${action}`, {
        method: "POST", ...jsonBody({ instanceIds, version })
      });
      const next = { ...this.miniappBridgePluginStatusByInstanceId };
      for (const item of response.plugins) next[item.instanceId] = withPluginVersion(item.plugin, this.miniappBridgePluginVersions);
      this.miniappBridgePluginStatusByInstanceId = next;
      return response.plugins;
    },
    applyMiniappBridgePluginVersions(versions: PublicApiChannelPluginVersions) {
      const next = { ...this.miniappBridgePluginStatusByInstanceId };
      for (const [instanceId, plugin] of Object.entries(next)) {
        next[instanceId] = withPluginVersion(plugin, versions);
      }
      this.miniappBridgePluginStatusByInstanceId = next;
    },
    async loadWorkspaceFilePluginStatus(instanceId: string, checkLatest = false) {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/workspace-file-plugin?checkLatest=${checkLatest ? "true" : "false"}`
      );
      this.workspaceFilePluginStatusByInstanceId = {
        ...this.workspaceFilePluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.workspaceFilePluginVersions)
      };
      return this.workspaceFilePluginStatusByInstanceId[instanceId];
    },
    async loadWorkspaceFilePluginVersions(forceRefresh = false) {
      const response = await api<{ versions: PublicApiChannelPluginVersions }>(
        `/api/admin/workspace-file-plugins/versions${forceRefresh ? "?forceRefresh=true" : ""}`
      );
      this.workspaceFilePluginVersions = response.versions;
      this.applyWorkspaceFilePluginVersions(response.versions);
      return response.versions;
    },
    async operateWorkspaceFilePlugin(instanceId: string, action: "install" | "uninstall" | "upgrade" | "reinstall", version = "") {
      const response = await api<{ plugin: PublicApiChannelPluginStatus }>(
        `/api/admin/instances/${instanceId}/workspace-file-plugin/${action}`,
        { method: "POST", ...jsonBody({ version }) }
      );
      this.workspaceFilePluginStatusByInstanceId = {
        ...this.workspaceFilePluginStatusByInstanceId,
        [instanceId]: withPluginVersion(response.plugin, this.workspaceFilePluginVersions)
      };
      return this.workspaceFilePluginStatusByInstanceId[instanceId];
    },
    async batchWorkspaceFilePlugins(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall", instanceIds: string[], version = "") {
      const response = await api<{ plugins: MiniappBridgePluginBatchItem[] }>(`/api/admin/workspace-file-plugins/${action}`, {
        method: "POST", ...jsonBody({ instanceIds, version })
      });
      const next = { ...this.workspaceFilePluginStatusByInstanceId };
      for (const item of response.plugins) next[item.instanceId] = withPluginVersion(item.plugin, this.workspaceFilePluginVersions);
      this.workspaceFilePluginStatusByInstanceId = next;
      return response.plugins;
    },
    applyWorkspaceFilePluginVersions(versions: PublicApiChannelPluginVersions) {
      const next = { ...this.workspaceFilePluginStatusByInstanceId };
      for (const [instanceId, plugin] of Object.entries(next)) {
        next[instanceId] = withPluginVersion(plugin, versions);
      }
      this.workspaceFilePluginStatusByInstanceId = next;
    },
    async installWechatPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/install`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: withWechatPluginVersion(response.plugin, this.wechatPluginVersions)
      };
      return this.wechatPluginStatusByInstanceId[instanceId];
    },
    async uninstallWechatPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/uninstall`,
        { method: "POST" }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: withWechatPluginVersion(response.plugin, this.wechatPluginVersions)
      };
      return this.wechatPluginStatusByInstanceId[instanceId];
    },
    async upgradeWechatPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/upgrade`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: withWechatPluginVersion(response.plugin, this.wechatPluginVersions)
      };
      return this.wechatPluginStatusByInstanceId[instanceId];
    },
    async reinstallWechatPlugin(instanceId: string, version = "") {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/reinstall`,
        {
          method: "POST",
          ...jsonBody({ version })
        }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: withWechatPluginVersion(response.plugin, this.wechatPluginVersions)
      };
      return this.wechatPluginStatusByInstanceId[instanceId];
    },
    async batchCheckWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("check", instanceIds);
    },
    async batchInstallWechatPlugins(instanceIds: string[], version = "") {
      return this.batchWechatPlugins("install", instanceIds, version);
    },
    async batchUninstallWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("uninstall", instanceIds);
    },
    async batchUpgradeWechatPlugins(instanceIds: string[], version = "") {
      return this.batchWechatPlugins("upgrade", instanceIds, version);
    },
    async batchReinstallWechatPlugins(instanceIds: string[], version = "") {
      return this.batchWechatPlugins("reinstall", instanceIds, version);
    },
    async batchWechatPlugins(action: "check" | "install" | "uninstall" | "upgrade" | "reinstall", instanceIds: string[], version = "") {
      const response = await api<{ plugins: WechatPluginBatchItem[] }>(`/api/admin/wechat-plugins/${action}`, {
        method: "POST",
        ...jsonBody({ instanceIds, version })
      });
      const next = { ...this.wechatPluginStatusByInstanceId };
      for (const item of response.plugins) {
        next[item.instanceId] = withWechatPluginVersion(item.plugin, this.wechatPluginVersions);
      }
      this.wechatPluginStatusByInstanceId = next;
      return response.plugins;
    },
    applyWechatPluginVersions(versions: PublicWechatPluginVersions) {
      const next = { ...this.wechatPluginStatusByInstanceId };
      for (const [instanceId, plugin] of Object.entries(next)) {
        next[instanceId] = withWechatPluginVersion(plugin, versions);
      }
      this.wechatPluginStatusByInstanceId = next;
    },
    async loadWechatLinks(params: { mode?: string; status?: string; phone?: string; page?: number; pageSize?: number } = {}) {
      const search = new URLSearchParams();
      if (params.mode) search.set("mode", params.mode);
      if (params.status) search.set("status", params.status);
      if (params.phone) search.set("phone", params.phone);
      search.set("page", String(params.page || 1));
      search.set("pageSize", String(params.pageSize || 20));
      return api<WechatBindLinkPage>(`/api/admin/wechat-bind-links?${search.toString()}`);
    },
    async loadWechatLinkDetail(token: string) {
      const response = await api<{ link: PublicWechatBindLink }>(`/api/admin/wechat-bind-links/${encodeURIComponent(token)}`);
      return response.link;
    },
    async revokeWechatLink(token: string) {
      const response = await api<{ link: PublicWechatBindLink }>(`/api/admin/wechat-bind-links/${encodeURIComponent(token)}/revoke`, {
        method: "POST"
      });
      return response.link;
    },
    async retryWechatLinkCleanup(token: string) {
      const response = await api<{ link: PublicWechatBindLink }>(`/api/admin/wechat-bind-links/${encodeURIComponent(token)}/retry-cleanup`, {
        method: "POST"
      });
      return response.link;
    },
    async cancelWechatLinkCleanup(token: string) {
      const response = await api<{ link: PublicWechatBindLink }>(`/api/admin/wechat-bind-links/${encodeURIComponent(token)}/cancel-cleanup`, {
        method: "POST"
      });
      return response.link;
    },
    async findBindingByPhone(phone: string) {
      const response = await api<{ binding: WechatBindingLookup | null }>(`/api/admin/wechat-bindings?phone=${encodeURIComponent(phone)}`);
      return response.binding;
    },
    async searchBindingsByPhone(phone: string) {
      const response = await api<{ bindings: WechatBindingLookup[] }>(
        `/api/admin/wechat-bindings/search?phone=${encodeURIComponent(phone)}`
      );
      return response.bindings;
    },
    async createPreset(payload: Record<string, unknown>) {
      const response = await api<{ preset: PublicModelPreset }>("/api/admin/model-presets", {
        method: "POST",
        ...jsonBody(payload)
      });
      await this.reloadPresets(response.preset.id);
    },
    async updatePreset(presetId: string, payload: Record<string, unknown>) {
      const response = await api<ModelPresetResponse>(`/api/admin/model-presets/${presetId}`, {
        method: "PUT",
        ...jsonBody(payload)
      });
      await this.reloadPresets(response.preset.id);
      return response;
    },
    async getPresetUsage(presetId: string) {
      const response = await api<{ usage: ModelPresetUsage }>(`/api/admin/model-presets/${presetId}/usage`);
      return response.usage;
    },
    async deletePreset(presetId: string) {
      const response = await api<ModelPresetDeleteResult>(`/api/admin/model-presets/${presetId}`, { method: "DELETE" });
      await this.reloadPresets();
      return response;
    },
    async setDefaultPreset(presetId: string) {
      await api<{ ok: boolean }>(`/api/admin/model-presets/${presetId}/default`, { method: "POST" });
      await this.reloadPresets(presetId);
    },
    async reloadPresets(selectedId = "") {
      const response = await api<{ presets: PublicModelPreset[] }>("/api/model-presets");
      this.presets = response.presets;
      return selectedId;
    },
    applyEvent(event: AppEvent) {
      if (event.type === "instance.updated") {
        this.upsert((event.payload as { instance: PublicInstance }).instance);
        void this.loadWechatUsers();
      }
      if (event.type === "admin.instances.updated") {
        void Promise.all([this.loadInstances(), this.loadWechatUsers()]);
      }
      if (event.type === "instance.provisioning.updated") {
        const payload = event.payload as { instanceId: string; provisioning: PublicInstanceProvisioning };
        this.patchInstance(payload.instanceId, { provisioning: payload.provisioning });
      }
      if (event.type === "instance.stats.updated") {
        const payload = event.payload as { instanceId: string; stats: InstanceStats };
        this.statsByInstanceId[payload.instanceId] = payload.stats;
      }
      if (event.type === "wechat.binding.updated") {
        const payload = event.payload as { instanceId: string; binding: PublicWechatBinding };
        this.patchInstance(payload.instanceId, { wechatBinding: payload.binding });
        void this.loadWechatUsers();
      }
      if (event.type === "wechat.plugin.updated") {
        const payload = event.payload as { instanceId: string; plugin: PublicWechatPluginStatus };
        this.wechatPluginStatusByInstanceId = {
          ...this.wechatPluginStatusByInstanceId,
          [payload.instanceId]: withWechatPluginVersion(payload.plugin, this.wechatPluginVersions)
        };
      }
      if (event.type === "openviking.plugin.updated") {
        const payload = event.payload as { instanceId: string; plugin: PublicOpenVikingPluginStatus };
        this.openVikingPluginStatusByInstanceId = {
          ...this.openVikingPluginStatusByInstanceId,
          [payload.instanceId]: withPluginVersion(payload.plugin, this.openVikingPluginVersions)
        };
      }
      if (event.type === "miniapp.bridge.plugin.updated") {
        const payload = event.payload as { instanceId: string; plugin: PublicApiChannelPluginStatus };
        this.miniappBridgePluginStatusByInstanceId = {
          ...this.miniappBridgePluginStatusByInstanceId,
          [payload.instanceId]: withPluginVersion(payload.plugin, this.miniappBridgePluginVersions)
        };
      }
      if (event.type === "workspace.file.plugin.updated") {
        const payload = event.payload as { instanceId: string; plugin: PublicApiChannelPluginStatus };
        this.workspaceFilePluginStatusByInstanceId = {
          ...this.workspaceFilePluginStatusByInstanceId,
          [payload.instanceId]: withPluginVersion(payload.plugin, this.workspaceFilePluginVersions)
        };
      }
      if (event.type === "wechat.bindLink.updated") {
        const payload = event.payload as { token: string; link: PublicWechatBindLink };
        this.wechatBindLinkByToken = {
          ...this.wechatBindLinkByToken,
          [payload.token]: payload.link
        };
      }
      if (event.type === "modelAuth.updated") {
        const payload = event.payload as { instanceId: string; modelAuth: PublicInstanceModelAuth };
        this.patchInstance(payload.instanceId, { modelAuth: payload.modelAuth });
      }
      if (event.type === "runnerImage.updated") {
        const payload = event.payload as { image: RunnerImageStatus };
        this.runnerImage = payload.image;
      }
    },
    upsert(instance: PublicInstance) {
      const index = this.instances.findIndex((item) => item.id === instance.id);
      if (index >= 0) {
        this.instances[index] = instance;
      } else {
        this.instances.unshift(instance);
      }
    },
    patchInstance(instanceId: string, patch: Partial<PublicInstance>) {
      const index = this.instances.findIndex((item) => item.id === instanceId);
      if (index < 0) return;
      this.instances[index] = { ...this.instances[index], ...patch };
    }
  }
});

function withWechatPluginVersion(
  plugin: PublicWechatPluginStatus,
  versions: PublicWechatPluginVersions
): PublicWechatPluginStatus {
  return withPluginVersion(plugin, versions);
}

function withPluginVersion<T extends PublicWechatPluginStatus>(
  plugin: T,
  versions: PublicWechatPluginVersions
): T {
  if (!plugin.installed || !plugin.currentVersion || !versions.latest) {
    return plugin;
  }
  return {
    ...plugin,
    latestVersion: versions.latest,
    upgradable: compareVersion(versions.latest, plugin.currentVersion) > 0
  };
}

function compareVersion(left: string, right: string) {
  const leftParts = versionCore(left).split(".");
  const rightParts = versionCore(right).split(".");
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const diff = numericPart(leftParts[index]) - numericPart(rightParts[index]);
    if (diff !== 0) return diff;
  }
  return versionQualifierRank(left) - versionQualifierRank(right);
}

function versionCore(version: string) {
  const normalized = version || "";
  const dash = normalized.indexOf("-");
  const plus = normalized.indexOf("+");
  const separators = [dash, plus].filter((index) => index >= 0);
  return separators.length === 0 ? normalized : normalized.slice(0, Math.min(...separators));
}

function numericPart(value: string | undefined) {
  const parsed = Number.parseInt(value || "0", 10);
  return Number.isFinite(parsed) ? parsed : 0;
}

function versionQualifierRank(version: string) {
  return version.includes("-") ? 0 : 1;
}
