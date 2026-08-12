import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { createTypingCallbacks } from "openclaw/plugin-sdk/channel-runtime";
import type { ChannelPlugin, OpenClawConfig, PluginRuntime } from "openclaw/plugin-sdk/core";

import { getApiConfigRuntime, type ApiConfigRuntime } from "./config-runtime.js";
import { clearApiOpenVikingTurn, registerApiOpenVikingTurn, runWithApiOpenVikingTurn } from "./openviking-handoff.js";

export type ApiSendMessageParams = {
  operation?: "chat";
  requestId?: string;
  agentId?: string;
  openVikingUserId?: string;
  openvikingUserId?: string;
  senderHash?: string;
  senderId?: string;
  conversationId?: string;
  conversationHash?: string;
  message?: string;
  wechatAccountId?: string;
  wechatPeerId?: string;
  metadata?: Record<string, unknown>;
};

export type ApiMsgContext = {
  Body: string;
  Text: string;
  From: string;
  To: string;
  AccountId: string;
  OriginatingChannel: "claw-manager-api";
  OriginatingTo: string;
  MessageSid: string;
  Timestamp: number;
  Provider: "claw-manager-api";
  ChatType: "direct";
  SessionKey?: string;
  SenderId: string;
  senderId: string;
  requesterSenderId: string;
  openVikingUserId: string;
  openvikingUserId: string;
  Metadata?: Record<string, unknown>;
};

type GatewaySendMessageContext = ApiSendMessageParams & {
  cfg?: OpenClawConfig;
  channelRuntime?: PluginRuntime["channel"];
  configRuntime?: ApiConfigRuntime;
  log?: ApiLogSink;
  onDelta?: ApiDeltaSink;
  onArtifact?: ApiArtifactSink;
};

type ApiLogSink = {
  debug?: (message: string) => void;
  info?: (message: string) => void;
  warn?: (message: string) => void;
  error?: (message: string) => void;
};

export type ApiGatewayStartContext = {
  cfg?: OpenClawConfig;
  channelRuntime?: PluginRuntime["channel"];
  configRuntime?: ApiConfigRuntime;
  abortSignal?: AbortSignal;
  log?: ApiLogSink;
  setStatus?: (next: Record<string, unknown>) => void;
};

function resolveApiConfigRuntime(explicit?: ApiConfigRuntime): ApiConfigRuntime | undefined {
  return explicit?.current ? explicit : getApiConfigRuntime();
}

type ApiQueueResponse = {
  ok: boolean;
  requestId: string;
  operation?: "chat";
  agentId?: string;
  messageId?: string;
  text?: string;
  error?: string;
  finishedAt: string;
};

type ApiDeltaSink = (text: string) => Promise<void> | void;
type ApiArtifactSink = (artifact: Record<string, unknown>) => Promise<void> | void;

type ApiQueueStreamEvent = {
  seq: number;
  type: "delta" | "artifact" | "heartbeat" | "done" | "error";
  text?: string;
  messageId?: string;
  error?: string;
  artifact?: Record<string, unknown>;
  createdAt: string;
};

const API_CHANNEL_ID = "claw-manager-api";
const API_ACCOUNT_ID = "global";
const API_QUEUE_POLL_MS = 200;
const API_HEARTBEAT_INTERVAL_MS = 1000;
const API_STREAM_HEARTBEAT_INTERVAL_MS = 15_000;
const API_STATUS_FILE = "status.json";
const USER_AGENT_ID_PATTERN = /^user_[0-9a-f]{32}$/;
const OPENVIKING_USER_ID_PATTERN = /^wx_[0-9a-f]{32}$/;
const REQUIRED_DENIED_TOOLS = ["write", "edit", "apply_patch", "exec", "process"] as const;
const DEFAULT_USER_AGENT_WORKSPACE_PRESET = {
  agentsMd: "# Claw Manager User Agent\n\nThis workspace belongs to one user across all connected channels.\n",
  soulMd: "# SOUL.md - User Agent\n\nBe concise, useful, and do not claim tool success when an operation failed.\n",
  identityMd: "# IDENTITY.md - User Agent\n\nClaw Manager cross-channel assistant.\n",
  toolsMd: "# TOOLS.md - User Agent\n\nUse configured tools according to their schemas.\n",
  heartbeatMd: "<!-- User Agent heartbeat tasks are disabled by default. -->\n",
  userMd: "# USER.md - User\n\nThis file may be customized by the user and is private to this Agent.\n",
};

function apiTraceId(requestId: string): string {
  return `cmtrace_${requestId.replaceAll("-", "")}`;
}

export async function reportApiTrace(params: {
  traceId: string;
  requestId: string;
  stage: string;
  status: "started" | "completed" | "failed";
  elapsedMs?: number;
  errorCode?: string;
  details?: Record<string, unknown>;
  env?: NodeJS.ProcessEnv;
  fetcher?: typeof fetch;
}): Promise<void> {
  const env = params.env ?? process.env;
  const baseUrl = trim(env.CLAW_MANAGER_INTERNAL_BASE_URL).replace(/\/+$/, "");
  const token = trim(env.OPENVIKING_BROKER_TOKEN);
  const instanceId = trim(env.OPENVIKING_OPENCLAW_INSTANCE_ID);
  if (!baseUrl || !token || !instanceId) return;
  try {
    const response = await (params.fetcher ?? fetch)(`${baseUrl}/api/internal/integration-traces/events`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${token}`,
        "X-CM-Trace-Id": params.traceId,
      },
      body: JSON.stringify({
        traceId: params.traceId,
        component: "api-channel",
        stage: params.stage,
        status: params.status,
        channel: "api",
        instanceId,
        requestId: params.requestId,
        elapsedMs: params.elapsedMs,
        errorCode: params.errorCode ?? "",
        details: params.details ?? {},
      }),
    });
    if (!response.ok) console.warn(`api trace report rejected traceId=${params.traceId} stage=${params.stage} status=${response.status}`);
  } catch {
    console.warn(`api trace report failed traceId=${params.traceId} stage=${params.stage}`);
  }
}

export function requestsImageGeneration(message: string): boolean {
  return imageRequestIntentReason(message) === "explicit_request";
}

export function imageRequestIntentReason(message: string): "explicit_request" | "negated" | "capability_question" | "none" {
  const normalized = trim(message);
  if (!normalized) return "none";
  if (/(不要|不用|无需|别|禁止).{0,8}(生图|生成|制作|画).{0,6}(图|图片|海报|卡片)/i.test(normalized)) {
    return "negated";
  }
  if (/^(你|系统|这个助手).{0,8}(支持|能否支持|是否支持).{0,8}(图片|生图).*[?？]?$/i.test(normalized)) {
    return "capability_question";
  }
  return /(生图|(?:生成|制作|画|绘制|设计|给我|来一张).{0,16}(图片|图像|海报|卡片|九宫格|图)|(?:图片|海报|卡片).{0,6}(生成|制作)|generate.{0,16}(image|poster)|create.{0,16}(image|poster))/i.test(normalized)
    ? "explicit_request"
    : "none";
}

export function startApiStreamHeartbeat(
  writer: () => Promise<void> | void,
  intervalMs = API_STREAM_HEARTBEAT_INTERVAL_MS,
  onError?: (error: unknown) => void,
): () => Promise<void> {
  let writeChain = Promise.resolve();
  const timer = setInterval(() => {
    writeChain = writeChain
      .catch(() => undefined)
      .then(() => Promise.resolve(writer()))
      .catch((error) => onError?.(error));
  }, intervalMs);
  return async () => {
    clearInterval(timer);
    await writeChain.catch(() => undefined);
  };
}

type ApiQueueMonitorState = {
  activeUsers: Set<string>;
  activeTasks: Set<Promise<void>>;
};

export type ApiAssistantAgentEvent = {
  stream?: unknown;
  runId?: unknown;
  sessionKey?: unknown;
  seq?: unknown;
  data?: unknown;
};

type ApiAgentEventStreamState = {
  requestId: string;
  runId: string;
  sessionKey: string;
  onDelta: ApiDeltaSink;
  onArtifact?: ApiArtifactSink;
  log?: ApiLogSink;
  startedAtMs: number;
  emittedText: string;
  agentEventDeltaCount: number;
  deliverDeltaCount: number;
  firstAgentEventDeltaAtMs?: number;
  firstDeltaAtMs?: number;
  seenAgentEventKeys: Set<string>;
  seenArtifactIds: Set<string>;
  writeChain: Promise<void>;
};

const activeApiAgentEventStreams = new Map<string, ApiAgentEventStreamState>();
const activeApiAgentEventStreamsByRunId = new Map<string, ApiAgentEventStreamState>();
let apiDynamicAgentBindingMutationChain: Promise<unknown> = Promise.resolve();

export function registerApiAgentEventStream(params: {
  requestId?: string;
  runId?: string;
  sessionKey?: string;
  onDelta?: ApiDeltaSink;
  onArtifact?: ApiArtifactSink;
  log?: ApiLogSink;
}): ApiAgentEventStreamState | undefined {
  const sessionKey = trim(params.sessionKey);
  if (!sessionKey || !params.onDelta) {
    return undefined;
  }
  const state: ApiAgentEventStreamState = {
    requestId: trim(params.requestId) || "auto",
    runId: trim(params.runId) || trim(params.requestId) || "auto",
    sessionKey,
    onDelta: params.onDelta,
    onArtifact: params.onArtifact,
    log: params.log,
    startedAtMs: Date.now(),
    emittedText: "",
    agentEventDeltaCount: 0,
    deliverDeltaCount: 0,
    seenAgentEventKeys: new Set(),
    seenArtifactIds: new Set(),
    writeChain: Promise.resolve(),
  };
  activeApiAgentEventStreams.set(sessionKey, state);
  if (state.runId) {
    activeApiAgentEventStreamsByRunId.set(state.runId, state);
  }
  return state;
}

export function unregisterApiAgentEventStream(sessionKey?: string, state?: ApiAgentEventStreamState): void {
  const normalized = trim(sessionKey);
  if (!normalized) {
    return;
  }
  if (state && activeApiAgentEventStreams.get(normalized) !== state) {
    return;
  }
  const current = activeApiAgentEventStreams.get(normalized);
  activeApiAgentEventStreams.delete(normalized);
  if (current?.runId) {
    activeApiAgentEventStreamsByRunId.delete(current.runId);
  }
}

export function resetApiAgentEventStreamsForTest(): void {
  activeApiAgentEventStreams.clear();
  activeApiAgentEventStreamsByRunId.clear();
}

export async function handleApiAssistantAgentEvent(event: ApiAssistantAgentEvent): Promise<boolean> {
  const sessionKey = trim(event.sessionKey);
  const runId = trim(event.runId);
  const state = sessionKey
    ? activeApiAgentEventStreams.get(sessionKey)
    : (runId ? activeApiAgentEventStreamsByRunId.get(runId) : undefined);
  if (!state || !isRecord(event.data)) {
    return false;
  }
  if (runId && runId !== state.runId) {
    return false;
  }
  if (event.stream === "tool") {
    return handleArtifactToolEvent(event, state);
  }
  if (event.stream !== "assistant") return false;
  const seq = typeof event.seq === "number" && Number.isFinite(event.seq)
    ? String(event.seq)
    : (typeof event.seq === "string" && event.seq.trim() ? event.seq.trim() : "");
  if (seq) {
    const dedupeKey = `${runId || sessionKey}:${seq}`;
    if (state.seenAgentEventKeys.has(dedupeKey)) {
      return false;
    }
    state.seenAgentEventKeys.add(dedupeKey);
  }

  const text = stringValue(event.data.text);
  const explicitDelta = stringValue(event.data.delta);
  let delta = "";
  let authoritativeCumulativeSuffix = false;
  if (text) {
    if (text.startsWith(state.emittedText)) {
      const suffix = text.slice(state.emittedText.length);
      const explicit = explicitDelta ? trimOverlappingDelta(state.emittedText, explicitDelta) : "";
      delta = shouldPreferExplicitAgentDelta(state.emittedText, suffix, explicit) ? explicit : suffix;
      authoritativeCumulativeSuffix = true;
    } else {
      const explicit = explicitDelta ? trimOverlappingDelta(state.emittedText, explicitDelta) : "";
      if (explicit) {
        delta = explicit;
      } else {
        const overlapTrimmed = trimOverlappingDelta(state.emittedText, text);
        if (overlapTrimmed && overlapTrimmed.length < text.length) {
          delta = overlapTrimmed;
        } else {
          state.log?.warn?.(
            `[${API_ACCOUNT_ID}] api agent-event ignored non-monotonic cumulative text ` +
              `requestId=${state.requestId} sessionKeyHash=${hashPreview(state.sessionKey)}`,
          );
          return false;
        }
      }
    }
  } else {
    delta = explicitDelta;
  }
  if (!delta) {
    return false;
  }
  if (!authoritativeCumulativeSuffix && delta.length > 1) {
    delta = trimOverlappingDelta(state.emittedText, delta);
  }
  if (!delta) {
    return false;
  }

  state.emittedText += delta;
  state.agentEventDeltaCount += 1;
  if (!state.firstAgentEventDeltaAtMs) {
    state.firstAgentEventDeltaAtMs = Date.now();
    state.log?.info?.(
      `[${API_ACCOUNT_ID}] api agent-event first delta requestId=${state.requestId} ` +
        `sessionKeyHash=${hashPreview(state.sessionKey)} firstDeltaMs=${state.firstAgentEventDeltaAtMs - state.startedAtMs}`,
    );
  }
  if (!state.firstDeltaAtMs) {
    state.firstDeltaAtMs = Date.now();
  }

  try {
    state.writeChain = state.writeChain
      .catch(() => undefined)
      .then(() => Promise.resolve(state.onDelta(delta)));
    await state.writeChain;
    return true;
  } catch (error) {
    state.log?.warn?.(
      `[${API_ACCOUNT_ID}] api agent-event delta write failed requestId=${state.requestId}: ${errorMessage(error)}`,
    );
    return false;
  }
}

async function writeOpenVikingHandoffForTurn(params: {
  sessionKey?: string;
  agentId?: string;
  openVikingUserId?: string;
  log?: ApiLogSink;
  cmTraceId?: string;
  requestId?: string;
}): Promise<void> {
  const secret = trim(process.env.OPENVIKING_IDENTITY_HASH_SECRET);
  if (!secret) {
    throw new Error("API_TURN_IDENTITY_MISSING");
  }
  const wrote = await registerApiOpenVikingTurn({
    sessionKey: params.sessionKey,
    agentId: params.agentId,
    openVikingUserId: params.openVikingUserId,
    secret,
    cmTraceId: params.cmTraceId,
    requestId: params.requestId,
  });
  if (!wrote) {
    throw new Error("API_TURN_IDENTITY_MISSING");
  }
  params.log?.info?.(`[${API_ACCOUNT_ID}] openviking turn registered`);
}

export function buildApiInboundContext(params: ApiSendMessageParams): ApiMsgContext {
  const message = trim(params.message);
  const openVikingUserId = trim(params.openVikingUserId) || trim(params.openvikingUserId);
  const senderHash = trim(params.senderHash);
  const senderId = trim(params.senderId) || (senderHash ? `api:${senderHash}` : "");
  const conversationHash = trim(params.conversationHash);
  if (!message) {
    throw new Error("message is required");
  }
  if (!openVikingUserId) {
    throw new Error("openVikingUserId is required");
  }
  if (!senderHash) {
    throw new Error("senderHash is required");
  }
  if (!conversationHash) {
    throw new Error("conversationHash is required");
  }
  const from = `api:${senderHash}`;
  const to = `api:${senderHash}`;
  return {
    Body: message,
    Text: message,
    From: from,
    To: to,
    AccountId: "global",
    OriginatingChannel: "claw-manager-api",
    OriginatingTo: to,
    MessageSid: `claw-manager-api-${randomUUID()}`,
    Timestamp: Date.now(),
    Provider: "claw-manager-api",
    ChatType: "direct",
    SessionKey: `api:${senderHash}:${conversationHash}`,
    SenderId: senderId,
    senderId,
    requesterSenderId: senderId,
    openVikingUserId,
    openvikingUserId: openVikingUserId,
    Metadata: params.metadata ?? {},
  };
}

export async function ensureApiUserAgentBinding(params: {
  cfg: OpenClawConfig;
  configRuntime?: ApiConfigRuntime;
  agentId: string;
  openVikingUserId: string;
  apiPeerId?: string;
  wechatAccountId?: string;
  wechatPeerId?: string;
  log?: ApiLogSink;
}): Promise<OpenClawConfig> {
  const agentId = requireUserAgentId(params.agentId);
  requireOpenVikingUserId(params.openVikingUserId);
  const current = params.configRuntime?.current?.() ?? params.cfg;
  const workspace = resolveHomePath(`~/.openclaw/workspace-${agentId}`);
  const agentDir = resolveHomePath(`~/.openclaw/agents/${agentId}/agent`);
  const binding = resolveRequestedUserAgentBinding(params);
  const currentAgents = isRecord(current.agents) && Array.isArray(current.agents.list)
    ? current.agents.list
    : [];
  const currentAgent = currentAgents.find((entry) => isRecord(entry) && entry.id === agentId);
  const currentBindings = Array.isArray(current.bindings) ? current.bindings : [];
  const agentReady = isRecord(currentAgent) && currentAgent.workspace === workspace &&
    currentAgent.agentDir === agentDir && hasRequiredApiToolPolicy(currentAgent);
  if (params.apiPeerId && !agentReady) {
    throw new Error("API_BINDING_NOT_READY");
  }
  if (agentReady &&
      (!binding || hasUniqueUserAgentBinding(currentBindings, agentId, binding))) {
    if (!params.apiPeerId) {
      await ensureApiAgentWorkspace(workspace);
    }
    return current as OpenClawConfig;
  }
  const mutateConfigFile = params.configRuntime?.mutateConfigFile;
  if (!mutateConfigFile) {
    throw new Error("CONFIG_RUNTIME_UNAVAILABLE");
  }
  if (params.apiPeerId && binding) {
    await serializeApiDynamicAgentBindingMutation(() => mutateConfigFile({
      base: "runtime",
      afterWrite: { mode: "auto" },
      mutate: (draft: Record<string, unknown>) => {
        const result = replaceUserAgentBinding(draft, agentId, binding);
        return { agentId, created: false, bound: result.bound };
      },
    }));
    return params.configRuntime?.current?.() ?? current;
  }
  const mutateDraft = async (draft: Record<string, unknown>) => {
    const agents = isRecord(draft.agents) ? draft.agents : {};
    const list = Array.isArray(agents.list) ? [...agents.list] : [];
    const existing = list.find((entry) => isRecord(entry) && entry.id === agentId);
    await ensureApiAgentWorkspace(workspace);
    await fs.mkdir(agentDir, { recursive: true });

    const agent: Record<string, unknown> = isRecord(existing) ? { ...existing } : { id: agentId };
    agent.id = agentId;
    agent.workspace = workspace;
    agent.agentDir = agentDir;
    const tools = isRecord(agent.tools) ? { ...agent.tools } : {};
    const denied = Array.isArray(tools.deny)
      ? tools.deny.filter((value): value is string => typeof value === "string")
      : [];
    tools.deny = [...new Set([...denied, ...REQUIRED_DENIED_TOOLS])];
    agent.tools = tools;
    draft.agents = {
      ...agents,
      list: existing
        ? list.map((entry) => isRecord(entry) && entry.id === agentId ? agent : entry)
        : [...list, agent],
    };
    const result = binding
      ? replaceUserAgentBinding(draft, agentId, binding)
      : { bound: false };
    return { agentId, created: !existing, bound: result.bound };
  };

  await serializeApiDynamicAgentBindingMutation(() => mutateConfigFile({
    base: "runtime",
    afterWrite: { mode: "auto" },
    mutate: mutateDraft,
  }));

  return params.configRuntime?.current?.() ?? current;
}

export function requirePersistedApiUserAgentBinding(params: {
  cfg: OpenClawConfig;
  agentId: string;
  apiPeerId: string;
}): OpenClawConfig {
  const agentId = requireUserAgentId(params.agentId);
  const agents = isRecord(params.cfg.agents) && Array.isArray(params.cfg.agents.list)
    ? params.cfg.agents.list
    : [];
  const agent = agents.find((entry) => isRecord(entry) && entry.id === agentId);
  const binding: UserAgentBindingMatch = {
    channel: API_CHANNEL_ID,
    accountId: API_ACCOUNT_ID,
    peer: { kind: "direct", id: trim(params.apiPeerId) },
  };
  const bindings = Array.isArray(params.cfg.bindings) ? params.cfg.bindings : [];
  const workspace = resolveHomePath(`~/.openclaw/workspace-${agentId}`);
  const agentDir = resolveHomePath(`~/.openclaw/agents/${agentId}/agent`);
  if (!isRecord(agent) || agent.workspace !== workspace || agent.agentDir !== agentDir ||
      !hasRequiredApiToolPolicy(agent) ||
      !bindings.some((entry) => isUserAgentBinding(entry, agentId, binding))) {
    throw new Error("API_BINDING_NOT_READY");
  }
  return params.cfg;
}

function requireUserAgentId(value?: string): string {
  const agentId = trim(value);
  if (!USER_AGENT_ID_PATTERN.test(agentId)) {
    throw new Error("agentId must match user_<32 lowercase hex>");
  }
  return agentId;
}

function requireOpenVikingUserId(value?: string): string {
  const openVikingUserId = trim(value);
  if (!OPENVIKING_USER_ID_PATTERN.test(openVikingUserId)) {
    throw new Error("openVikingUserId must match wx_<32 lowercase hex>");
  }
  return openVikingUserId;
}

type UserAgentBindingMatch = {
  channel: string;
  accountId: string;
  peer: { kind: "direct"; id: string };
};

function resolveRequestedUserAgentBinding(params: {
  apiPeerId?: string;
  wechatAccountId?: string;
  wechatPeerId?: string;
}): UserAgentBindingMatch | undefined {
  const apiPeerId = trim(params.apiPeerId);
  if (apiPeerId) {
    return { channel: API_CHANNEL_ID, accountId: API_ACCOUNT_ID, peer: { kind: "direct", id: apiPeerId } };
  }
  const accountId = trim(params.wechatAccountId);
  const peerId = trim(params.wechatPeerId);
  if (accountId || peerId) {
    if (!accountId || !peerId) {
      throw new Error("wechatAccountId and wechatPeerId must be provided together");
    }
    return { channel: "openclaw-weixin", accountId, peer: { kind: "direct", id: peerId } };
  }
  return undefined;
}

function isUserAgentBinding(value: unknown, agentId: string, match: UserAgentBindingMatch): boolean {
  if (!isRecord(value) || value.agentId !== agentId || !isRecord(value.match)) return false;
  return isSameUserAgentBindingMatch(value.match, match);
}

function hasUniqueUserAgentBinding(bindings: unknown[], agentId: string, match: UserAgentBindingMatch): boolean {
  let sameMatchCount = 0;
  let hasTargetBinding = false;
  for (const entry of bindings) {
    if (!isRecord(entry) || !isRecord(entry.match) || !isSameUserAgentBindingMatch(entry.match, match)) {
      continue;
    }
    sameMatchCount += 1;
    if (entry.agentId === agentId) {
      hasTargetBinding = true;
    }
  }
  return hasTargetBinding && sameMatchCount === 1;
}

function isSameUserAgentBindingMatch(value: Record<string, unknown>, match: UserAgentBindingMatch): boolean {
  if (value.channel !== match.channel ||
      !isRecord(value.peer) ||
      value.peer.kind !== "direct" ||
      value.peer.id !== match.peer.id) {
    return false;
  }
  // A WeChat login can issue a new accountId for the same peer. Routing ownership is
  // therefore peer-scoped, while API bindings remain account-scoped.
  return match.channel === "openclaw-weixin" || value.accountId === match.accountId;
}

function replaceUserAgentBinding(
  draft: Record<string, unknown>,
  agentId: string,
  binding: UserAgentBindingMatch,
): { bound: boolean; displacedAgentIds: string[] } {
  const currentBindings = Array.isArray(draft.bindings) ? draft.bindings : [];
  const nextBindings: unknown[] = [];
  const displacedAgentIds = new Set<string>();

  for (const entry of currentBindings) {
    if (!isRecord(entry) || !isRecord(entry.match)) {
      nextBindings.push(entry);
      continue;
    }
    if (isSameUserAgentBindingMatch(entry.match, binding)) {
      if (typeof entry.agentId === "string" && entry.agentId !== agentId) {
        displacedAgentIds.add(entry.agentId);
      }
      continue;
    }
    nextBindings.push(entry);
  }

  nextBindings.push({ agentId, match: binding });
  draft.bindings = nextBindings;
  return { bound: true, displacedAgentIds: [...displacedAgentIds] };
}

export type ReplaceUserAgentConflict = {
  agentId: string;
  channel: string;
  accountId: string;
  peerId: string;
};

export type ReplaceUserAgentResult = {
  persisted: boolean;
  runtimeApplied: boolean;
  bindingCreated: boolean;
  displacedAgentIds: string[];
  conflictingBindings: ReplaceUserAgentConflict[];
};

export type DeleteUserAgentResult = {
  persisted: boolean;
  runtimeApplied: boolean;
  agentRemoved: boolean;
  removedBindings: ReplaceUserAgentConflict[];
  conflictingBindings: ReplaceUserAgentConflict[];
};

/** Atomically replace one user's WeChat route and remove only the explicitly displaced Agent. */
export async function replaceApiUserAgent(params: {
  cfg: OpenClawConfig;
  configRuntime?: ApiConfigRuntime;
  newAgentId: string;
  openVikingUserId: string;
  wechatAccountId: string;
  wechatPeerId: string;
  oldAgentId?: string;
  apiPeerIds?: string[];
}): Promise<ReplaceUserAgentResult> {
  const newAgentId = requireUserAgentId(params.newAgentId);
  const oldAgentId = trim(params.oldAgentId);
  if (oldAgentId && !USER_AGENT_ID_PATTERN.test(oldAgentId)) throw new Error("invalid oldAgentId");
  requireOpenVikingUserId(params.openVikingUserId);
  const binding = resolveRequestedUserAgentBinding({
    wechatAccountId: params.wechatAccountId,
    wechatPeerId: params.wechatPeerId,
  });
  if (!binding) throw new Error("wechatAccountId and wechatPeerId must be provided together");
  const runtime = params.configRuntime;
  if (!runtime?.current || !runtime.mutateConfigFile) throw new Error("CONFIG_RUNTIME_UNAVAILABLE");

  const current = runtime.current();
  const bindings = Array.isArray(current.bindings) ? current.bindings : [];
  const apiPeerIds = new Set((params.apiPeerIds ?? []).map(trim).filter(Boolean));
  const removedIndexes = new Set<number>();
  const displacedAgentIds = new Set<string>();

  bindings.forEach((entry, index) => {
    if (!isRecord(entry) || !isRecord(entry.match)) return;
    const owner = typeof entry.agentId === "string" ? trim(entry.agentId) : "";
    if (isSameUserAgentBindingMatch(entry.match, binding)) {
      removedIndexes.add(index);
      if (owner && owner !== newAgentId) displacedAgentIds.add(owner);
      return;
    }
    if (oldAgentId && owner === oldAgentId && isApiPeerBinding(entry.match, apiPeerIds)) {
      removedIndexes.add(index);
    }
  });

  const conflictingBindings: ReplaceUserAgentConflict[] = [];
  for (const displaced of displacedAgentIds) {
    if (!oldAgentId || displaced !== oldAgentId) {
      conflictingBindings.push({ agentId: displaced, channel: binding.channel, accountId: binding.accountId, peerId: binding.peer.id });
    }
  }
  if (oldAgentId && oldAgentId !== newAgentId) {
    bindings.forEach((entry, index) => {
      if (removedIndexes.has(index) || !isRecord(entry) || entry.agentId !== oldAgentId || !isRecord(entry.match)) return;
      conflictingBindings.push(bindingConflict(oldAgentId, entry.match));
    });
  }
  if (conflictingBindings.length > 0) {
    return { persisted: false, runtimeApplied: false, bindingCreated: false, displacedAgentIds: [...displacedAgentIds], conflictingBindings };
  }

  const workspace = resolveHomePath(`~/.openclaw/workspace-${newAgentId}`);
  const agentDir = resolveHomePath(`~/.openclaw/agents/${newAgentId}/agent`);
  await ensureApiAgentWorkspace(workspace);
  await fs.mkdir(agentDir, { recursive: true });
  await serializeApiDynamicAgentBindingMutation(() => runtime.mutateConfigFile!({
    base: "runtime",
    afterWrite: { mode: "auto" },
    mutate: (draft: Record<string, unknown>) => {
      const draftAgents = isRecord(draft.agents) ? draft.agents : {};
      const list = Array.isArray(draftAgents.list) ? [...draftAgents.list] : [];
      const existing = list.find((entry) => isRecord(entry) && entry.id === newAgentId);
      const agent: Record<string, unknown> = isRecord(existing) ? { ...existing } : { id: newAgentId };
      agent.id = newAgentId;
      agent.workspace = workspace;
      agent.agentDir = agentDir;
      const tools = isRecord(agent.tools) ? { ...agent.tools } : {};
      const denied = Array.isArray(tools.deny) ? tools.deny.filter((value): value is string => typeof value === "string") : [];
      tools.deny = [...new Set([...denied, ...REQUIRED_DENIED_TOOLS])];
      agent.tools = tools;
      const nextAgentList = existing
        ? list.map((entry) => isRecord(entry) && entry.id === newAgentId ? agent : entry)
        : [...list, agent];
      draft.agents = {
        ...draftAgents,
        list: oldAgentId && oldAgentId !== newAgentId
          ? nextAgentList.filter((entry) => !isRecord(entry) || entry.id !== oldAgentId)
          : nextAgentList,
      };
      const draftBindings = Array.isArray(draft.bindings) ? draft.bindings : [];
      draft.bindings = draftBindings.filter((entry) => {
        if (!isRecord(entry) || !isRecord(entry.match)) return true;
        if (isSameUserAgentBindingMatch(entry.match, binding)) return false;
        return !(oldAgentId && entry.agentId === oldAgentId && isApiPeerBinding(entry.match, apiPeerIds));
      });
      (draft.bindings as unknown[]).push({ agentId: newAgentId, match: binding });
      return { bindingCreated: true, displacedAgentIds: [...displacedAgentIds] };
    },
  }));

  const applied = runtime.current();
  const appliedBindings = Array.isArray(applied.bindings) ? applied.bindings : [];
  const runtimeApplied = hasUniqueUserAgentBinding(appliedBindings, newAgentId, binding);
  return {
    persisted: runtimeApplied,
    runtimeApplied,
    bindingCreated: runtimeApplied,
    displacedAgentIds: [...displacedAgentIds],
    conflictingBindings: [],
  };
}

/** Remove one user Agent and only bindings whose ownership is explicitly proven by the caller. */
export async function deleteApiUserAgent(params: {
  cfg: OpenClawConfig;
  configRuntime?: ApiConfigRuntime;
  agentId: string;
  wechatAccountIds?: string[];
  wechatPeerIds?: string[];
  apiPeerIds?: string[];
  protectedAgentIds?: string[];
}): Promise<DeleteUserAgentResult> {
  const agentId = requireUserAgentId(params.agentId);
  const runtime = params.configRuntime;
  if (!runtime?.current || !runtime.mutateConfigFile) throw new Error("CONFIG_RUNTIME_UNAVAILABLE");

  const wechatAccountIds = new Set((params.wechatAccountIds ?? []).map(trim).filter(Boolean));
  const wechatPeerIds = new Set((params.wechatPeerIds ?? []).map(trim).filter(Boolean));
  const apiPeerIds = new Set((params.apiPeerIds ?? []).map(trim).filter(Boolean));
  const protectedAgentIds = new Set(
    (params.protectedAgentIds ?? [])
      .map(trim)
      .filter((value) => value !== agentId && USER_AGENT_ID_PATTERN.test(value)),
  );
  const current = runtime.current();
  const bindings = Array.isArray(current.bindings) ? current.bindings : [];
  const removableBindingKeys = new Set<string>();
  const removedBindings: ReplaceUserAgentConflict[] = [];
  const conflictingBindings: ReplaceUserAgentConflict[] = [];

  bindings.forEach((entry) => {
    if (!isRecord(entry) || !isRecord(entry.match)) return;
    const owner = typeof entry.agentId === "string" ? trim(entry.agentId) : "";
    const match = entry.match;
    const channel = trim(match.channel);
    const accountId = trim(match.accountId);
    const peerId = isRecord(match.peer) ? trim(match.peer.id) : "";
    const isRequestedWechat = channel === "openclaw-weixin" &&
      (wechatAccountIds.has(accountId) || wechatPeerIds.has(peerId));
    const isRequestedApi = channel === API_CHANNEL_ID && accountId === API_ACCOUNT_ID && apiPeerIds.has(peerId);
    const explicitlyOwned = isRequestedWechat || isRequestedApi;

    // A current, verified Agent may share this peer while an old duplicate route is being removed.
    if (protectedAgentIds.has(owner)) {
      return;
    }
    // A historical duplicate WeChat route for the same peer is part of this user's residue even if it points at an old Agent.
    if (isRequestedWechat && wechatPeerIds.has(peerId)) {
      removableBindingKeys.add(bindingIdentity(entry));
      removedBindings.push(bindingConflict(owner, match));
      return;
    }
    if (owner === agentId && explicitlyOwned) {
      removableBindingKeys.add(bindingIdentity(entry));
      removedBindings.push(bindingConflict(owner, match));
      return;
    }
    if (owner === agentId) conflictingBindings.push(bindingConflict(owner, match));
  });

  if (conflictingBindings.length > 0) {
    return { persisted: false, runtimeApplied: false, agentRemoved: false, removedBindings: [], conflictingBindings };
  }

  await serializeApiDynamicAgentBindingMutation(() => runtime.mutateConfigFile!({
    base: "runtime",
    afterWrite: { mode: "auto" },
    mutate: (draft: Record<string, unknown>) => {
      const draftAgents = isRecord(draft.agents) ? draft.agents : {};
      const list = Array.isArray(draftAgents.list) ? draftAgents.list : [];
      draft.agents = {
        ...draftAgents,
        list: list.filter((entry) => !isRecord(entry) || entry.id !== agentId),
      };
      const draftBindings = Array.isArray(draft.bindings) ? draft.bindings : [];
      draft.bindings = draftBindings.filter((entry) => !removableBindingKeys.has(bindingIdentity(entry)));
      return { agentRemoved: true, removedBindings };
    },
  }));

  const applied = runtime.current();
  const appliedAgents = isRecord(applied.agents) && Array.isArray(applied.agents.list) ? applied.agents.list : [];
  const appliedBindings = Array.isArray(applied.bindings) ? applied.bindings : [];
  const agentRemoved = !appliedAgents.some((entry) => isRecord(entry) && entry.id === agentId);
  const requestedBindingsRemoved = !appliedBindings.some((entry) => {
    if (!isRecord(entry) || !isRecord(entry.match)) return false;
    const owner = typeof entry.agentId === "string" ? trim(entry.agentId) : "";
    if (protectedAgentIds.has(owner)) return false;
    const channel = trim(entry.match.channel);
    const accountId = trim(entry.match.accountId);
    const peerId = isRecord(entry.match.peer) ? trim(entry.match.peer.id) : "";
    return (channel === "openclaw-weixin" && (wechatAccountIds.has(accountId) || wechatPeerIds.has(peerId))) ||
      (channel === API_CHANNEL_ID && accountId === API_ACCOUNT_ID && apiPeerIds.has(peerId));
  });
  const runtimeApplied = agentRemoved && requestedBindingsRemoved;
  return { persisted: runtimeApplied, runtimeApplied, agentRemoved, removedBindings, conflictingBindings: [] };
}

function isApiPeerBinding(match: Record<string, unknown>, apiPeerIds: Set<string>): boolean {
  return apiPeerIds.size > 0 && match.channel === API_CHANNEL_ID && match.accountId === API_ACCOUNT_ID &&
    isRecord(match.peer) && match.peer.kind === "direct" && typeof match.peer.id === "string" &&
    apiPeerIds.has(trim(match.peer.id));
}

function bindingIdentity(entry: unknown): string {
  if (!isRecord(entry) || !isRecord(entry.match)) return "";
  const match = entry.match;
  return JSON.stringify([
    trim(entry.agentId),
    trim(match.channel),
    trim(match.accountId),
    isRecord(match.peer) ? trim(match.peer.kind) : "",
    isRecord(match.peer) ? trim(match.peer.id) : "",
  ]);
}

function bindingConflict(agentId: string, match: Record<string, unknown>): ReplaceUserAgentConflict {
  return {
    agentId,
    channel: trim(match.channel),
    accountId: trim(match.accountId),
    peerId: isRecord(match.peer) ? trim(match.peer.id) : "",
  };
}

function hasRequiredApiToolPolicy(agent: Record<string, unknown>): boolean {
  const tools = isRecord(agent.tools) ? agent.tools : undefined;
  const denied = Array.isArray(tools?.deny) ? tools.deny : [];
  return REQUIRED_DENIED_TOOLS.every((tool) => denied.includes(tool));
}

async function handleArtifactToolEvent(event: ApiAssistantAgentEvent, state: ApiAgentEventStreamState): Promise<boolean> {
  if (!state.onArtifact || !isRecord(event.data)) return false;
  const toolName = stringValue(event.data.toolName) || stringValue(event.data.name) || stringValue(event.data.tool);
  if (toolName !== "miniapp_artifact") return false;
  const artifact = extractArtifact(event.data);
  if (!artifact) return false;
  const id = stringValue(artifact.id);
  if (!id || state.seenArtifactIds.has(id)) return false;
  state.seenArtifactIds.add(id);
  state.writeChain = state.writeChain.catch(() => undefined).then(() => Promise.resolve(state.onArtifact?.(artifact)));
  await state.writeChain;
  return true;
}

function extractArtifact(data: Record<string, unknown>): Record<string, unknown> | undefined {
  const candidates = [data, data.details, isRecord(data.result) ? data.result.details : undefined,
    isRecord(data.result) ? data.result : undefined, isRecord(data.output) ? data.output.details : undefined,
    isRecord(data.output) ? data.output : undefined];
  for (const candidate of candidates) {
    if (!isRecord(candidate) || !isRecord(candidate.artifact)) continue;
    const artifact = candidate.artifact;
    const type = stringValue(artifact.type);
    const miniappPath = stringValue(artifact.miniappPath);
    if ((type === "image_report" || type === "html_report") && miniappPath.startsWith("/pages/")) return artifact;
  }
  return undefined;
}

function serializeApiDynamicAgentBindingMutation<T>(work: () => Promise<T>): Promise<T> {
  const run = apiDynamicAgentBindingMutationChain
    .catch(() => undefined)
    .then(work);
  apiDynamicAgentBindingMutationChain = run.then(() => undefined, () => undefined);
  return run;
}

async function ensureApiAgentWorkspace(workspace: string): Promise<void> {
  await fs.mkdir(path.join(workspace, ".openclaw"), { recursive: true });
  const preset = await loadApiWorkspacePreset();
  const now = new Date().toISOString();
  await writeFileIfMissing(path.join(workspace, "AGENTS.md"), preset.agentsMd);
  await writeFileIfMissing(path.join(workspace, "USER.md"), preset.userMd);
  await writeFileIfMissing(path.join(workspace, "SOUL.md"), preset.soulMd);
  await writeFileIfMissing(path.join(workspace, "TOOLS.md"), preset.toolsMd);
  await writeFileIfMissing(path.join(workspace, "IDENTITY.md"), preset.identityMd);
  await writeFileIfMissing(path.join(workspace, "HEARTBEAT.md"), preset.heartbeatMd);
  await writeFileIfMissing(path.join(workspace, ".openclaw", "workspace-state.json"), JSON.stringify({
    version: 1,
    setupCompletedAt: now,
  }, null, 2));
  await safeUnlink(path.join(workspace, "BOOTSTRAP.md"));
}

async function loadApiWorkspacePreset(): Promise<typeof DEFAULT_USER_AGENT_WORKSPACE_PRESET> {
  try {
    const parsed = JSON.parse(await fs.readFile(
      resolveHomePath("~/.openclaw/claw-manager/workspace-preset.json"),
      "utf8",
    )) as Record<string, unknown>;
    const keys = ["agentsMd", "soulMd", "identityMd", "toolsMd", "heartbeatMd", "userMd"] as const;
    if (typeof parsed.version === "number" && Number.isInteger(parsed.version) && parsed.version >= 0 &&
        keys.every((key) => typeof parsed[key] === "string" && trim(parsed[key]).length > 0)) {
      return Object.fromEntries(keys.map((key) => [key, parsed[key]])) as typeof DEFAULT_USER_AGENT_WORKSPACE_PRESET;
    }
  } catch {
    // Missing or invalid presets must not block user Agent provisioning.
  }
  return DEFAULT_USER_AGENT_WORKSPACE_PRESET;
}

export async function dispatchApiMessage(ctx: GatewaySendMessageContext): Promise<{
  channel: string;
  messageId: string;
  text: string;
  streamDiagnostics: Record<string, unknown>;
}> {
  if (!ctx.channelRuntime) {
    throw new Error("ctx.channelRuntime missing");
  }
  if (!ctx.cfg) {
    throw new Error("ctx.cfg missing");
  }
  const agentId = requireUserAgentId(ctx.agentId);
  const openVikingUserId = requireOpenVikingUserId(ctx.openVikingUserId ?? ctx.openvikingUserId);
  const runtime = ctx.channelRuntime;
  const inbound = buildApiInboundContext(ctx);
  const configRuntime = resolveApiConfigRuntime(ctx.configRuntime);
  const cfgForRoute = requirePersistedApiUserAgentBinding({
    cfg: configRuntime?.current?.() ?? ctx.cfg,
    agentId,
    apiPeerId: inbound.To,
  });
  const resolvedRoute = runtime.routing.resolveAgentRoute({
    cfg: cfgForRoute,
    channel: API_CHANNEL_ID,
    accountId: API_ACCOUNT_ID,
    peer: { kind: "direct", id: inbound.To },
  });
  if (resolvedRoute.agentId !== agentId) {
    throw new Error("API_BINDING_NOT_READY");
  }
  const route = {
    ...resolvedRoute,
    agentId,
    sessionKey: `agent:${agentId}:${API_CHANNEL_ID}:${API_ACCOUNT_ID}:direct:${inbound.To}:${trim(ctx.conversationHash)}`,
    mainSessionKey: `agent:${agentId}:main`,
    matchedBy: resolvedRoute.agentId === agentId
      ? (resolvedRoute as Record<string, unknown>).matchedBy
      : "explicit-user-agent",
  };
  const sessionKey = route.sessionKey ?? inbound.SessionKey;
  inbound.SessionKey = sessionKey;
  ctx.log?.info?.(
    `[${API_ACCOUNT_ID}] api dispatch route requestId=${trim(ctx.requestId) || "auto"} ` +
      `openVikingUserHash=${hashPreview(inbound.openVikingUserId)} sessionKeyHash=${hashPreview(sessionKey)}`,
  );
  const storePath = runtime.session.resolveStorePath(cfgForRoute.session?.store, {
    agentId: route.agentId,
  });
  const finalized = runtime.reply.finalizeInboundContext(
    inbound as Parameters<typeof runtime.reply.finalizeInboundContext>[0],
  ) as unknown as ApiMsgContext & { CommandAuthorized: boolean };
  const requestId = trim(ctx.requestId) || randomUUID();
  const cmTraceId = apiTraceId(requestId);
  await writeOpenVikingHandoffForTurn({
    sessionKey,
    agentId,
    openVikingUserId: inbound.openVikingUserId,
    log: ctx.log,
    cmTraceId,
    requestId,
  });
  await runtime.session.recordInboundSession({
    storePath,
    sessionKey,
    ctx: finalized as Parameters<typeof runtime.session.recordInboundSession>[0]["ctx"],
    updateLastRoute: {
      sessionKey: route.mainSessionKey ?? sessionKey,
      channel: API_CHANNEL_ID,
      to: inbound.To,
      accountId: API_ACCOUNT_ID,
    },
    onRecordError: (error: unknown) => ctx.log?.error?.(`recordInboundSession: ${String(error)}`),
  });

  const runId = requestId;
  let replyText = "";
  const streamState = registerApiAgentEventStream({
    requestId,
    runId,
    sessionKey,
    onDelta: ctx.onDelta,
    onArtifact: ctx.onArtifact,
    log: ctx.log,
  });
  const { dispatcher, replyOptions, markDispatchIdle } = runtime.reply.createReplyDispatcherWithTyping({
    humanDelay: runtime.reply.resolveHumanDelayConfig(cfgForRoute, route.agentId),
    typingCallbacks: createTypingCallbacks({
      start: async () => {},
      stop: async () => {},
      onStartError: () => {},
      onStopError: () => {},
    }),
    deliver: async (payload: { text?: string }) => {
      const text = payload.text ?? "";
      replyText = mergeDeliveredText(replyText, text);
      if (text && streamState) {
        await emitDeliveredDelta(streamState, replyText);
      }
    },
    onError: (error: unknown) => {
      throw error instanceof Error ? error : new Error(String(error));
    },
  });

  const dispatchStartedAt = Date.now();
  await reportApiTrace({ traceId: cmTraceId, requestId, stage: "api.dispatch.started", status: "started" });
  try {
    await runWithApiOpenVikingTurn(requestId, () => runtime.reply.withReplyDispatcher({
      dispatcher,
      run: () =>
        runtime.reply.dispatchReplyFromConfig({
          ctx: finalized,
          cfg: cfgForRoute,
          dispatcher,
          replyOptions: {
            ...replyOptions,
            runId,
          },
        }),
    }));
    if (streamState && replyText) {
      await emitDeliveredDelta(streamState, replyText);
    }
    await streamState?.writeChain;
    await reportApiTrace({ traceId: cmTraceId, requestId, stage: "api.dispatch.completed", status: "completed", elapsedMs: Date.now() - dispatchStartedAt });
  } catch (error) {
    await reportApiTrace({ traceId: cmTraceId, requestId, stage: "api.dispatch.failed", status: "failed", elapsedMs: Date.now() - dispatchStartedAt, errorCode: "API_DISPATCH_FAILED" });
    throw error;
  } finally {
    markDispatchIdle();
    unregisterApiAgentEventStream(sessionKey, streamState);
    await clearApiOpenVikingTurn({
      sessionKey,
      secret: trim(process.env.OPENVIKING_IDENTITY_HASH_SECRET),
      requestId,
    });
  }
  ctx.log?.info?.(
    `[${API_ACCOUNT_ID}] api dispatch completed requestId=${requestId} ` +
      `openVikingUserHash=${hashPreview(inbound.openVikingUserId)} sessionKeyHash=${hashPreview(sessionKey)} textLen=${replyText.length} ` +
      `agentEventDeltaCount=${streamState?.agentEventDeltaCount ?? 0} ` +
      `deliverDeltaCount=${streamState?.deliverDeltaCount ?? 0}`,
  );
  return {
    channel: API_CHANNEL_ID,
    messageId: requestId,
    text: replyText,
    streamDiagnostics: streamDiagnostics(streamState),
  };
}

async function emitDeliveredDelta(state: ApiAgentEventStreamState, candidate: string): Promise<void> {
  const delta = unemittedSuffix(state.emittedText, candidate);
  if (!delta) return;
  state.emittedText += delta;
  state.deliverDeltaCount += 1;
  if (!state.firstDeltaAtMs) {
    state.firstDeltaAtMs = Date.now();
  }
  state.writeChain = state.writeChain
    .catch(() => undefined)
    .then(() => Promise.resolve(state.onDelta(delta)));
  await state.writeChain;
}

function streamDiagnostics(state?: ApiAgentEventStreamState): Record<string, unknown> {
  const agentEventDeltaCount = state?.agentEventDeltaCount ?? 0;
  const deliverDeltaCount = state?.deliverDeltaCount ?? 0;
  const streamMode = agentEventDeltaCount > 0
    ? (deliverDeltaCount > 0 ? "agent-events+deliver-fallback" : "agent-events")
    : (deliverDeltaCount > 0 ? "deliver-fallback" : "final-fallback");
  return {
    streamMode,
    agentEventDeltaCount,
    deliverDeltaCount,
    deltaCount: agentEventDeltaCount + deliverDeltaCount,
    firstDeltaMs: state?.firstDeltaAtMs ? Math.max(0, state.firstDeltaAtMs - state.startedAtMs) : -1,
  };
}

export function resolveApiQueueRoot(): string {
  const home = trim(process.env.OPENCLAW_HOME) || trim(process.env.HOME) || process.cwd();
  return path.join(home, ".openclaw", API_CHANNEL_ID);
}

export async function writeApiQueueHeartbeat(
  root: string,
  running: boolean,
  extra: Record<string, unknown> = {},
): Promise<void> {
  await fs.mkdir(root, { recursive: true });
  const now = Date.now();
  const statusPath = path.join(root, API_STATUS_FILE);
  const tempPath = `${statusPath}.tmp-${process.pid}-${now}`;
  await fs.writeFile(tempPath, JSON.stringify({
    version: 1,
    running,
    updatedAt: new Date(now).toISOString(),
    updatedAtEpochMs: now,
    pid: process.pid,
    ...extra,
  }), "utf8");
  await fs.rename(tempPath, statusPath);
}

export async function monitorApiQueue(ctx: ApiGatewayStartContext): Promise<void> {
  if (!ctx.channelRuntime) {
    throw new Error("ctx.channelRuntime missing");
  }
  if (!ctx.cfg) {
    throw new Error("ctx.cfg missing");
  }
  const root = resolveApiQueueRoot();
  const dirs = {
    root,
    requests: path.join(root, "requests"),
    processing: path.join(root, "processing"),
    responses: path.join(root, "responses"),
    streams: path.join(root, "streams"),
    failed: path.join(root, "failed"),
  };
  await Promise.all(Object.values(dirs).map((dir) => fs.mkdir(dir, { recursive: true })));
  const monitorState: ApiQueueMonitorState = {
    activeUsers: new Set(),
    activeTasks: new Set(),
  };
  let lastHeartbeatAt = 0;
  const writeHeartbeatIfDue = async (force = false) => {
    const now = Date.now();
    if (!force && now - lastHeartbeatAt < API_HEARTBEAT_INTERVAL_MS) {
      return;
    }
    await writeApiQueueHeartbeat(root, true, {
      activeCount: monitorState.activeTasks.size,
      activeUsers: [...monitorState.activeUsers],
      queuedCount: await countQueueFiles(dirs.requests),
    });
    lastHeartbeatAt = now;
  };
  await writeHeartbeatIfDue(true);
  ctx.log?.info?.(`[${API_ACCOUNT_ID}] claw-manager-api queue monitor started (${root})`);
  ctx.setStatus?.({
    accountId: API_ACCOUNT_ID,
    running: true,
    configured: true,
    lastStartAt: Date.now(),
    lastError: null,
  });

  while (!ctx.abortSignal?.aborted) {
    await writeHeartbeatIfDue();
    await processPendingApiRequests(ctx, dirs, monitorState);
    await sleep(API_QUEUE_POLL_MS, ctx.abortSignal);
  }
  if (monitorState.activeTasks.size > 0) {
    await Promise.allSettled([...monitorState.activeTasks]);
  }
  await writeApiQueueHeartbeat(root, false, {
    activeCount: 0,
    activeUsers: [],
    queuedCount: await countQueueFiles(dirs.requests),
  });
}

async function processPendingApiRequests(
  ctx: ApiGatewayStartContext,
  dirs: { requests: string; processing: string; responses: string; streams: string; failed: string },
  monitorState: ApiQueueMonitorState,
): Promise<void> {
  let files: string[] = [];
  try {
    files = (await fs.readdir(dirs.requests))
      .filter((file) => file.endsWith(".json"))
      .sort();
  } catch (error) {
    ctx.log?.error?.(`[${API_ACCOUNT_ID}] read queue failed: ${errorMessage(error)}`);
    return;
  }

  for (const file of files) {
    if (ctx.abortSignal?.aborted) {
      return;
    }
    const requestPath = path.join(dirs.requests, file);
    const request = await readApiRequestForScheduling(requestPath);
    const userKey = apiRequestUserKey(request, file);
    if (monitorState.activeUsers.has(userKey)) {
      continue;
    }
    const processingPath = path.join(dirs.processing, file);
    try {
      await fs.rename(requestPath, processingPath);
    } catch {
      continue;
    }
    monitorState.activeUsers.add(userKey);
    let task!: Promise<void>;
    task = processApiRequestFile(
      ctx,
      processingPath,
      path.join(dirs.responses, file),
      path.join(dirs.streams, file.replace(/\.json$/, ".jsonl")),
      path.join(dirs.failed, file),
    ).finally(() => {
      monitorState.activeUsers.delete(userKey);
      monitorState.activeTasks.delete(task);
    });
    monitorState.activeTasks.add(task);
  }
}

async function readApiRequestForScheduling(requestPath: string): Promise<ApiSendMessageParams | undefined> {
  try {
    return JSON.parse(await fs.readFile(requestPath, "utf8")) as ApiSendMessageParams;
  } catch {
    return undefined;
  }
}

function apiRequestUserKey(request: ApiSendMessageParams | undefined, fallback: string): string {
  return trim(request?.agentId)
    || trim(request?.openVikingUserId)
    || trim(request?.openvikingUserId)
    || trim(request?.senderHash)
    || `invalid:${fallback}`;
}

async function countQueueFiles(requestsDir: string): Promise<number> {
  try {
    return (await fs.readdir(requestsDir)).filter((file) => file.endsWith(".json")).length;
  } catch {
    return 0;
  }
}

async function processApiRequestFile(
  ctx: ApiGatewayStartContext,
  processingPath: string,
  responsePath: string,
  streamPath: string,
  failedPath: string,
): Promise<void> {
  let request: ApiSendMessageParams = {};
  let requestId = path.basename(processingPath, ".json");
  let streamSeq = 0;
  let streamWriteChain = Promise.resolve();
  const writeStreamEvent = async (event: Omit<ApiQueueStreamEvent, "seq" | "createdAt">) => {
    const write = streamWriteChain.catch(() => undefined).then(async () => {
      streamSeq += 1;
      await writeQueueStreamEvent(streamPath, {
        seq: streamSeq,
        createdAt: new Date().toISOString(),
        ...event,
      });
    });
    streamWriteChain = write.then(() => undefined, () => undefined);
    await write;
  };
  try {
    await safeUnlink(streamPath);
    request = JSON.parse(await fs.readFile(processingPath, "utf8")) as ApiSendMessageParams;
    requestId = trim(request.requestId) || requestId;
    const operation = request.operation ?? "chat";
    if (operation !== "chat") {
      throw new Error("unsupported operation");
    }
    const cmTraceId = apiTraceId(requestId);
    await reportApiTrace({ traceId: cmTraceId, requestId, stage: "api.request.received", status: "completed",
      details: {
        imageRequested: requestsImageGeneration(trim(request.message)),
        imageIntentReason: imageRequestIntentReason(trim(request.message)),
      } });
    ctx.log?.info?.(`[${API_ACCOUNT_ID}] api request received requestId=${requestId} openVikingUserHash=${hashPreview(request.openVikingUserId ?? request.openvikingUserId)}`);
    ctx.setStatus?.({
      accountId: API_ACCOUNT_ID,
      running: true,
      lastInboundAt: Date.now(),
      lastEventAt: Date.now(),
    });
    const stopStreamHeartbeat = startApiStreamHeartbeat(
      () => writeStreamEvent({ type: "heartbeat" }),
      API_STREAM_HEARTBEAT_INTERVAL_MS,
      (error) => ctx.log?.warn?.(`[${API_ACCOUNT_ID}] api stream heartbeat failed requestId=${requestId}: ${errorMessage(error)}`),
    );
    let result: Awaited<ReturnType<typeof dispatchApiMessage>>;
    try {
      result = await dispatchApiMessage({
        ...request,
        requestId,
        cfg: ctx.cfg,
        channelRuntime: ctx.channelRuntime,
        configRuntime: ctx.configRuntime,
        log: ctx.log,
        onDelta: async (text) => writeStreamEvent({ type: "delta", text }),
        onArtifact: async (artifact) => {
          await reportApiTrace({ traceId: cmTraceId, requestId, stage: "api.artifact.emitted", status: "completed" });
          await writeStreamEvent({ type: "artifact", artifact });
        },
      });
    } finally {
      await stopStreamHeartbeat();
    }
    await writeStreamEvent({ type: "done", messageId: result.messageId });
    await reportApiTrace({
      traceId: cmTraceId,
      requestId,
      stage: "api.stream.completed",
      status: "completed",
      details: result.streamDiagnostics,
    });
    await writeQueueResponse(responsePath, {
      ok: true,
      requestId,
      messageId: result.messageId,
      text: result.text,
      finishedAt: new Date().toISOString(),
    });
    ctx.setStatus?.({
      accountId: API_ACCOUNT_ID,
      running: true,
      lastOutboundAt: Date.now(),
      lastEventAt: Date.now(),
      lastError: null,
    });
    ctx.log?.info?.(`[${API_ACCOUNT_ID}] api request completed requestId=${requestId} textLen=${result.text.length}`);
    await safeUnlink(processingPath);
  } catch (error) {
    const message = errorMessage(error);
    ctx.log?.error?.(`[${API_ACCOUNT_ID}] api request failed requestId=${requestId}: ${message}`);
    await writeStreamEvent({ type: "error", error: message }).catch(() => {});
    await writeQueueResponse(responsePath, {
      ok: false,
      requestId,
      error: message,
      finishedAt: new Date().toISOString(),
    });
    await fs.mkdir(path.dirname(failedPath), { recursive: true });
    await fs.rename(processingPath, failedPath).catch(() => safeUnlink(processingPath));
    ctx.setStatus?.({
      accountId: API_ACCOUNT_ID,
      running: true,
      lastError: message,
      lastEventAt: Date.now(),
    });
  }
}

export async function writeQueueStreamEvent(streamPath: string, event: ApiQueueStreamEvent): Promise<void> {
  await fs.mkdir(path.dirname(streamPath), { recursive: true });
  await fs.appendFile(streamPath, `${JSON.stringify(event)}\n`, "utf8");
}

async function writeQueueResponse(responsePath: string, response: ApiQueueResponse): Promise<void> {
  await fs.mkdir(path.dirname(responsePath), { recursive: true });
  const tmpPath = `${responsePath}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(tmpPath, JSON.stringify(response), "utf8");
  await fs.rename(tmpPath, responsePath);
}

async function safeUnlink(file: string): Promise<void> {
  try {
    await fs.unlink(file);
  } catch {}
}

async function writeFileIfMissing(file: string, content: string): Promise<void> {
  try {
    await fs.writeFile(file, content, { encoding: "utf8", flag: "wx" });
  } catch (error) {
    if (!isNodeErrorCode(error, "EEXIST")) {
      throw error;
    }
  }
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
  });
}

export const apiChannelPlugin: ChannelPlugin<Record<string, never>> = {
  id: API_CHANNEL_ID,
  meta: {
    id: API_CHANNEL_ID,
    label: API_CHANNEL_ID,
    selectionLabel: "Claw Manager API",
    docsPath: `/channels/${API_CHANNEL_ID}`,
    docsLabel: API_CHANNEL_ID,
    blurb: "External API channel.",
    order: 80,
  },
  configSchema: {
    schema: {
      type: "object",
      additionalProperties: true,
      properties: {},
    },
  },
  capabilities: {
    chatTypes: ["direct"],
    media: false,
    blockStreaming: true,
  },
  config: {
    listAccountIds: () => [API_ACCOUNT_ID],
    resolveAccount: () => ({}),
    isConfigured: () => true,
    describeAccount: () => ({
      accountId: API_ACCOUNT_ID,
      name: "Claw Manager API",
      enabled: true,
      configured: true,
    }),
  },
  outbound: {
    deliveryMode: "direct",
    textChunkLimit: 8000,
    sendText: async (ctx: { text?: string }) => ({
      channel: API_CHANNEL_ID,
      messageId: randomUUID(),
      text: ctx.text ?? "",
    }),
  },
  status: {
    defaultRuntime: {
      accountId: API_ACCOUNT_ID,
      lastError: null,
      lastInboundAt: null,
      lastOutboundAt: null,
    },
    collectStatusIssues: () => [],
    buildChannelSummary: ({ snapshot }: { snapshot: Record<string, unknown> }) => ({
      configured: true,
      lastError: snapshot.lastError ?? null,
      lastInboundAt: snapshot.lastInboundAt ?? null,
      lastOutboundAt: snapshot.lastOutboundAt ?? null,
    }),
    buildAccountSnapshot: ({ runtime }: { runtime: Record<string, unknown> }) => ({
      ...runtime,
      accountId: API_ACCOUNT_ID,
      name: "Claw Manager API",
      enabled: true,
      configured: true,
    }),
  },
  gateway: {
    startAccount: async (ctx: ApiGatewayStartContext) => monitorApiQueue(ctx),
    stopAccount: async (ctx: { log?: ApiLogSink }) => {
      ctx.log?.info?.(`[${API_ACCOUNT_ID}] claw-manager-api queue monitor stopping`);
    },
  },
} as unknown as ChannelPlugin<Record<string, never>>;

function trim(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function hashPreview(value: unknown): string {
  const normalized = trim(value);
  return normalized ? createHash("sha256").update(normalized).digest("hex").slice(0, 12) : "missing";
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNodeErrorCode(error: unknown, code: string): boolean {
  return isRecord(error) && error.code === code;
}

function asOpenClawConfig(value: OpenClawConfig | Record<string, unknown>): OpenClawConfig {
  return value as OpenClawConfig;
}

function resolveHomePath(value: string): string {
  if (value.startsWith("~/")) {
    const openClawHome = trim(process.env.OPENCLAW_HOME);
    if (openClawHome && value.startsWith("~/.openclaw/")) {
      return path.join(openClawHome, ".openclaw", value.slice("~/.openclaw/".length));
    }
    return path.join(os.homedir(), value.slice(2));
  }
  return value;
}

function trimOverlappingDelta(previous: string, delta: string): string {
  if (!previous || !delta) {
    return delta;
  }
  const max = Math.min(previous.length, delta.length);
  for (let size = max; size > 0; size -= 1) {
    if (previous.endsWith(delta.slice(0, size))) {
      return delta.slice(size);
    }
  }
  return delta;
}

function mergeDeliveredText(previous: string, candidate: string): string {
  if (!candidate) return previous;
  if (!previous || candidate.startsWith(previous)) return candidate;
  if (candidate.length > 1 && previous.startsWith(candidate)) return previous;
  return previous + candidate;
}

function unemittedSuffix(previous: string, candidate: string): string {
  if (!candidate || previous === candidate || previous.startsWith(candidate)) return "";
  if (!previous) return candidate;
  if (candidate.startsWith(previous)) return candidate.slice(previous.length);
  return trimOverlappingDelta(previous, candidate);
}

function shouldPreferExplicitAgentDelta(previous: string, suffix: string, explicit: string): boolean {
  if (!explicit || !previous) {
    return false;
  }
  if (suffix === explicit) {
    return true;
  }
  if (suffix.length <= explicit.length || !suffix.endsWith(explicit)) {
    return false;
  }
  const extraPrefix = suffix.slice(0, suffix.length - explicit.length);
  return extraPrefix.length === 1 && explicit.startsWith(extraPrefix);
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return String(error);
}
