import { createHmac } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
function trimString(value) {
    return typeof value === "string" ? value.trim() : "";
}
function hmacSha256Hex(secret, value) {
    return createHmac("sha256", secret).update(value, "utf8").digest("hex");
}
export function resolveOpenVikingHandoffStateDir(stateDir) {
    const direct = trimString(stateDir);
    if (direct) {
        return direct;
    }
    return (trimString(process.env.OPENCLAW_STATE_DIR) ||
        trimString(process.env.CLAWDBOT_STATE_DIR) ||
        path.join(os.homedir(), ".openclaw"));
}
function handoffPath(stateDir) {
    return path.join(resolveOpenVikingHandoffStateDir(stateDir), "openviking", "sender-handoff.json");
}
function deriveSenderHandoff(senderId, secret) {
    const normalizedSender = trimString(senderId);
    const normalizedSecret = trimString(secret);
    if (!normalizedSender || !normalizedSecret) {
        return undefined;
    }
    const senderHash = hmacSha256Hex(normalizedSecret, normalizedSender).slice(0, 32);
    return {
        senderHash,
        openVikingUserId: `wx_${senderHash}`,
    };
}
function sessionKeyHash(sessionKey, secret) {
    const normalizedSessionKey = trimString(sessionKey);
    const normalizedSecret = trimString(secret);
    if (!normalizedSessionKey || !normalizedSecret) {
        return undefined;
    }
    return hmacSha256Hex(normalizedSecret, normalizedSessionKey).slice(0, 32);
}
async function readHandoffFile(filePath) {
    try {
        const raw = await readFile(filePath, "utf8");
        const parsed = JSON.parse(raw);
        return {
            version: 1,
            entries: parsed.entries && typeof parsed.entries === "object" ? parsed.entries : {},
        };
    }
    catch (err) {
        if (err.code === "ENOENT") {
            return { version: 1, entries: {} };
        }
        throw err;
    }
}
export async function writeOpenVikingSenderHandoff(params) {
    const derived = deriveSenderHandoff(params.senderId ?? "", params.secret ?? "");
    const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
    if (!derived || !key) {
        return false;
    }
    const filePath = handoffPath(params.stateDir);
    await mkdir(path.dirname(filePath), { recursive: true });
    const file = await readHandoffFile(filePath);
    file.entries[key] = {
        ...derived,
        cmTraceId: trimString(params.cmTraceId),
        updatedAt: new Date().toISOString(),
    };
    const tempPath = `${filePath}.${process.pid}.tmp`;
    await writeFile(tempPath, `${JSON.stringify(file, null, 2)}\n`, "utf8");
    await rename(tempPath, filePath);
    return true;
}
export async function readOpenVikingSenderHandoff(params) {
    const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
    if (!key) {
        return undefined;
    }
    const file = await readHandoffFile(handoffPath(params.stateDir));
    return file.entries[key];
}
//# sourceMappingURL=openviking-handoff.js.map