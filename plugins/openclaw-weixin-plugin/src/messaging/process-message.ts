import path from "node:path";
import { randomUUID } from "node:crypto";

import { createTypingCallbacks } from "openclaw/plugin-sdk/channel-runtime";
import {
  resolveSenderCommandAuthorizationWithRuntime,
  resolveDirectDmAuthorizationOutcome,
} from "openclaw/plugin-sdk/command-auth";
import { resolvePreferredOpenClawTmpDir } from "openclaw/plugin-sdk/infra-runtime";
import type { PluginRuntime } from "openclaw/plugin-sdk/core";

import { sendTyping } from "../api/api.js";
import type { WeixinMessage } from "../api/types.js";
import { MessageItemType, TypingStatus } from "../api/types.js";
import { loadWeixinAccount } from "../auth/accounts.js";
import { readFrameworkAllowFromList } from "../auth/pairing.js";
import { downloadRemoteImageToTemp } from "../cdn/upload.js";
import { resolveReplyProgressMessagesEnabled } from "../config/reply-progress.js";
import { downloadMediaFromItem } from "../media/media-download.js";
import {
  archiveAndParseWeixinDocument,
  findAgentWorkspaceFromConfig,
  formatParsedDocumentForInboundBody,
} from "../document/agent-document-archive.js";
import { logger } from "../util/logger.js";
import { redactBody, redactIdentity, redactToken } from "../util/redact.js";

import { isDebugMode } from "./debug-mode.js";
import { sendWeixinErrorNotice } from "./error-notice.js";
import { applyWeixinMessageSendingHook, emitWeixinMessageSent } from "./outbound-hooks.js";
import {
  setContextToken,
  weixinMessageToMsgContext,
  getContextTokenFromMsgContext,
  isMediaItem,
} from "./inbound.js";
import type { WeixinInboundMediaOpts } from "./inbound.js";
import { sendWeixinMediaFile } from "./send-media.js";
import { StreamingMarkdownFilter } from "./markdown-filter.js";
import { sendMessageWeixin } from "./send.js";
import { WeixinReplyProgressSender } from "./reply-progress-sender.js";
import { handleSlashCommand } from "./slash-commands.js";
import { clearWechatOpenVikingTurn, registerWechatOpenVikingTurn, runWithWechatOpenVikingTurn } from "./openviking-handoff.js";
import { resolveUserAgentIdentity } from "./user-agent-identity.js";
import {
  ensureWeixinDynamicAgentRoute,
  type WeixinConfigRuntime,
} from "./dynamic-agent.js";

const MEDIA_OUTBOUND_TEMP_DIR = path.join(resolvePreferredOpenClawTmpDir(), "weixin/media/outbound-temp");
const DOCUMENT_PARSE_LIMITS = {
  maxFileBytes: 20 * 1024 * 1024,
  maxTextChars: 80_000,
  maxImages: 10,
  maxPdfPages: 10,
  maxImageEdgePixels: 1600,
} as const;


export type WeixinSenderRuntimeIdentity = {
  SenderId?: string;
  senderId?: string;
  requesterSenderId?: string;
};

/** Dependencies for processOneMessage, injected by the monitor loop. */
export type ProcessMessageDeps = {
  accountId: string;
  config: import("openclaw/plugin-sdk/core").OpenClawConfig;
  channelRuntime: PluginRuntime["channel"];
  configRuntime?: WeixinConfigRuntime;
  baseUrl: string;
  cdnBaseUrl: string;
  token?: string;
  typingTicket?: string;
  log: (msg: string) => void;
  errLog: (m: string) => void;
};

/** Extract text body from item_list (for slash command detection). */
function extractTextBody(itemList?: import("../api/types.js").MessageItem[]): string {
  if (!itemList?.length) return "";
  for (const item of itemList) {
    if (item.type === MessageItemType.TEXT && item.text_item?.text != null) {
      return String(item.text_item.text);
    }
  }
  return "";
}

function fileNameFromMediaItem(item: WeixinMessage["item_list"] extends Array<infer T> ? T : never | undefined): string | undefined {
  if (!item || typeof item !== "object") return undefined;
  if ((item as any).type === MessageItemType.FILE) return (item as any).file_item?.file_name;
  return undefined;
}

export function modelSupportsImagesFromConfig(cfg: Record<string, any>): boolean {
  const primary = cfg.agents?.defaults?.model?.primary ?? cfg.model?.primary;
  if (typeof primary !== "string" || !primary.includes("/")) return true;
  const [providerId, modelId] = primary.split("/", 2);
  const models = cfg.models?.providers?.[providerId]?.models;
  if (!Array.isArray(models)) return true;
  const model = models.find((entry: unknown) => Boolean(entry) && typeof entry === "object" && (entry as Record<string, unknown>).id === modelId) as Record<string, unknown> | undefined;
  return Array.isArray(model?.input) ? model.input.includes("image") : true;
}

export async function attachParsedWeixinDocumentToContext(input: {
  ctx: import("./inbound.js").WeixinMsgContext;
  routedConfig: Record<string, any>;
  agentId?: string;
  downloadedFilePath: string;
  filename?: string;
  mime?: string;
  accountId: string;
  peerId: string;
  messageSid: string;
  limits?: typeof DOCUMENT_PARSE_LIMITS;
  log?: (msg: string) => void;
  errLog?: (msg: string) => void;
}): Promise<void> {
  const workspace = findAgentWorkspaceFromConfig(input.routedConfig, input.agentId);
  if (!workspace) {
    input.errLog?.(`document archive skipped: workspace unavailable agentId=${input.agentId ?? "(none)"}`);
    input.ctx.Body = [
      input.ctx.Body?.trim() ? `【用户消息】\n${input.ctx.Body.trim()}` : undefined,
      "【收到微信文件】",
      `文件名：${input.filename ?? path.basename(input.downloadedFilePath)}`,
      "文件已下载，但当前 Agent 工作区不可用，未能归档和解析。",
    ].filter(Boolean).join("\n\n");
    delete input.ctx.MediaPath;
    delete input.ctx.MediaType;
    delete input.ctx.MediaPaths;
    delete input.ctx.MediaTypes;
    return;
  }

  const archived = await archiveAndParseWeixinDocument({
    workspace,
    downloadedFilePath: input.downloadedFilePath,
    filename: input.filename ?? path.basename(input.downloadedFilePath),
    mime: input.mime,
    accountId: input.accountId,
    peerId: input.peerId,
    messageSid: input.messageSid,
    limits: input.limits ?? DOCUMENT_PARSE_LIMITS,
    modelSupportsImages: modelSupportsImagesFromConfig(input.routedConfig),
  });
  input.ctx.Body = formatParsedDocumentForInboundBody(input.ctx.Body ?? "", archived);
  if (archived.mediaPaths.length > 0) {
    input.ctx.MediaPaths = archived.mediaPaths;
    input.ctx.MediaTypes = archived.mediaTypes;
    input.ctx.MediaPath = archived.mediaPaths[0];
    input.ctx.MediaType = archived.mediaTypes[0];
  } else {
    delete input.ctx.MediaPath;
    delete input.ctx.MediaType;
    delete input.ctx.MediaPaths;
    delete input.ctx.MediaTypes;
  }
  input.log?.(`[weixin] document archived path=${archived.originalRelativePath} textChars=${archived.parsed.textChars} images=${archived.parsed.images.length}`);
}

export function attachSenderRuntimeIdentity<T extends object>(
  ctx: T,
  senderId: string,
): T & WeixinSenderRuntimeIdentity {
  return Object.assign(ctx, {
    SenderId: senderId,
    senderId,
    requesterSenderId: senderId,
  });
}

function describeSenderForLog(senderId: string): string {
  return redactIdentity(senderId);
}

async function writeOpenVikingHandoffForTurn(params: {
  sessionKey?: string;
  agentId?: string;
  openVikingUserId: string;
  log: (msg: string) => void;
  cmTraceId?: string;
  runId?: string;
}): Promise<void> {
  const secret = process.env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() ?? "";
  if (!secret || !params.sessionKey || !params.openVikingUserId.trim()) {
    throw new Error("WECHAT_TURN_IDENTITY_MISSING");
  }
  const wrote = await registerWechatOpenVikingTurn({
    sessionKey: params.sessionKey,
    agentId: params.agentId,
    openVikingUserId: params.openVikingUserId,
    secret,
    cmTraceId: params.cmTraceId,
    runId: params.runId,
  });
  if (!wrote) {
    throw new Error("WECHAT_TURN_IDENTITY_MISSING");
  }
  params.log("[openviking] turn registered");
}

export async function reportWechatTrace(params: {
  traceId: string;
  stage: string;
  status: "started" | "completed" | "failed";
  requestId?: string;
  elapsedMs?: number;
  errorMessage?: string;
  details?: Record<string, unknown>;
  env?: NodeJS.ProcessEnv;
  fetcher?: typeof fetch;
  timeoutMs?: number;
}): Promise<void> {
  const env = params.env ?? process.env;
  const baseUrl = (env.CLAW_MANAGER_INTERNAL_BASE_URL ?? "").replace(/\/+$/, "");
  const token = env.OPENVIKING_BROKER_TOKEN ?? "";
  const instanceId = env.OPENVIKING_OPENCLAW_INSTANCE_ID ?? "";
  if (!baseUrl || !token || !instanceId) return;
  try {
    const response = await (params.fetcher ?? fetch)(`${baseUrl}/api/internal/integration-traces/events`, { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "X-CM-Trace-Id": params.traceId }, body: JSON.stringify({ traceId: params.traceId, component: "wechat-plugin", stage: params.stage, status: params.status, channel: "wechat", instanceId, requestId: params.requestId, elapsedMs: params.elapsedMs, errorCode: params.status === "failed" ? "WECHAT_STAGE_FAILED" : "", errorMessage: sanitizeTraceError(params.errorMessage), details: params.details ?? {} }), signal: AbortSignal.timeout(params.timeoutMs ?? 5_000) });
    if (!response.ok) logger.warn(`trace report rejected traceId=${params.traceId} stage=${params.stage} status=${response.status}`);
  } catch (error) { logger.warn(`trace report failed traceId=${params.traceId}: ${String(error)}`); }
}

function sanitizeTraceError(value?: string): string {
  return (value ?? "")
    .replace(/Bearer\s+\S+/gi, "Bearer ***")
    .replace(/cm_user_[A-Za-z0-9_-]+/g, "cm_user_***")
    .replace(/sk-[A-Za-z0-9_-]+/g, "sk-***")
    .slice(0, 500);
}

export function requestsImageGeneration(message: string): boolean {
  return /(生图|生成.*(图|海报|卡片)|图片|海报|九宫格|image|poster)/i.test(message);
}

export async function resolveRequiredUserAgentIdentity(
  senderId: string,
  params: {
    traceId: string;
    requestId: string;
    errLog: (message: string) => void;
    resolver?: typeof resolveUserAgentIdentity;
    traceReporter?: typeof reportWechatTrace;
  },
) {
  try {
    return await (params.resolver ?? resolveUserAgentIdentity)(senderId);
  } catch (error) {
    const message = `user Agent identity resolution failed: ${String(error)}`;
    params.errLog(message);
    void (params.traceReporter ?? reportWechatTrace)({
      traceId: params.traceId,
      stage: "wechat.route.resolved",
      status: "failed",
      requestId: params.requestId,
      errorMessage: message,
    });
    throw new Error("user Agent identity resolution failed", { cause: error });
  }
}

/**
 * Process a single inbound message: route → download media → dispatch reply.
 * Extracted from the monitor loop to keep monitoring and message handling separate.
 */
export async function processOneMessage(
  full: WeixinMessage,
  deps: ProcessMessageDeps,
): Promise<void> {
  if (!deps?.channelRuntime) {
    logger.error(
      `processOneMessage: channelRuntime is undefined, skipping message sender=${describeSenderForLog(full.from_user_id ?? "")}`,
    );
    deps.errLog("processOneMessage: channelRuntime is undefined, skip");
    return;
  }

  const receivedAt = Date.now();
  const debug = isDebugMode(deps.accountId);
  const debugTrace: string[] = [];
  const debugTs: Record<string, number> = { received: receivedAt };

  const textBody = extractTextBody(full.item_list);
  if (textBody.startsWith("/")) {
    const slashResult = await handleSlashCommand(textBody, {
      to: full.from_user_id ?? "",
      contextToken: full.context_token,
      baseUrl: deps.baseUrl,
      token: deps.token,
      accountId: deps.accountId,
      log: deps.log,
      errLog: deps.errLog,
    }, receivedAt, full.create_time_ms);
    if (slashResult.handled) {
      logger.info(`[weixin] Slash command handled, skipping AI pipeline`);
      return;
    }
  }
  const runId = randomUUID();
  const cmTraceId = `cmtrace_${runId.replaceAll("-", "")}`;
  await reportWechatTrace({ traceId: cmTraceId, stage: "wechat.inbound.received", status: "completed", requestId: runId,
    details: { imageRequested: requestsImageGeneration(textBody) } });

  if (debug) {
    const itemTypes = full.item_list?.map((i) => i.type).join(",") ?? "none";
    debugTrace.push(
      "── 收消息 ──",
      `│ seq=${full.seq ?? "?"} msgId=${full.message_id ?? "?"} sender=${describeSenderForLog(full.from_user_id ?? "")}`,
      `│ bodyLen=${textBody.length} itemTypes=[${itemTypes}]`,
      `│ sessionId=${full.session_id ?? "?"} contextToken=${full.context_token ? "present" : "none"}`,
    );
  }

  const mediaOpts: WeixinInboundMediaOpts = {};

  // Find the first downloadable media item (priority: IMAGE > VIDEO > FILE > VOICE).
  // When none found in the main item_list, fall back to media referenced via a quoted message.
  const hasDownloadableMedia = (m?: { encrypt_query_param?: string; full_url?: string }) =>
    m?.encrypt_query_param || m?.full_url;
  const mainMediaItem =
    full.item_list?.find(
      (i) => i.type === MessageItemType.IMAGE && hasDownloadableMedia(i.image_item?.media),
    ) ??
    full.item_list?.find(
      (i) => i.type === MessageItemType.VIDEO && hasDownloadableMedia(i.video_item?.media),
    ) ??
    full.item_list?.find(
      (i) => i.type === MessageItemType.FILE && hasDownloadableMedia(i.file_item?.media),
    ) ??
    full.item_list?.find(
      (i) =>
        i.type === MessageItemType.VOICE &&
        hasDownloadableMedia(i.voice_item?.media) &&
        !i.voice_item?.text,
    );
  const refMediaItem = !mainMediaItem
    ? full.item_list?.find(
        (i) =>
          i.type === MessageItemType.TEXT &&
          i.ref_msg?.message_item &&
          isMediaItem(i.ref_msg.message_item!),
      )?.ref_msg?.message_item
    : undefined;

  const mediaDownloadStart = Date.now();
  const mediaItem = mainMediaItem ?? refMediaItem;
  const inboundFileName = fileNameFromMediaItem(mediaItem as never);
  if (mediaItem) {
    const label = refMediaItem ? "ref" : "inbound";
    const downloaded = await downloadMediaFromItem(mediaItem, {
      cdnBaseUrl: deps.cdnBaseUrl,
      saveMedia: deps.channelRuntime.media.saveMediaBuffer,
      log: deps.log,
      errLog: deps.errLog,
      label,
    });
    Object.assign(mediaOpts, downloaded);
  }
  const mediaDownloadMs = Date.now() - mediaDownloadStart;

  if (debug) {
    debugTrace.push(mediaItem
      ? `│ mediaDownload: type=${mediaItem.type} cost=${mediaDownloadMs}ms`
      : "│ mediaDownload: none",
    );
  }

  const ctx = weixinMessageToMsgContext(full, deps.accountId, mediaOpts);

  // --- Framework command authorization ---
  const rawBody = ctx.Body?.trim() ?? "";
  ctx.CommandBody = rawBody;

  const senderId = full.from_user_id ?? "";
  const senderForLog = describeSenderForLog(senderId);

  const { senderAllowedForCommands, commandAuthorized } =
    await resolveSenderCommandAuthorizationWithRuntime({
      cfg: deps.config,
      rawBody,
      isGroup: false,
      dmPolicy: "pairing",
      configuredAllowFrom: [],
      configuredGroupAllowFrom: [],
      senderId,
      isSenderAllowed: (id: string, list: string[]) => list.length === 0 || list.includes(id),
      /** Pairing: framework credentials `*-allowFrom.json`, with account `userId` fallback for legacy installs. */
      readAllowFromStore: async () => {
        const fromStore = readFrameworkAllowFromList(deps.accountId);
        if (fromStore.length > 0) return fromStore;
        const uid = loadWeixinAccount(deps.accountId)?.userId?.trim();
        return uid ? [uid] : [];
      },
      runtime: deps.channelRuntime.commands,
    });

  const directDmOutcome = resolveDirectDmAuthorizationOutcome({
    isGroup: false,
    dmPolicy: "pairing",
    senderAllowedForCommands,
  });

  if (directDmOutcome === "disabled" || directDmOutcome === "unauthorized") {
    logger.info(
      `authorization: dropping message sender=${senderForLog} outcome=${directDmOutcome}`,
    );
    return;
  }

  ctx.CommandAuthorized = commandAuthorized;
  logger.debug(
    `authorization: sender=${senderForLog} commandAuthorized=${String(commandAuthorized)} senderAllowed=${String(senderAllowedForCommands)}`,
  );

  if (debug) {
    debugTrace.push(
      "── 鉴权 & 路由 ──",
      `│ auth: cmdAuthorized=${String(commandAuthorized)} senderAllowed=${String(senderAllowedForCommands)}`,
    );
  }

  const senderIdentity = await resolveRequiredUserAgentIdentity(senderId, {
    traceId: cmTraceId,
    requestId: runId,
    errLog: deps.errLog,
  });
  const dynamicRoute = await ensureWeixinDynamicAgentRoute({
    cfg: deps.config,
    configRuntime: deps.configRuntime,
    channelRuntime: deps.channelRuntime,
    accountId: deps.accountId,
    peerId: ctx.To,
    agentId: senderIdentity.agentId,
  });
  const routedConfig = dynamicRoute.cfg;
  const route = dynamicRoute.route;
  logger.debug(
    `resolveAgentRoute: agentId=${route.agentId ?? "(none)"} sessionKey=${route.sessionKey ? "present" : "none"} mainSessionKey=${route.mainSessionKey ? "present" : "none"}`,
  );
  if (!route.agentId) {
    logger.error(
      `resolveAgentRoute: no agentId resolved for peer=${senderForLog} accountId=${redactIdentity(deps.accountId)} — message will not be dispatched`,
    );
  }
  await reportWechatTrace({ traceId: cmTraceId, stage: "wechat.route.resolved", status: route.agentId ? "completed" : "failed", requestId: runId });

  if (mediaOpts.decryptedFilePath) {
    await attachParsedWeixinDocumentToContext({
      ctx,
      routedConfig: routedConfig as Record<string, any>,
      agentId: route.agentId,
      downloadedFilePath: mediaOpts.decryptedFilePath,
      filename: inboundFileName ?? path.basename(mediaOpts.decryptedFilePath),
      mime: mediaOpts.fileMediaType,
      accountId: deps.accountId,
      peerId: ctx.To,
      messageSid: String(full.message_id ?? ctx.MessageSid),
      limits: DOCUMENT_PARSE_LIMITS,
      log: deps.log,
      errLog: deps.errLog,
    });
  }

  if (debug) {
    debugTrace.push(
      `│ route: agent=${route.agentId ?? "none"} session=${route.sessionKey ? "present" : "none"}`,
    );
    debugTs.preDispatch = Date.now();
  }
  // Propagate the resolved session key into ctx so dispatchReplyFromConfig uses
  // the correct session (matching the dmScope from config) instead of falling back
  // to agent:main:main.
  ctx.SessionKey = route.sessionKey;
  const storePath = deps.channelRuntime.session.resolveStorePath(routedConfig.session?.store, {
    agentId: route.agentId,
  });
  const finalized = attachSenderRuntimeIdentity(deps.channelRuntime.reply.finalizeInboundContext(
    ctx as Parameters<typeof deps.channelRuntime.reply.finalizeInboundContext>[0],
  ), senderId);
  await writeOpenVikingHandoffForTurn({
    sessionKey: route.sessionKey,
    agentId: route.agentId,
    openVikingUserId: senderIdentity.openVikingUserId,
    log: deps.log,
    cmTraceId,
    runId,
  });

  logger.info(
    `inbound: sender=${senderForLog} bodyLen=${(finalized.Body ?? "").length} hasMedia=${Boolean(finalized.MediaPath ?? finalized.MediaUrl ?? finalized.MediaPaths?.length)}`,
  );
  logger.debug(
    `inbound context: bodyLen=${(finalized.Body ?? "").length} sender=${senderForLog} ` +
      `media=${(finalized.MediaPath || finalized.MediaUrl || finalized.MediaPaths?.length) ? "present" : "none"}`,
  );

  await deps.channelRuntime.session.recordInboundSession({
    storePath,
    sessionKey: route.sessionKey,
    ctx: finalized as Parameters<typeof deps.channelRuntime.session.recordInboundSession>[0]["ctx"],
    updateLastRoute: {
      sessionKey: route.mainSessionKey,
      channel: "openclaw-weixin",
      to: ctx.To,
      accountId: deps.accountId,
    },
    onRecordError: (err) => deps.errLog(`recordInboundSession: ${String(err)}`),
  });
  logger.debug(
    `recordInboundSession: done storePath=${storePath} sessionKey=${route.sessionKey ? "present" : "none"}`,
  );

  const contextToken = getContextTokenFromMsgContext(ctx);
  if (contextToken) {
    setContextToken(deps.accountId, full.from_user_id ?? "", contextToken);
  }
  const replyProgressSender = resolveReplyProgressMessagesEnabled(routedConfig)
    ? new WeixinReplyProgressSender({
        runId,
        to: ctx.To,
        accountId: deps.accountId,
        opts: {
          baseUrl: deps.baseUrl,
          token: deps.token,
          contextToken,
        },
      })
    : undefined;
  const humanDelay = deps.channelRuntime.reply.resolveHumanDelayConfig(routedConfig, route.agentId);

  const hasTypingTicket = Boolean(deps.typingTicket);
  const typingCallbacks = createTypingCallbacks({
    start: hasTypingTicket
      ? () =>
          sendTyping({
            baseUrl: deps.baseUrl,
            token: deps.token,
            body: {
              ilink_user_id: ctx.To,
              typing_ticket: deps.typingTicket!,
              status: TypingStatus.TYPING,
            },
          })
      : async () => {},
    stop: hasTypingTicket
      ? () =>
          sendTyping({
            baseUrl: deps.baseUrl,
            token: deps.token,
            body: {
              ilink_user_id: ctx.To,
              typing_ticket: deps.typingTicket!,
              status: TypingStatus.CANCEL,
            },
          })
      : async () => {},
    onStartError: (err) => deps.log(`[weixin] typing send error: ${String(err)}`),
    onStopError: (err) => deps.log(`[weixin] typing cancel error: ${String(err)}`),
    keepaliveIntervalMs: 5000,
  });

  /** Delivery records populated synchronously at deliver() entry, safe to read in finally. */
  const debugDeliveries: Array<{ textLen: number; media: string; preview: string; ts: number }> = [];

  const { dispatcher, replyOptions, markDispatchIdle } =
    deps.channelRuntime.reply.createReplyDispatcherWithTyping({
      humanDelay,
      typingCallbacks,
      deliver: async (payload) => {
        const rawText = payload.text ?? "";
        let text = (() => {
          const f = new StreamingMarkdownFilter();
          return f.feed(rawText) + f.flush();
        })();
        const mediaUrl = payload.mediaUrl ?? payload.mediaUrls?.[0];
        logger.debug(`outbound payload: ${redactBody(JSON.stringify(payload))}`);
        logger.info(
          `outbound: to=${senderForLog} contextToken=${redactToken(contextToken)} textLen=${text.length} mediaUrl=${mediaUrl ? "present" : "none"}`,
        );

        if (debug) {
          debugDeliveries.push({
            textLen: text.length,
            media: mediaUrl ? "present" : "none",
            preview: `${text.slice(0, 60)}${text.length > 60 ? "…" : ""}`,
            ts: Date.now(),
          });
        }

        const sendingResult = await applyWeixinMessageSendingHook({
          to: ctx.To,
          text,
          accountId: deps.accountId,
          mediaUrl,
          runId,
        });
        if (sendingResult.cancelled) {
          logger.info(`outbound: cancelled by message_sending hook to=${senderForLog}`);
          return;
        }
        text = sendingResult.text;

        const supportedMedia = Boolean(mediaUrl && (
          !mediaUrl.includes("://") || mediaUrl.startsWith("file://") || mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")
        ));
        const deliveryStage = supportedMedia ? "wechat.media.send" : "wechat.text.send";
        const deliveryStartedAt = Date.now();
        await reportWechatTrace({ traceId: cmTraceId, stage: `${deliveryStage}.started`, status: "started", requestId: runId });
        try {
          if (mediaUrl) {
            let filePath: string;
            if (!mediaUrl.includes("://") || mediaUrl.startsWith("file://")) {
              if (mediaUrl.startsWith("file://")) {
                filePath = new URL(mediaUrl).pathname;
              } else if (!path.isAbsolute(mediaUrl)) {
                filePath = path.resolve(mediaUrl);
                logger.debug(`outbound: resolved relative path ${mediaUrl} -> ${filePath}`);
              } else {
                filePath = mediaUrl;
              }
              logger.debug(`outbound: local file path resolved filePath=${filePath}`);
            } else if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
              logger.debug(`outbound: downloading remote mediaUrl=${mediaUrl.slice(0, 80)}...`);
              filePath = await downloadRemoteImageToTemp(mediaUrl, MEDIA_OUTBOUND_TEMP_DIR);
              logger.debug(`outbound: remote image downloaded to filePath=${filePath}`);
            } else {
              logger.warn(
                `outbound: unrecognized mediaUrl scheme, sending text only mediaUrl=${mediaUrl.slice(0, 80)}`,
              );
              await sendMessageWeixin({ to: ctx.To, text, opts: {
                baseUrl: deps.baseUrl,
                token: deps.token,
                contextToken,
                runId,
              }});
              emitWeixinMessageSent({ to: ctx.To, content: text, success: true, accountId: deps.accountId, runId });
              logger.info(`outbound: text sent to=${senderForLog}`);
              await reportWechatTrace({ traceId: cmTraceId, stage: "wechat.text.send.completed", status: "completed", requestId: runId, elapsedMs: Date.now() - deliveryStartedAt });
              return;
            }
            await sendWeixinMediaFile({
              filePath,
              to: ctx.To,
              text,
              opts: { baseUrl: deps.baseUrl, token: deps.token, contextToken, runId },
              cdnBaseUrl: deps.cdnBaseUrl,
            });
            emitWeixinMessageSent({ to: ctx.To, content: text, success: true, accountId: deps.accountId, runId });
            logger.info(`outbound: media sent OK to=${senderForLog}`);
          } else {
            logger.debug(`outbound: sending text message to=${senderForLog}`);
            await sendMessageWeixin({ to: ctx.To, text, opts: {
              baseUrl: deps.baseUrl,
              token: deps.token,
              contextToken,
              runId,
            }});
            emitWeixinMessageSent({ to: ctx.To, content: text, success: true, accountId: deps.accountId, runId });
            logger.info(`outbound: text sent OK to=${senderForLog}`);
          }
          await reportWechatTrace({ traceId: cmTraceId, stage: `${deliveryStage}.completed`, status: "completed", requestId: runId, elapsedMs: Date.now() - deliveryStartedAt });
        } catch (err) {
          emitWeixinMessageSent({ to: ctx.To, content: text, success: false, error: String(err), accountId: deps.accountId, runId });
          logger.error(
            `outbound: FAILED to=${senderForLog} mediaUrl=${mediaUrl ?? "none"} err=${String(err)} stack=${(err as Error).stack ?? ""}`,
          );
          await reportWechatTrace({ traceId: cmTraceId, stage: `${deliveryStage}.failed`, status: "failed", requestId: runId, elapsedMs: Date.now() - deliveryStartedAt, errorMessage: String(err) });
          throw err;
        }
      },
      onError: (err, info) => {
        deps.errLog(`weixin reply ${info.kind}: ${String(err)}`);
        const errMsg = err instanceof Error ? err.message : String(err);
        let notice: string;
        if (errMsg.includes("remote media download failed") || errMsg.includes("fetch")) {
          notice = `⚠️ 媒体文件下载失败，请检查链接是否可访问。`;
        } else if (
          errMsg.includes("getUploadUrl") ||
          errMsg.includes("CDN upload") ||
          errMsg.includes("upload_param")
        ) {
          notice = `⚠️ 媒体文件上传失败，请稍后重试。`;
        } else {
          notice = `⚠️ 消息发送失败：${errMsg}`;
        }
        void sendWeixinErrorNotice({
          to: ctx.To,
          contextToken,
          message: notice,
          baseUrl: deps.baseUrl,
          token: deps.token,
          runId,
          errLog: deps.errLog,
        });
      },
    });

  logger.debug(`dispatchReplyFromConfig: starting agentId=${route.agentId ?? "(none)"}`);
  await reportWechatTrace({ traceId: cmTraceId, stage: "openclaw.dispatch.started", status: "started", requestId: runId });
  try {
    await runWithWechatOpenVikingTurn(runId, () => deps.channelRuntime.reply.withReplyDispatcher({
      dispatcher,
      run: () =>
        deps.channelRuntime.reply.dispatchReplyFromConfig({
          ctx: finalized,
          cfg: routedConfig,
          dispatcher,
          replyOptions: {
            ...replyOptions,
            ...(replyProgressSender?.replyOptions ?? {}),
            disableBlockStreaming: true,
          },
        }),
    }));
    logger.debug(`dispatchReplyFromConfig: done agentId=${route.agentId ?? "(none)"}`);
    await reportWechatTrace({ traceId: cmTraceId, stage: "openclaw.dispatch.completed", status: "completed", requestId: runId });
  } catch (err) {
    logger.error(
      `dispatchReplyFromConfig: error agentId=${route.agentId ?? "(none)"} err=${String(err)}`,
    );
    await reportWechatTrace({ traceId: cmTraceId, stage: "openclaw.dispatch.failed", status: "failed", requestId: runId, errorMessage: String(err) });
    throw err;
  } finally {
    markDispatchIdle();
    await clearWechatOpenVikingTurn({
      sessionKey: route.sessionKey,
      secret: process.env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() ?? "",
      runId,
    });
    await replyProgressSender?.finalize();

    logger.info(
      `debug-check: accountId=${redactIdentity(deps.accountId)} debug=${String(debug)} hasContextToken=${Boolean(contextToken)}`,
    );

    if (debug && contextToken) {
      const dispatchDoneAt = Date.now();
      const eventTs = full.create_time_ms ?? 0;
      const platformDelay = eventTs > 0 ? `${receivedAt - eventTs}ms` : "N/A";
      const inboundProcessMs = (debugTs.preDispatch ?? receivedAt) - receivedAt;
      const aiMs = dispatchDoneAt - (debugTs.preDispatch ?? receivedAt);
      const totalTime = eventTs > 0 ? `${dispatchDoneAt - eventTs}ms` : `${dispatchDoneAt - receivedAt}ms`;

      if (debugDeliveries.length > 0) {
        debugTrace.push("── 回复 ──");
        for (const d of debugDeliveries) {
          debugTrace.push(
            `│ textLen=${d.textLen} media=${d.media}`,
            `│ text="${d.preview}"`,
          );
        }
        const firstTs = debugDeliveries[0].ts;
        debugTrace.push(`│ deliver耗时: ${dispatchDoneAt - firstTs}ms`);
      } else {
        debugTrace.push("── 回复 ──", "│ (deliver未捕获)");
      }

      debugTrace.push(
        "── 耗时 ──",
        `├ 平台→插件: ${platformDelay}`,
        `├ 入站处理(auth+route+media): ${inboundProcessMs}ms (mediaDownload: ${mediaDownloadMs}ms)`,
        `├ AI生成+回复: ${aiMs}ms`,
        `├ 总耗时: ${totalTime}`,
        `└ eventTime: ${eventTs > 0 ? new Date(eventTs).toISOString() : "N/A"}`,
      );

      const timingText = `⏱ Debug 全链路\n${debugTrace.join("\n")}`;

      logger.info(`debug-timing: sending to=${senderForLog}`);
      try {
        await sendMessageWeixin({
          to: ctx.To,
          text: timingText,
          opts: { baseUrl: deps.baseUrl, token: deps.token, contextToken, runId },
        });
        logger.info(`debug-timing: sent OK`);
      } catch (debugErr) {
        logger.error(`debug-timing: send FAILED err=${String(debugErr)}`);
      }
    }
  }
}
