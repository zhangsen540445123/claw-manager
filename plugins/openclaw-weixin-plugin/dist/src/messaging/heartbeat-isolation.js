/**
 * Heartbeat outbound isolation guards.
 *
 * Heartbeat detection is based only on the official canonical isolated
 * session suffix. Message content is never used, so ordinary user
 * messages such as "2018", "-1", and "HEARTBEAT_OK" remain deliverable.
 */
import { createHash } from "node:crypto";
export function isExplicitHeartbeatSession(sessionKey) {
    return typeof sessionKey === "string" && /(?:\:heartbeat)+$/i.test(sessionKey.trim());
}
export function isExplicitHeartbeatContext(context) {
    return isExplicitHeartbeatSession(context.sessionKey);
}
function normalizeChannelId(value) {
    if (typeof value !== "string")
        return undefined;
    const normalized = value.trim().toLowerCase();
    return normalized || undefined;
}
/** Only the official hook channelId identifies the outbound channel. */
export function isHeartbeatChannelContext(context, channelId) {
    const expected = normalizeChannelId(channelId);
    return expected !== undefined && normalizeChannelId(context.channelId) === expected;
}
function hashForLog(value) {
    if (typeof value !== "string" || !value.trim())
        return "none";
    return createHash("sha256").update(value.trim()).digest("hex").slice(0, 12);
}
export function createHeartbeatMessageSendingHook(params) {
    return async (_event, context) => {
        if (!isHeartbeatChannelContext(context, params.channelId) || !isExplicitHeartbeatContext(context)) {
            return undefined;
        }
        params.log?.(`heartbeat outbound suppressed channel=${params.channelId} ` +
            `sessionHash=${hashForLog(context.sessionKey)} runHash=${hashForLog(context.runId)}`);
        return {
            cancel: true,
            cancelReason: "heartbeat_direct_delivery_blocked",
        };
    };
}
//# sourceMappingURL=heartbeat-isolation.js.map