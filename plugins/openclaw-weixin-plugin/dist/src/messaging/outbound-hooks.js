import { fireAndForgetHook, buildCanonicalSentMessageHookContext, toPluginMessageContext, toPluginMessageSentEvent, } from "openclaw/plugin-sdk/hook-runtime";
import { getGlobalHookRunner } from "openclaw/plugin-sdk/plugin-runtime";
import { logger } from "../util/logger.js";
const CHANNEL_ID = "openclaw-weixin";
/**
 * Build the context passed to OpenClaw's message_sending hook.
 *
 * Runtime heartbeat metadata is deliberately opt-in: ordinary Weixin
 * deliveries must not acquire heartbeat markers merely because this helper
 * is used.
 */
export function buildWeixinMessageSendingContext(input) {
    return {
        channelId: CHANNEL_ID,
        ...(input.accountId ? { accountId: input.accountId } : {}),
        ...(input.sessionKey ? { sessionKey: input.sessionKey } : {}),
        ...(input.runId ? { runId: input.runId } : {}),
        ...(input.isHeartbeat !== undefined ? { isHeartbeat: input.isHeartbeat } : {}),
        ...(input.trigger ? { trigger: input.trigger } : {}),
        ...(input.runKind ? { runKind: input.runKind } : {}),
        ...(input.runType ? { runType: input.runType } : {}),
    };
}
/**
 * Run message_sending hook before sending.
 * Returns the (possibly modified) text content plus a cancelled flag.
 * Hook errors are caught and logged — sending proceeds regardless.
 */
export async function applyWeixinMessageSendingHook(params) {
    const hookRunner = getGlobalHookRunner();
    if (!hookRunner?.hasHooks("message_sending")) {
        return { cancelled: false, text: params.text };
    }
    try {
        const hookResult = await hookRunner.runMessageSending({
            to: params.to,
            content: params.text,
            metadata: {
                channel: CHANNEL_ID,
                accountId: params.accountId,
                runId: params.runId,
                ...(params.mediaUrl ? { mediaUrls: [params.mediaUrl] } : {}),
            },
        }, buildWeixinMessageSendingContext({
            accountId: params.accountId,
            sessionKey: params.sessionKey,
            runId: params.runId,
            isHeartbeat: params.isHeartbeat,
            trigger: params.trigger,
            runKind: params.runKind,
            runType: params.runType,
        }));
        if (hookResult?.cancel) {
            return { cancelled: true, text: params.text };
        }
        return {
            cancelled: false,
            text: hookResult?.content ?? params.text,
        };
    }
    catch (err) {
        logger.warn(`message_sending hook error, proceeding with send: ${String(err)}`);
        return { cancelled: false, text: params.text };
    }
}
/**
 * Fire message_sent hook (fire-and-forget) after a send attempt.
 */
export function emitWeixinMessageSent(params) {
    const hookRunner = getGlobalHookRunner();
    if (!hookRunner?.hasHooks("message_sent"))
        return;
    const canonical = buildCanonicalSentMessageHookContext({
        to: params.to,
        content: params.content,
        success: params.success,
        error: params.error,
        channelId: CHANNEL_ID,
        accountId: params.accountId,
        conversationId: params.to,
        runId: params.runId,
    });
    fireAndForgetHook(Promise.resolve(hookRunner.runMessageSent(toPluginMessageSentEvent(canonical), toPluginMessageContext(canonical))), "weixin: message_sent plugin hook failed");
}
//# sourceMappingURL=outbound-hooks.js.map