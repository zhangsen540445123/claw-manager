import { defineStore } from "pinia";
import { api, jsonBody } from "../api/http";
import type {
  AppEvent,
  InstanceStats,
  ModelPresetSyncResult,
  ModelPresetUsage,
  ModelProviderDefinition,
  PublicInstance,
  PublicInstanceModelAuth,
  PublicInstanceProvisioning,
  PublicModelPreset,
  PublicWechatBindLink,
  PublicWechatBinding,
  PublicWechatPluginStatus,
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

interface ModelPresetResponse {
  preset: PublicModelPreset;
  sync?: ModelPresetSyncResult;
}

interface WechatPluginBatchItem {
  instanceId: string;
  plugin: PublicWechatPluginStatus;
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
        [instanceId]: response.plugin
      };
      return response.plugin;
    },
    async installWechatPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/install`,
        { method: "POST" }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: response.plugin
      };
      return response.plugin;
    },
    async uninstallWechatPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/uninstall`,
        { method: "POST" }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: response.plugin
      };
      return response.plugin;
    },
    async upgradeWechatPlugin(instanceId: string) {
      const response = await api<{ plugin: PublicWechatPluginStatus }>(
        `/api/admin/instances/${instanceId}/wechat-plugin/upgrade`,
        { method: "POST" }
      );
      this.wechatPluginStatusByInstanceId = {
        ...this.wechatPluginStatusByInstanceId,
        [instanceId]: response.plugin
      };
      return response.plugin;
    },
    async batchCheckWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("check", instanceIds);
    },
    async batchInstallWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("install", instanceIds);
    },
    async batchUninstallWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("uninstall", instanceIds);
    },
    async batchUpgradeWechatPlugins(instanceIds: string[]) {
      return this.batchWechatPlugins("upgrade", instanceIds);
    },
    async batchWechatPlugins(action: "check" | "install" | "uninstall" | "upgrade", instanceIds: string[]) {
      const response = await api<{ plugins: WechatPluginBatchItem[] }>(`/api/admin/wechat-plugins/${action}`, {
        method: "POST",
        ...jsonBody({ instanceIds })
      });
      const next = { ...this.wechatPluginStatusByInstanceId };
      for (const item of response.plugins) {
        next[item.instanceId] = item.plugin;
      }
      this.wechatPluginStatusByInstanceId = next;
      return response.plugins;
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
          [payload.instanceId]: payload.plugin
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
