import { defineStore } from "pinia";
import { api, jsonBody } from "../api/http";
import type {
  AppEvent,
  InstanceStats,
  ModelPresetSyncResult,
  ModelPresetUsage,
  ModelProviderDefinition,
  PublicOpenVikingPluginStatus,
  PublicOpenVikingPluginVersions,
  PublicOpenVikingSettings,
  PublicInstance,
  PublicInstanceModelAuth,
  PublicInstanceProvisioning,
  PublicModelPreset,
  PublicWechatBindLink,
  PublicWechatBinding,
  PublicWechatPluginStatus,
  PublicWechatPluginVersions,
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

export const useAdminStore = defineStore("admin", {
  state: () => ({
    instances: [] as PublicInstance[],
    presets: [] as PublicModelPreset[],
    providers: [] as ModelProviderDefinition[],
    runnerImage: null as RunnerImageStatus | null,
    serverLogs: "",
    statsByInstanceId: {} as Record<string, InstanceStats | null>,
    wechatPluginStatusByInstanceId: {} as Record<string, PublicWechatPluginStatus>,
    wechatPluginVersions: { latest: "", versions: [] } as PublicWechatPluginVersions,
    openVikingSettings: null as PublicOpenVikingSettings | null,
    openVikingPluginStatusByInstanceId: {} as Record<string, PublicOpenVikingPluginStatus>,
    openVikingPluginVersions: { latest: "", versions: [] } as PublicOpenVikingPluginVersions,
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
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/wechat-unbind`, { method: "POST" });
      this.upsert(response.instance);
      return response;
    },
    async saveWechatRemark(instanceId: string, accountId: string, remark: string) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}`, {
        method: "PUT",
        ...jsonBody({ remark })
      });
      this.upsert(response.instance);
    },
    async deleteWechatAccount(instanceId: string, accountId: string) {
      const response = await api<InstanceResponse>(`/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}`, {
        method: "DELETE"
      });
      this.upsert(response.instance);
      return response;
    },
    async restartWechatAccountChannel(instanceId: string, accountId: string) {
      const response = await api<{ account: WechatChannelRestartItem }>(
        `/api/admin/instances/${instanceId}/wechat-accounts/${encodeURIComponent(accountId)}/restart-channel`,
        { method: "POST" }
      );
      await this.loadInstances();
      return response.account;
    },
    async batchRestartWechatAccountChannels(accounts: Array<{ instanceId: string; accountId: string }>) {
      const response = await api<{ accounts: WechatChannelRestartItem[] }>("/api/admin/wechat-accounts/restart-channel", {
        method: "POST",
        ...jsonBody({ accounts })
      });
      await this.loadInstances();
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
    async loadOpenVikingPluginVersions() {
      const response = await api<{ versions: PublicOpenVikingPluginVersions }>("/api/admin/openviking-plugins/versions");
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
      await api<{ ok: boolean }>(`/api/admin/model-presets/${presetId}`, { method: "DELETE" });
      await this.reloadPresets();
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
      }
      if (event.type === "admin.instances.updated") {
        void this.loadInstances();
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
