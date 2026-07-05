export interface PublicAdmin {
  id: string;
  email: string;
  name: string;
  role: "admin";
  mustChangePassword: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PublicModelPreset {
  id: string;
  name: string;
  isDefault: boolean;
  isConfigured: boolean;
  providerKey: string;
  providerId: string;
  modelId: string;
  apiMode: string;
  authType: string;
  authProviderId: string;
  authMethodId: string;
  baseUrl: string;
  contextWindow: number;
  maxTokens: number;
  hasBaseUrl: boolean;
  hasApiKey: boolean;
  createdAt: string;
}

export interface ModelProviderDefinition {
  key: string;
  label: string;
  providerId: string;
  authType: string;
  authProviderId: string;
  authMethodId: string;
  apiMode: string;
  defaultModelId: string;
  defaultBaseUrl: string;
  supportsInteractiveAuth: boolean;
  forceRemoteOAuth: boolean;
  fields: ModelProviderField[];
}

export interface ModelProviderFieldOption {
  value: string;
  label: string;
}

export interface ModelProviderField {
  name: string;
  label: string;
  type: "text" | "password" | "select" | string;
  required?: boolean;
  placeholder?: string;
  options?: ModelProviderFieldOption[];
}

export interface PublicInstanceProvisioning {
  status: string;
  percent: number;
  stage: string;
  message: string;
  gatewayStartedAt?: string | null;
  updatedAt?: string | null;
}

export interface PublicInstanceModel {
  presetId: string;
  providerKey: string;
  providerId: string;
  modelId: string;
  apiMode: string;
  authType: string;
  authProviderId: string;
  authMethodId: string;
  baseUrl: string;
  apiKey: string;
  extra: Record<string, unknown>;
  contextWindow: number;
  maxTokens: number;
}

export interface PublicInstanceModelAuth {
  status: string;
  updatedAt?: string | null;
  message: string;
  outputSnippet: string;
  authUrl: string;
  promptLabel: string;
  needsInput: boolean;
}

export interface PublicWechatPairedAccount {
  accountId: string;
  phone: string;
  wechatUserId: string;
  openVikingUserId: string;
  remark: string;
  baseUrl: string;
  savedAt?: string | null;
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

export interface PublicWechatBinding {
  status: string;
  updatedAt?: string | null;
  qrExpiresAt?: string | null;
  qrExpired: boolean;
  qrMode?: string | null;
  qrPayload: string;
  qrLink: string;
  outputSnippet: string;
  pairedAccounts: PublicWechatPairedAccount[];
  runtimeReady: boolean;
  runtimeStatus: string;
  runtimeMessage: string;
  runtimeUpdatedAt?: string | null;
}

export interface PublicInstance {
  id: string;
  name: string;
  slug: string;
  status: string;
  port: number;
  dashboardUrl: string;
  containerName: string;
  gatewayToken: string;
  createdAt: string;
  updatedAt: string;
  provisioning: PublicInstanceProvisioning;
  model: PublicInstanceModel | null;
  models: PublicInstanceModel[];
  modelAuth: PublicInstanceModelAuth;
  plugins: Record<string, unknown>;
  wechatBinding: PublicWechatBinding;
}

export interface PublicWechatBindLink {
  token: string;
  mode: "new" | "existing" | string;
  status: string;
  phone: string;
  instanceId: string;
  instanceName: string;
  qrMode?: string | null;
  qrPayload: string;
  qrLink: string;
  qrExpiresAt?: string | null;
  qrExpired: boolean;
  message: string;
  expiresAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  statusLabel: string;
  modeLabel: string;
  bindLink: string;
}

export interface PublicWechatPluginStatus {
  installed: boolean;
  currentVersion: string;
  latestVersion: string;
  upgradable: boolean;
  status: string;
  message: string;
  outputSnippet: string;
  updatedAt: string;
}

export interface PublicWechatPluginVersions {
  latest: string;
  versions: string[];
}

export type PublicOpenVikingPluginStatus = PublicWechatPluginStatus;

export type PublicOpenVikingPluginVersions = PublicWechatPluginVersions;

export type PublicApiChannelPluginStatus = PublicWechatPluginStatus;

export type PublicApiChannelPluginVersions = PublicWechatPluginVersions;

export interface PublicMiniappClient {
  appId: string;
  appSecret?: string | null;
  appSecretPreview: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  created: boolean;
}

export interface PublicSkillRepository {
  id: string;
  name: string;
  repoUrl: string;
  branch: string;
  authType: "none" | "token" | string;
  accessToken?: string | null;
  tokenPreview: string;
  hasToken: boolean;
  lastCommitSha: string;
  lastPullStatus: string;
  lastPullMessage: string;
  lastPulledAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PublicSkillDefinition {
  id: string;
  repositoryId: string;
  repositoryName: string;
  skillName: string;
  originalName: string;
  relativePath: string;
  description: string;
  contentHash: string;
  warnings: string[];
  syncable: boolean;
  lastCommitSha: string;
  createdAt: string;
  updatedAt: string;
}

export interface PublicSkillInstanceSync {
  instanceId: string;
  skillName: string;
  skillId: string;
  repositoryId: string;
  sourceCommitSha: string;
  status: string;
  message: string;
  syncedAt?: string | null;
  updatedAt: string;
}

export interface SkillSyncResult {
  skillId: string;
  skillName: string;
  instanceId: string;
  instanceName: string;
  status: string;
  message: string;
  syncedAt: string;
}

export interface PublicOpenVikingSettings {
  baseUrl: string;
  trustedModeEnabled: boolean;
  accountId: string;
  pluginPackage: string;
  rootApiKeyConfigured: boolean;
  rootApiKeyFingerprint: string;
  saltConfigured: boolean;
  saltSource: string;
  saltFingerprint: string;
  updatedAt?: string | null;
}

export interface WechatBindLinkPage {
  links: PublicWechatBindLink[];
  total: number;
  page: number;
  pageSize: number;
}

export interface WechatBindingLookup {
  accountId: string;
  phone: string;
  instanceId: string;
  wechatUserId: string;
  remark: string;
  baseUrl: string;
  savedAt: string;
  boundAt: string;
  updatedAt: string;
}

export interface ModelPresetUsageInstance {
  id: string;
  name: string;
  status: string;
  modelIndexes: number[];
}

export interface ModelPresetUsage {
  instances: ModelPresetUsageInstance[];
}

export interface ModelPresetSyncResult {
  requested: boolean;
  affectedInstances: number;
  updatedInstanceIds: string[];
  restartedInstanceIds: string[];
}

export interface InstanceStats {
  cpuPercent?: number;
  memoryUsageBytes?: number;
  memoryLimitBytes?: number;
  memoryPercent?: number;
}

export interface AppEvent<T = Record<string, unknown>> {
  type: string;
  traceId: string;
  occurredAt: string;
  payload: T;
}
