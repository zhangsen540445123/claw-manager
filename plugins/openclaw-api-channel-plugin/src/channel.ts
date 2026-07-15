import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { createTypingCallbacks } from "openclaw/plugin-sdk/channel-runtime";
import type { ChannelPlugin, OpenClawConfig, PluginRuntime } from "openclaw/plugin-sdk/core";

import { writeApiOpenVikingHandoff } from "./openviking-handoff.js";

export type ApiSendMessageParams = {
  requestId?: string;
  openVikingUserId?: string;
  openvikingUserId?: string;
  senderHash?: string;
  senderId?: string;
  conversationId?: string;
  conversationHash?: string;
  message?: string;
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

type ApiConfigRuntime = {
  current?: () => OpenClawConfig | Record<string, unknown>;
  mutateConfigFile?: (params: Record<string, unknown>) => Promise<{ result?: unknown } | unknown>;
};

type ApiQueueResponse = {
  ok: boolean;
  requestId: string;
  messageId?: string;
  text?: string;
  error?: string;
  finishedAt: string;
};

type ApiDeltaSink = (text: string) => Promise<void> | void;
type ApiArtifactSink = (artifact: Record<string, unknown>) => Promise<void> | void;

type ApiQueueStreamEvent = {
  seq: number;
  type: "delta" | "artifact" | "done" | "error";
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
const API_STATUS_FILE = "status.json";
const API_DYNAMIC_AGENT_PREFIX = "api-";

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
              `requestId=${state.requestId} sessionKey=${state.sessionKey}`,
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
        `sessionKey=${state.sessionKey} firstDeltaMs=${state.firstAgentEventDeltaAtMs - state.startedAtMs}`,
    );
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
  openVikingUserId?: string;
  senderHash?: string;
  log?: ApiLogSink;
}): Promise<void> {
  const secret = trim(process.env.OPENVIKING_IDENTITY_HASH_SECRET);
  if (!secret) {
    params.log?.debug?.(`[${API_ACCOUNT_ID}] openviking handoff skipped: identity secret missing`);
    return;
  }
  try {
    const wrote = await writeApiOpenVikingHandoff({
      sessionKey: params.sessionKey,
      openVikingUserId: params.openVikingUserId,
      senderHash: params.senderHash,
      secret,
    });
    if (wrote) {
      params.log?.info?.(`[${API_ACCOUNT_ID}] openviking handoff written user=${params.openVikingUserId}`);
    }
  } catch (error) {
    params.log?.warn?.(`[${API_ACCOUNT_ID}] openviking handoff write failed: ${errorMessage(error)}`);
  }
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
  const to = `api:${senderHash}:${conversationHash}`;
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

export function resolveApiDynamicAgentId(senderHash: string): string {
  const normalized = trim(senderHash).toLowerCase().replace(/[^a-z0-9_-]/g, "");
  if (!normalized) {
    throw new Error("senderHash is required");
  }
  return `${API_DYNAMIC_AGENT_PREFIX}${normalized}`;
}

async function ensureApiDynamicAgentBinding(params: {
  cfg: OpenClawConfig;
  channelRuntime: PluginRuntime["channel"];
  configRuntime?: ApiConfigRuntime;
  peerId: string;
  senderHash: string;
  log?: ApiLogSink;
}): Promise<OpenClawConfig> {
  const current = params.configRuntime?.current?.() ?? params.cfg;
  const mutateConfigFile = params.configRuntime?.mutateConfigFile;
  if (!mutateConfigFile) {
    return current;
  }

  const agentId = resolveApiDynamicAgentId(params.senderHash);
  const route = params.channelRuntime.routing.resolveAgentRoute({
    cfg: current,
    channel: API_CHANNEL_ID,
    accountId: API_ACCOUNT_ID,
    peer: { kind: "direct", id: params.peerId },
  });
  const matchedBy = trim((route as Record<string, unknown>).matchedBy);
  if (route.agentId === agentId || (matchedBy && matchedBy !== "default")) {
    return current;
  }

  const workspace = resolveHomePath(`~/.openclaw/workspace-${agentId}`);
  const agentDir = resolveHomePath(`~/.openclaw/agents/${agentId}/agent`);
  await serializeApiDynamicAgentBindingMutation(() => mutateConfigFile({
    base: "runtime",
    afterWrite: { mode: "auto" },
    mutate: async (draft: Record<string, unknown>) => {
      const agents = isRecord(draft.agents) ? draft.agents : {};
      const list = Array.isArray(agents.list) ? [...agents.list] : [];
      const agentExists = list.some((entry) => isRecord(entry) && entry.id === agentId);
      if (!agentExists) {
        await ensureApiAgentWorkspace(workspace);
        await fs.mkdir(agentDir, { recursive: true });
        list.push({ id: agentId, workspace, agentDir });
      }
      draft.agents = { ...agents, list };

      const bindings = Array.isArray(draft.bindings) ? [...draft.bindings] : [];
      const bindingExists = bindings.some((entry) => {
        if (!isRecord(entry) || entry.agentId !== agentId || !isRecord(entry.match)) {
          return false;
        }
        const match = entry.match;
        return match.channel === API_CHANNEL_ID &&
          match.accountId === API_ACCOUNT_ID &&
          isRecord(match.peer) &&
          match.peer.kind === "direct" &&
          match.peer.id === params.peerId;
      });
      if (!bindingExists) {
        bindings.push({
          agentId,
          match: {
            channel: API_CHANNEL_ID,
            accountId: API_ACCOUNT_ID,
            peer: { kind: "direct", id: params.peerId },
          },
        });
        params.log?.info?.(
          `[${API_ACCOUNT_ID}] api dynamic agent bound agent=${agentId} peer=${params.peerId}`,
        );
      }
      draft.bindings = bindings;
      return { agentId, created: !agentExists, bound: !bindingExists };
    },
  }));

  return params.configRuntime?.current?.() ?? current;
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
  const now = new Date().toISOString();
  await writeFileIfMissing(path.join(workspace, "AGENTS.md"), [
    "# Claw Manager API Agent",
    "",
    "This workspace is dedicated to one external API openid.",
    "Do not run first-run onboarding.",
    "Do not use local workspace profile files as cross-user memory.",
    "For user facts and preferences, rely on sender-scoped OpenViking memories injected into the current turn.",
    "Answer the current API request directly.",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, "USER.md"), [
    "# USER.md - API User",
    "",
    "No local user profile is stored here.",
    "Use the sender-scoped OpenViking user memory for this API caller.",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, "SOUL.md"), [
    "# SOUL.md - API Channel",
    "",
    "Be concise, useful, and follow the API request.",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, "TOOLS.md"), [
    "# TOOLS.md - API Channel",
    "",
    "No local tool notes.",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, "IDENTITY.md"), [
    "# IDENTITY.md - API Channel",
    "",
    "Claw Manager API channel assistant.",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, "HEARTBEAT.md"), [
    "<!-- API channel heartbeat disabled. -->",
    "",
  ].join("\n"));
  await writeFileIfMissing(path.join(workspace, ".openclaw", "workspace-state.json"), JSON.stringify({
    version: 1,
    setupCompletedAt: now,
  }, null, 2));
  await safeUnlink(path.join(workspace, "BOOTSTRAP.md"));
}

export async function dispatchApiMessage(ctx: GatewaySendMessageContext): Promise<{ channel: string; messageId: string; text: string }> {
  if (!ctx.channelRuntime) {
    throw new Error("ctx.channelRuntime missing");
  }
  if (!ctx.cfg) {
    throw new Error("ctx.cfg missing");
  }
  const runtime = ctx.channelRuntime;
  const inbound = buildApiInboundContext(ctx);
  const cfgForRoute = await ensureApiDynamicAgentBinding({
    cfg: ctx.configRuntime?.current?.() ?? ctx.cfg,
    channelRuntime: runtime,
    configRuntime: ctx.configRuntime,
    peerId: inbound.To,
    senderHash: trim(ctx.senderHash),
    log: ctx.log,
  });
  const route = runtime.routing.resolveAgentRoute({
    cfg: cfgForRoute,
    channel: API_CHANNEL_ID,
    accountId: API_ACCOUNT_ID,
    peer: { kind: "direct", id: inbound.To },
  });
  const sessionKey = route.sessionKey ?? inbound.SessionKey;
  inbound.SessionKey = sessionKey;
  ctx.log?.info?.(
    `[${API_ACCOUNT_ID}] api dispatch route requestId=${trim(ctx.requestId) || "auto"} ` +
      `user=${inbound.openVikingUserId} senderHash=${trim(ctx.senderHash)} sessionKey=${sessionKey}`,
  );
  const storePath = runtime.session.resolveStorePath(cfgForRoute.session?.store, {
    agentId: route.agentId,
  });
  const finalized = runtime.reply.finalizeInboundContext(
    inbound as Parameters<typeof runtime.reply.finalizeInboundContext>[0],
  ) as unknown as ApiMsgContext & { CommandAuthorized: boolean };
  await writeOpenVikingHandoffForTurn({
    sessionKey,
    openVikingUserId: inbound.openVikingUserId,
    senderHash: trim(ctx.senderHash),
    log: ctx.log,
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

  const requestId = trim(ctx.requestId) || randomUUID();
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
      replyText += text;
      if (text) {
        if (streamState) {
          streamState.deliverDeltaCount += 1;
        }
      }
    },
    onError: (error: unknown) => {
      throw error instanceof Error ? error : new Error(String(error));
    },
  });

  try {
    await runtime.reply.withReplyDispatcher({
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
    });
    await streamState?.writeChain;
  } finally {
    markDispatchIdle();
    unregisterApiAgentEventStream(sessionKey, streamState);
  }
  ctx.log?.info?.(
    `[${API_ACCOUNT_ID}] api dispatch completed requestId=${requestId} ` +
      `user=${inbound.openVikingUserId} sessionKey=${sessionKey} textLen=${replyText.length} ` +
      `agentEventDeltaCount=${streamState?.agentEventDeltaCount ?? 0} ` +
      `deliverDeltaCount=${streamState?.deliverDeltaCount ?? 0}`,
  );
  return {
    channel: API_CHANNEL_ID,
    messageId: requestId,
    text: replyText,
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
  return trim(request?.openVikingUserId)
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
  const writeStreamEvent = async (event: Omit<ApiQueueStreamEvent, "seq" | "createdAt">) => {
    streamSeq += 1;
    await writeQueueStreamEvent(streamPath, {
      seq: streamSeq,
      createdAt: new Date().toISOString(),
      ...event,
    });
  };
  try {
    await safeUnlink(streamPath);
    request = JSON.parse(await fs.readFile(processingPath, "utf8")) as ApiSendMessageParams;
    requestId = trim(request.requestId) || requestId;
    ctx.log?.info?.(`[${API_ACCOUNT_ID}] api request received requestId=${requestId} user=${trim(request.openVikingUserId) || trim(request.openvikingUserId) || "missing"}`);
    ctx.setStatus?.({
      accountId: API_ACCOUNT_ID,
      running: true,
      lastInboundAt: Date.now(),
      lastEventAt: Date.now(),
    });
    const result = await dispatchApiMessage({
      ...request,
      requestId,
      cfg: ctx.cfg,
      channelRuntime: ctx.channelRuntime,
      configRuntime: ctx.configRuntime,
      log: ctx.log,
      onDelta: async (text) => writeStreamEvent({ type: "delta", text }),
      onArtifact: async (artifact) => writeStreamEvent({ type: "artifact", artifact }),
    });
    await writeStreamEvent({ type: "done", messageId: result.messageId });
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
