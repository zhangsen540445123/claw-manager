import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
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
  log?: ApiLogSink;
};

type ApiLogSink = {
  debug?: (message: string) => void;
  info?: (message: string) => void;
  warn?: (message: string) => void;
  error?: (message: string) => void;
};

type ApiGatewayStartContext = {
  cfg?: OpenClawConfig;
  channelRuntime?: PluginRuntime["channel"];
  abortSignal?: AbortSignal;
  log?: ApiLogSink;
  setStatus?: (next: Record<string, unknown>) => void;
};

type ApiQueueResponse = {
  ok: boolean;
  requestId: string;
  messageId?: string;
  text?: string;
  error?: string;
  finishedAt: string;
};

const API_CHANNEL_ID = "claw-manager-api";
const API_ACCOUNT_ID = "global";
const API_QUEUE_POLL_MS = 200;
const API_HEARTBEAT_INTERVAL_MS = 1000;
const API_STATUS_FILE = "status.json";

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

export async function dispatchApiMessage(ctx: GatewaySendMessageContext): Promise<{ channel: string; messageId: string; text: string }> {
  if (!ctx.channelRuntime) {
    throw new Error("ctx.channelRuntime missing");
  }
  if (!ctx.cfg) {
    throw new Error("ctx.cfg missing");
  }
  const runtime = ctx.channelRuntime;
  const inbound = buildApiInboundContext(ctx);
  const route = runtime.routing.resolveAgentRoute({
    cfg: ctx.cfg,
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
  const storePath = runtime.session.resolveStorePath(ctx.cfg.session?.store, {
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

  let replyText = "";
  const { dispatcher, replyOptions, markDispatchIdle } = runtime.reply.createReplyDispatcherWithTyping({
    humanDelay: runtime.reply.resolveHumanDelayConfig(ctx.cfg, route.agentId),
    typingCallbacks: createTypingCallbacks({
      start: async () => {},
      stop: async () => {},
      onStartError: () => {},
      onStopError: () => {},
    }),
    deliver: async (payload: { text?: string }) => {
      replyText += payload.text ?? "";
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
          cfg: ctx.cfg!,
          dispatcher,
          replyOptions: {
            ...replyOptions,
            disableBlockStreaming: true,
          },
        }),
    });
  } finally {
    markDispatchIdle();
  }
  ctx.log?.info?.(
    `[${API_ACCOUNT_ID}] api dispatch completed requestId=${trim(ctx.requestId) || "auto"} ` +
      `user=${inbound.openVikingUserId} sessionKey=${sessionKey} textLen=${replyText.length}`,
  );
  return {
    channel: API_CHANNEL_ID,
    messageId: trim(ctx.requestId) || randomUUID(),
    text: replyText,
  };
}

export function resolveApiQueueRoot(): string {
  const home = trim(process.env.OPENCLAW_HOME) || trim(process.env.HOME) || process.cwd();
  return path.join(home, ".openclaw", API_CHANNEL_ID);
}

export async function writeApiQueueHeartbeat(root: string, running: boolean): Promise<void> {
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
    failed: path.join(root, "failed"),
  };
  await Promise.all(Object.values(dirs).map((dir) => fs.mkdir(dir, { recursive: true })));
  let lastHeartbeatAt = 0;
  const writeHeartbeatIfDue = async (force = false) => {
    const now = Date.now();
    if (!force && now - lastHeartbeatAt < API_HEARTBEAT_INTERVAL_MS) {
      return;
    }
    await writeApiQueueHeartbeat(root, true);
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
    await processPendingApiRequests(ctx, dirs);
    await sleep(API_QUEUE_POLL_MS, ctx.abortSignal);
  }
  await writeApiQueueHeartbeat(root, false);
}

async function processPendingApiRequests(
  ctx: ApiGatewayStartContext,
  dirs: { requests: string; processing: string; responses: string; failed: string },
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
    const processingPath = path.join(dirs.processing, file);
    try {
      await fs.rename(requestPath, processingPath);
    } catch {
      continue;
    }
    await processApiRequestFile(ctx, processingPath, path.join(dirs.responses, file), path.join(dirs.failed, file));
  }
}

async function processApiRequestFile(
  ctx: ApiGatewayStartContext,
  processingPath: string,
  responsePath: string,
  failedPath: string,
): Promise<void> {
  let request: ApiSendMessageParams = {};
  let requestId = path.basename(processingPath, ".json");
  try {
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
      log: ctx.log,
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

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return String(error);
}
