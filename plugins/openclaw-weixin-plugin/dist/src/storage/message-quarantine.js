import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { resolveStateDir } from "./state-dir.js";
function resolveAccountsDir() {
    return path.join(resolveStateDir(), "openclaw-weixin", "accounts");
}
export function getMessageQuarantineFilePath(accountId) {
    const safeAccountId = path.basename(accountId);
    if (!safeAccountId || safeAccountId !== accountId || safeAccountId === "." || safeAccountId === "..") {
        throw new Error("invalid Weixin account id for quarantine path");
    }
    return path.join(resolveAccountsDir(), `${safeAccountId}.quarantine.jsonl`);
}
export function appendMessageQuarantine(accountId, message, errorCode) {
    const record = {
        timestamp: new Date().toISOString(),
        messageIdHash: hashIdentifier(buildMessageIdentity(message)),
        accountHash: hashIdentifier(accountId),
        peerHash: hashIdentifier(message.from_user_id ?? "unknown-peer"),
        errorCode: normalizeErrorCode(errorCode),
    };
    const filePath = getMessageQuarantineFilePath(accountId);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.appendFileSync(filePath, `${JSON.stringify(record)}\n`, "utf8");
    return record;
}
function buildMessageIdentity(message) {
    const itemIds = (message.item_list ?? [])
        .map((item) => item.msg_id)
        .filter((value) => typeof value === "string" && value.length > 0);
    return JSON.stringify({
        messageId: message.message_id ?? null,
        clientId: message.client_id ?? null,
        sessionId: message.session_id ?? null,
        itemIds,
    });
}
function hashIdentifier(value) {
    return createHash("sha256").update(value, "utf8").digest("hex");
}
function normalizeErrorCode(value) {
    const normalized = value.trim().toUpperCase().replace(/[^A-Z0-9_-]+/g, "_").slice(0, 80);
    return normalized || "MESSAGE_PROCESSING_FAILED";
}
//# sourceMappingURL=message-quarantine.js.map