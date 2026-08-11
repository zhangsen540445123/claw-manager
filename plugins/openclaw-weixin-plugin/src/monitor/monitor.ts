import type { ChannelAccountSnapshot } from "openclaw/plugin-sdk/channel-contract";
import type { PluginRuntime } from "openclaw/plugin-sdk/core";

import { getUpdates, classifyFetchError } from "../api/api.js";
import { WeixinConfigManager } from "../api/config-cache.js";
import { STALE_TOKEN_ERRCODE, pauseSession, getRemainingPauseMs } from "../api/session-guard.js";
import type { WeixinMessage } from "../api/types.js";
import { processOneMessage } from "../messaging/process-message.js";
import { appendMessageQuarantine } from "../storage/message-quarantine.js";
import { getSyncBufFilePath, loadGetUpdatesBuf, saveGetUpdatesBuf } from "../storage/sync-buf.js";
import { logger } from "../util/logger.js";
import type { Logger } from "../util/logger.js";
import { redactBody, redactIdentity } from "../util/redact.js";
import type { WeixinConfigRuntime } from "../messaging/dynamic-agent.js";

const DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000;
const MAX_CONSECUTIVE_FAILURES = 3;
const MAX_MESSAGE_PROCESSING_ATTEMPTS = 3;
const BACKOFF_DELAY_MS = 30_000;
const RETRY_DELAY_MS = 2_000;

export type MonitorWeixinOpts = {
  baseUrl: string;
  cdnBaseUrl: string;
  token?: string;
  accountId: string;
  /** When non-empty, only messages whose from_user_id is in this list are processed. */
  allowFrom?: string[];
  config: import("openclaw/plugin-sdk/core").OpenClawConfig;
  runtime?: { log?: (msg: string) => void; error?: (msg: string) => void };
  /**
   * Gateway-injected channel runtime surface (reply/routing/session/media/commands/...).
   * Required for inbound message processing; provided by `ChannelGatewayContext.channelRuntime`.
   */
  channelRuntime: PluginRuntime["channel"];
  configRuntime?: WeixinConfigRuntime;
  abortSignal?: AbortSignal;
  longPollTimeoutMs?: number;
  /** Gateway status callback — called on each successful poll and inbound message. */
  setStatus?: (next: ChannelAccountSnapshot) => void;
};

/**
 * Long-poll loop: getUpdates -> normalize -> recordInboundSession -> dispatchReplyFromConfig.
 * Runs until abort.
 */
export async function monitorWeixinProvider(opts: MonitorWeixinOpts): Promise<void> {
  const {
    baseUrl,
    cdnBaseUrl,
    token,
    accountId,
    config,
    channelRuntime,
    abortSignal,
    longPollTimeoutMs,
    setStatus,
  } = opts;
  const log = opts.runtime?.log ?? (() => {});
  const errLog = opts.runtime?.error ?? ((m: string) => log(m));
  const aLog: Logger = logger.withAccount(accountId);

  if (!channelRuntime) {
    const msg =
      "channelRuntime missing on monitor opts; gateway must inject ChannelGatewayContext.channelRuntime";
    aLog.error(msg);
    throw new Error(msg);
  }

  log(`weixin monitor started (${baseUrl}, account=${redactIdentity(accountId)})`);
  aLog.info(
    `Monitor started: baseUrl=${baseUrl} timeoutMs=${longPollTimeoutMs ?? DEFAULT_LONG_POLL_TIMEOUT_MS}`,
  );

  const syncFilePath = getSyncBufFilePath(accountId);
  aLog.debug(`syncFilePath: ${syncFilePath}`);

  const previousGetUpdatesBuf = loadGetUpdatesBuf(syncFilePath);
  let getUpdatesBuf = previousGetUpdatesBuf ?? "";

  if (previousGetUpdatesBuf) {
    log(`[weixin] resuming from previous sync buf (${getUpdatesBuf.length} bytes)`);
    aLog.debug(`Using previous get_updates_buf (${getUpdatesBuf.length} bytes)`);
  } else {
    log(`[weixin] no previous sync buf, starting fresh`);
    aLog.info(`No previous get_updates_buf found, starting fresh`);
  }

  const configManager = new WeixinConfigManager({ baseUrl, token }, log);

  let nextTimeoutMs = longPollTimeoutMs ?? DEFAULT_LONG_POLL_TIMEOUT_MS;
  let consecutiveFailures = 0;

  while (!abortSignal?.aborted) {
    let resp;
    try {
      aLog.debug(
        `getUpdates: get_updates_buf=${getUpdatesBuf.substring(0, 50)}..., timeoutMs=${nextTimeoutMs}`,
      );
      resp = await getUpdates({
        baseUrl,
        token,
        get_updates_buf: getUpdatesBuf,
        timeoutMs: nextTimeoutMs,
        // Stop/hot-reload should cancel the in-flight long-poll immediately
        // instead of waiting for the server-side long-poll timeout.
        abortSignal,
      });
    } catch (err) {
      if (abortSignal?.aborted) {
        aLog.info(`Monitor stopped (aborted)`);
        return;
      }
      consecutiveFailures += 1;
      const classified = classifyFetchError(err);
      errLog(
        `weixin getUpdates network error (${consecutiveFailures}/${MAX_CONSECUTIVE_FAILURES}): ${String(err)} type=${classified.type} description=${classified.description}${classified.code ? ` code=${classified.code}` : ""}`,
      );
      aLog.error(
        `getUpdates network error: ${String(err)}, type=${classified.type} code=${classified.code ?? "none"}, stack=${(err as Error).stack}`,
      );
      await waitAfterGetUpdatesFailure(consecutiveFailures, errLog, aLog, abortSignal);
      if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        consecutiveFailures = 0;
      }
      continue;
    }

    aLog.debug(
      `getUpdates response: ret=${resp.ret}, msgs=${resp.msgs?.length ?? 0}, get_updates_buf_length=${resp.get_updates_buf?.length ?? 0}`,
    );

    if (resp.longpolling_timeout_ms != null && resp.longpolling_timeout_ms > 0) {
      nextTimeoutMs = resp.longpolling_timeout_ms;
      aLog.debug(`Updated next poll timeout: ${nextTimeoutMs}ms`);
    }
    const isApiError =
      (resp.ret !== undefined && resp.ret !== 0) ||
      (resp.errcode !== undefined && resp.errcode !== 0);
    if (isApiError) {
      const isStaleToken =
        resp.errcode === STALE_TOKEN_ERRCODE || resp.ret === STALE_TOKEN_ERRCODE;

      if (isStaleToken) {
        pauseSession(accountId);
        const pauseMs = getRemainingPauseMs(accountId);
        aLog.error(
          `getUpdates: token for ${redactIdentity(accountId)} is stale, pausing all requests for ${Math.ceil(pauseMs / 60_000)} min`,
        );
        consecutiveFailures = 0;
        await sleep(pauseMs, abortSignal);
        continue;
      }

      consecutiveFailures += 1;
      errLog(
        `weixin getUpdates failed: ret=${resp.ret} errcode=${resp.errcode} errmsg=${resp.errmsg ?? ""} (${consecutiveFailures}/${MAX_CONSECUTIVE_FAILURES})`,
      );
      aLog.error(
        `getUpdates failed: ret=${resp.ret} errcode=${resp.errcode} errmsg=${resp.errmsg} response=${redactBody(JSON.stringify(resp))}`,
      );
      await waitAfterGetUpdatesFailure(consecutiveFailures, errLog, aLog, abortSignal);
      if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        consecutiveFailures = 0;
      }
      continue;
    }

    consecutiveFailures = 0;
    setStatus?.({ accountId, lastEventAt: Date.now() });
    const list = resp.msgs ?? [];
    for (const full of list) {
      aLog.info(
        `inbound message: from=${redactIdentity(full.from_user_id)} types=${full.item_list?.map((i) => i.type).join(",") ?? "none"}`,
      );

      const now = Date.now();
      setStatus?.({ accountId, lastEventAt: now, lastInboundAt: now });
      await processMessageWithIsolation(full, opts, configManager, errLog, aLog);
    }

    if (resp.get_updates_buf != null && resp.get_updates_buf !== "") {
      saveGetUpdatesBuf(syncFilePath, resp.get_updates_buf);
      getUpdatesBuf = resp.get_updates_buf;
      aLog.debug(`Saved new get_updates_buf (${getUpdatesBuf.length} bytes)`);
    }
  }
  aLog.info(`Monitor ended`);
}

async function processMessageWithIsolation(
  full: WeixinMessage,
  opts: MonitorWeixinOpts,
  configManager: WeixinConfigManager,
  errLog: (message: string) => void,
  aLog: Logger,
): Promise<void> {
  let lastError: unknown;
  for (let attempt = 1; attempt <= MAX_MESSAGE_PROCESSING_ATTEMPTS; attempt += 1) {
    try {
      const fromUserId = full.from_user_id ?? "";
      const cachedConfig = await configManager.getForUser(fromUserId, full.context_token);
      await processOneMessage(full, {
        accountId: opts.accountId,
        config: opts.config,
        channelRuntime: opts.channelRuntime,
        configRuntime: opts.configRuntime,
        baseUrl: opts.baseUrl,
        cdnBaseUrl: opts.cdnBaseUrl,
        token: opts.token,
        typingTicket: cachedConfig.typingTicket,
        log: opts.runtime?.log ?? (() => {}),
        errLog,
      });
      return;
    } catch (error) {
      lastError = error;
      const errorCode = messageProcessingErrorCode(error);
      const redactedError = redactBody(String(error));
      if (errorCode === "WECHAT_AGENT_NOT_READY") {
        errLog(
          `weixin agent not ready (${attempt}/${MAX_MESSAGE_PROCESSING_ATTEMPTS}): account=${redactIdentity(opts.accountId)} peer=${redactIdentity(full.from_user_id)} code=${errorCode}`,
        );
        aLog.warn(`agent not ready: attempt=${attempt} error=${redactedError}`);
      } else {
        errLog(
          `weixin message routing error (${attempt}/${MAX_MESSAGE_PROCESSING_ATTEMPTS}): account=${redactIdentity(opts.accountId)} peer=${redactIdentity(full.from_user_id)} code=${errorCode}`,
        );
        aLog.error(`message routing error: attempt=${attempt} error=${redactedError}`);
      }
      if (attempt < MAX_MESSAGE_PROCESSING_ATTEMPTS) {
        await sleep(RETRY_DELAY_MS, opts.abortSignal);
      }
    }
  }

  const errorCode = messageProcessingErrorCode(lastError);
  try {
    appendMessageQuarantine(opts.accountId, full, errorCode);
    errLog(
      `weixin message quarantined: account=${redactIdentity(opts.accountId)} peer=${redactIdentity(full.from_user_id)} code=${errorCode}`,
    );
    aLog.warn(`message quarantined: code=${errorCode}`);
  } catch (quarantineError) {
    aLog.error(`quarantine write failed: ${redactBody(String(quarantineError))}`);
    errLog(`weixin message quarantine write failed: code=${errorCode}`);
  }
}

function messageProcessingErrorCode(error: unknown): string {
  const text = String(error);
  if (text.includes("WECHAT_AGENT_NOT_READY")) {
    return "WECHAT_AGENT_NOT_READY";
  }
  if (typeof error === "object" && error !== null && "code" in error) {
    const code = String((error as { code?: unknown }).code ?? "").trim();
    if (code) {
      return code;
    }
  }
  return "MESSAGE_PROCESSING_FAILED";
}

async function waitAfterGetUpdatesFailure(
  consecutiveFailures: number,
  errLog: (message: string) => void,
  aLog: Logger,
  abortSignal?: AbortSignal,
): Promise<void> {
  if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
    errLog(
      `weixin getUpdates: ${MAX_CONSECUTIVE_FAILURES} consecutive failures, backing off 30s`,
    );
    aLog.error(
      `getUpdates: ${MAX_CONSECUTIVE_FAILURES} consecutive failures, backing off 30s`,
    );
    await sleep(BACKOFF_DELAY_MS, abortSignal);
  } else {
    await sleep(RETRY_DELAY_MS, abortSignal);
  }
}

export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", finish);
      resolve();
    };
    const timer = setTimeout(finish, ms);
    if (signal?.aborted) {
      finish();
      return;
    }
    signal?.addEventListener("abort", finish, { once: true });
  });
}
