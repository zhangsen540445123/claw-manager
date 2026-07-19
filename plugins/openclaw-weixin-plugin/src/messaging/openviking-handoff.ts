import { createHmac, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export type OpenVikingSenderHandoff = {
  openVikingUserId: string;
  senderHash: string;
  cmTraceId?: string;
  updatedAt: string;
};

type HandoffFile = {
  version: 1;
  entries: Record<string, OpenVikingSenderHandoff>;
};

const HANDOFF_WRITE_CHAIN_KEY = Symbol.for("claw-manager.openviking-handoff.write-chain");

function trimString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function hmacSha256Hex(secret: string, value: string): string {
  return createHmac("sha256", secret).update(value, "utf8").digest("hex");
}

export function resolveOpenVikingHandoffStateDir(stateDir?: string): string {
  const direct = trimString(stateDir);
  if (direct) {
    return direct;
  }
  return (
    trimString(process.env.OPENCLAW_STATE_DIR) ||
    trimString(process.env.CLAWDBOT_STATE_DIR) ||
    path.join(os.homedir(), ".openclaw")
  );
}

function handoffPath(stateDir?: string): string {
  return path.join(resolveOpenVikingHandoffStateDir(stateDir), "openviking", "sender-handoff.json");
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
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === "ENOENT") {
      return { version: 1, entries: {} };
    }
    throw err;
  }
}

export async function writeOpenVikingSenderHandoff(params: {
  stateDir?: string;
  sessionKey?: string;
  openVikingUserId?: string;
  secret?: string;
  cmTraceId?: string;
}): Promise<boolean> {
  const openVikingUserId = trimString(params.openVikingUserId);
  const matchedIdentity = /^wx_([0-9a-f]{32})$/.exec(openVikingUserId);
  const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
  if (!matchedIdentity || !key) {
    return false;
  }
  return enqueueSharedHandoffWrite(async () => {
    const filePath = handoffPath(params.stateDir);
    await mkdir(path.dirname(filePath), { recursive: true });
    const file = await readHandoffFile(filePath);
    file.entries[key] = {
      openVikingUserId,
      senderHash: matchedIdentity[1],
      cmTraceId: trimString(params.cmTraceId),
      updatedAt: new Date().toISOString(),
    };
    const tempPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
    await writeFile(tempPath, `${JSON.stringify(file, null, 2)}\n`, "utf8");
    await rename(tempPath, filePath);
    return true;
  });
}

function enqueueSharedHandoffWrite<T>(work: () => Promise<T>): Promise<T> {
  const shared = globalThis as unknown as Record<PropertyKey, unknown>;
  const previous = shared[HANDOFF_WRITE_CHAIN_KEY] as Promise<void> | undefined;
  const run = (previous ?? Promise.resolve()).catch(() => undefined).then(work);
  shared[HANDOFF_WRITE_CHAIN_KEY] = run.then(() => undefined, () => undefined);
  return run;
}

export async function readOpenVikingSenderHandoff(params: {
  stateDir?: string;
  sessionKey?: string;
  secret?: string;
}): Promise<OpenVikingSenderHandoff | undefined> {
  const key = sessionKeyHash(params.sessionKey ?? "", params.secret ?? "");
  if (!key) {
    return undefined;
  }
  const file = await readHandoffFile(handoffPath(params.stateDir));
  return file.entries[key];
}
