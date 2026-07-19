import { createHmac, randomUUID } from "node:crypto";
import { AsyncLocalStorage } from "node:async_hooks";
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

const HANDOFF_WRITE_CHAIN_KEY = Symbol.for("claw-manager.openviking-state.write-chain");
const TURN_CONTEXT_KEY = Symbol.for("claw-manager.openviking-turn-context");

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

function activeTurnsPath(stateDir?: string): string {
  return path.join(resolveOpenVikingHandoffStateDir(stateDir), "openviking", "active-turns.json");
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

function activeTurnKey(sessionKey: string, secret: string, token?: string): string | undefined {
  const base = sessionKeyHash(sessionKey, secret);
  const normalizedToken = trimString(token);
  return base && normalizedToken ? `${base}:${hmacSha256Hex(secret, normalizedToken).slice(0, 16)}` : base;
}

function turnContext(): AsyncLocalStorage<{ channel: "api" | "wechat"; token: string }> {
  const shared = globalThis as unknown as Record<PropertyKey, unknown>;
  return (shared[TURN_CONTEXT_KEY] as AsyncLocalStorage<{ channel: "api" | "wechat"; token: string }> | undefined) ??
    (shared[TURN_CONTEXT_KEY] = new AsyncLocalStorage<{ channel: "api" | "wechat"; token: string }>()) as AsyncLocalStorage<{ channel: "api" | "wechat"; token: string }>;
}

export function runWithWechatOpenVikingTurn<T>(runId: string, work: () => Promise<T>): Promise<T> {
  return turnContext().run({ channel: "wechat", token: runId }, work);
}

async function mutateActiveTurns(params: { stateDir?: string; sessionKey?: string; secret?: string; runId?: string }, value?: Record<string, unknown>): Promise<boolean> {
  const key = activeTurnKey(params.sessionKey ?? "", params.secret ?? "", params.runId);
  if (!key) return false;
  return enqueueSharedHandoffWrite(async () => {
    const filePath = activeTurnsPath(params.stateDir);
    await mkdir(path.dirname(filePath), { recursive: true });
    const file = await readHandoffFile(filePath);
    if (value) file.entries[key] = value as unknown as OpenVikingSenderHandoff;
    else {
      const current = file.entries[key] as unknown as Record<string, unknown> | undefined;
      if (trimString(params.runId) && trimString(current?.runId) !== trimString(params.runId)) return false;
      delete file.entries[key];
    }
    const tempPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
    await writeFile(tempPath, `${JSON.stringify(file, null, 2)}\n`, "utf8");
    await rename(tempPath, filePath);
    return true;
  });
}

export async function registerWechatOpenVikingTurn(params: {
  stateDir?: string; sessionKey?: string; secret?: string; agentId?: string; openVikingUserId?: string;
  cmTraceId?: string; runId?: string;
}): Promise<boolean> {
  const agentId = trimString(params.agentId);
  const openVikingUserId = trimString(params.openVikingUserId);
  if (!agentId || !/^wx_[0-9a-f]{32}$/.test(openVikingUserId)) return false;
  const wrote = await mutateActiveTurns(params, {
    channel: "wechat", sessionKeyHash: sessionKeyHash(params.sessionKey ?? "", params.secret ?? ""), agentId, openVikingUserId, cmTraceId: trimString(params.cmTraceId) || undefined,
    runId: trimString(params.runId) || undefined, status: "active", createdAt: new Date().toISOString(),
  });
  return wrote;
}

export async function clearWechatOpenVikingTurn(params: { stateDir?: string; sessionKey?: string; secret?: string; runId?: string }): Promise<boolean> {
  const cleared = await mutateActiveTurns(params);
  return cleared;
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
