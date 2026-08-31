/**
 * Heartbeat outbound isolation guards.
 *
 * Heartbeat detection is based only on the official canonical isolated
 * session suffix. Message content is never used, so ordinary user
 * messages such as "2018", "-1", and "HEARTBEAT_OK" remain deliverable.
 */

import { createHash } from "node:crypto";

export type HeartbeatMessageSendingEvent = {
  to?: unknown;
  content?: unknown;
};

export type HeartbeatMessageContext = {
  channelId?: unknown;
  messageProvider?: unknown;
  sessionKey?: unknown;
  runId?: unknown;
  isHeartbeat?: unknown;
  trigger?: unknown;
  runKind?: unknown;
  runType?: unknown;
};

export type HeartbeatMessageSendingResult = {
  cancel: true;
  cancelReason: "heartbeat_direct_delivery_blocked";
};

export function isExplicitHeartbeatSession(sessionKey: unknown): boolean {
  return typeof sessionKey === "string" && /(?:\:heartbeat)+$/i.test(sessionKey.trim());
}

export function isExplicitHeartbeatContext(context: HeartbeatMessageContext): boolean {
  return isExplicitHeartbeatSession(context.sessionKey);
}

function normalizeChannelId(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  return normalized || undefined;
}

/** Only the official hook channelId identifies the outbound channel. */
export function isHeartbeatChannelContext(
  context: HeartbeatMessageContext,
  channelId: string,
): boolean {
  const expected = normalizeChannelId(channelId);
  return expected !== undefined && normalizeChannelId(context.channelId) === expected;
}

function hashForLog(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) return "none";
  return createHash("sha256").update(value.trim()).digest("hex").slice(0, 12);
}

export function createHeartbeatMessageSendingHook(params: {
  channelId: string;
  log?: (message: string) => void;
}): (
  event: HeartbeatMessageSendingEvent,
  context: HeartbeatMessageContext,
) => Promise<HeartbeatMessageSendingResult | undefined> {
  return async (_event, context) => {
    if (!isHeartbeatChannelContext(context, params.channelId) || !isExplicitHeartbeatContext(context)) {
      return undefined;
    }

    params.log?.(
      `heartbeat outbound suppressed channel=${params.channelId} ` +
        `sessionHash=${hashForLog(context.sessionKey)} runHash=${hashForLog(context.runId)}`,
    );
    return {
      cancel: true,
      cancelReason: "heartbeat_direct_delivery_blocked",
    };
  };
}
