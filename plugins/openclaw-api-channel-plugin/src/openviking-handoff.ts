import { createHmac, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export type ApiOpenVikingHandoff = {
  openVikingUserId: string;
  senderHash: string;
  updatedAt: string;
};

type HandoffFile = {
  version: 1;
  entries: Record<string, ApiOpenVikingHandoff>;
};

let handoffWriteChain: Promise<void> = Promise.resolve();

function trimString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function hmacSha256Hex(secret: string, value: string): string {
  return createHmac("sha256", secret).update(value, "utf8").digest("hex");
}

export function resolveApiOpenVikingHandoffStateDir(stateDir?: string): string {
  return (
    trimString(stateDir) ||
    trimString(process.env.OPENCLAW_STATE_DIR) ||
    trimString(process.env.CLAWDBOT_STATE_DIR) ||
    path.join(os.homedir(), ".openclaw")
  );
}

function handoffPath(stateDir?: string): string {
  return path.join(resolveApiOpenVikingHandoffStateDir(stateDir), "openviking", "sender-handoff.json");
}

function sessionKeyHash(sessionKey: string, secret: string): string | undefined {
  const normalizedSessionKey = trimString(sessionKey);
  const normalizedSecret = trimString(secret);
  if (!normalizedSessionKey || !normalizedSecret) {
    return undefined;
  }
  return hmacSha256Hex(normalizedSecret, normalizedSessionKey).slice(0, 32);
}

async function readHandoffFile(filePath: string): Promise<HandoffFile> {
  try {
    const raw = await readFile(filePath, "utf8");
    const parsed = JSON.parse(raw) as Partial<HandoffFile>;
    return {
      version: 1,
      entries: parsed.entries && typeof parsed.entries === "object" ? parsed.entries : {},
    };
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return { version: 1, entries: {} };
    }
    throw error;
  }
}

export async function writeApiOpenVikingHandoff(params: {
  stateDir?: string;
  sessionKey?: string;
  openVikingUserId?: string;
  senderHash?: string;
  secret?: string;
}): Promise<boolean> {
  const write = handoffWriteChain
    .catch(() => undefined)
    .then(() => writeApiOpenVikingHandoffLocked(params));
  handoffWriteChain = write.then(() => undefined, () => undefined);
  return write;
}

async function writeApiOpenVikingHandoffLocked(params: {
  stateDir?: string;
  sessionKey?: string;
  openVikingUserId?: string;
  senderHash?: string;
  secret?: string;
}): Promise<boolean> {
  const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
  const openVikingUserId = trimString(params.openVikingUserId);
  const senderHash = trimString(params.senderHash);
  if (!key || !openVikingUserId || !senderHash) {
    return false;
  }

  const filePath = handoffPath(params.stateDir);
  await mkdir(path.dirname(filePath), { recursive: true });
  const file = await readHandoffFile(filePath);
  file.entries[key] = {
    openVikingUserId,
    senderHash,
    updatedAt: new Date().toISOString(),
  };
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.${randomUUID()}.tmp`;
  await writeFile(tempPath, `${JSON.stringify(file, null, 2)}\n`, "utf8");
  await rename(tempPath, filePath);
  return true;
}

export async function readApiOpenVikingHandoff(params: {
  stateDir?: string;
  sessionKey?: string;
  secret?: string;
}): Promise<ApiOpenVikingHandoff | undefined> {
  const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
  if (!key) {
    return undefined;
  }
  const file = await readHandoffFile(handoffPath(params.stateDir));
  return file.entries[key];
}
