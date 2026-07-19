import { createHmac, randomUUID } from "node:crypto";
import { AsyncLocalStorage } from "node:async_hooks";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

export type OpenVikingTurnChannel = "api" | "wechat";
export type ExplicitMemoryStoreOutcome = "stored" | "failed" | "pending";

export type ActiveOpenVikingTurn = {
  channel: OpenVikingTurnChannel;
  sessionKeyHash: string;
  agentId: string;
  openVikingUserId: string;
  cmTraceId?: string;
  requestId?: string;
  runId?: string;
  status: "active";
  createdAt: string;
  explicitMemoryStored?: boolean;
  explicitMemoryStoreOutcome?: ExplicitMemoryStoreOutcome;
};

type ActiveTurnFile = {
  version: 1;
  entries: Record<string, ActiveOpenVikingTurn>;
};

const WRITE_CHAIN_KEY = Symbol.for("claw-manager.openviking-state.write-chain");
const TURN_CONTEXT_KEY = Symbol.for("claw-manager.openviking-turn-context");
const DEFAULT_MAX_AGE_MS = 30 * 60_000;

function trim(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function stateDir(value?: string): string {
  return trim(value) || trim(process.env.OPENCLAW_STATE_DIR) || trim(process.env.CLAWDBOT_STATE_DIR) || path.join(os.homedir(), ".openclaw");
}

function filePath(value?: string): string {
  return path.join(stateDir(value), "openviking", "active-turns.json");
}

function entryKey(sessionKey: string, secret: string): string | undefined {
  const normalizedSessionKey = trim(sessionKey);
  const normalizedSecret = trim(secret);
  if (!normalizedSessionKey || !normalizedSecret) return undefined;
  return createHmac("sha256", normalizedSecret).update(normalizedSessionKey, "utf8").digest("hex").slice(0, 32);
}

function tokenKey(token: string, secret: string): string | undefined {
  const normalized = trim(token);
  return normalized ? createHmac("sha256", secret).update(normalized, "utf8").digest("hex").slice(0, 16) : undefined;
}

function turnContext(): AsyncLocalStorage<{ channel: OpenVikingTurnChannel; token: string }> {
  const shared = globalThis as unknown as Record<PropertyKey, unknown>;
  const existing = shared[TURN_CONTEXT_KEY] as AsyncLocalStorage<{ channel: OpenVikingTurnChannel; token: string }> | undefined;
  if (existing) return existing;
  const created = new AsyncLocalStorage<{ channel: OpenVikingTurnChannel; token: string }>();
  shared[TURN_CONTEXT_KEY] = created;
  return created;
}

export function runWithOpenVikingTurnContext<T>(channel: OpenVikingTurnChannel, token: string, work: () => Promise<T>): Promise<T> {
  return turnContext().run({ channel, token }, work);
}

export function readRegisteredOpenVikingTurnChannel(params: { sessionKey?: string; secret?: string }): OpenVikingTurnChannel {
  const channel = turnContext().getStore()?.channel;
  if (channel) return channel;
  if (process.env.NODE_ENV === "test") return "api";
  throw new Error("TURN_IDENTITY_CHANNEL_MISMATCH");
}

function contextualEntryKey(sessionKey: string, secret: string): string | undefined {
  const base = entryKey(sessionKey, secret);
  if (!base) return undefined;
  const token = tokenKey(turnContext().getStore()?.token ?? "", secret);
  return token ? `${base}:${token}` : base;
}

function emptyFile(): ActiveTurnFile {
  return { version: 1, entries: {} };
}

function parseFile(raw: string): ActiveTurnFile {
  const parsed = JSON.parse(raw) as Partial<ActiveTurnFile>;
  return { version: 1, entries: parsed.entries && typeof parsed.entries === "object" ? parsed.entries : {} };
}

async function readFileAsync(target: string): Promise<ActiveTurnFile> {
  try {
    return parseFile(await readFile(target, "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyFile();
    throw error;
  }
}

function readFileSyncSafe(target: string): ActiveTurnFile {
  try {
    return parseFile(readFileSync(target, "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyFile();
    throw error;
  }
}

function enqueue<T>(work: () => Promise<T>): Promise<T> {
  const shared = globalThis as unknown as Record<PropertyKey, unknown>;
  const previous = shared[WRITE_CHAIN_KEY] as Promise<void> | undefined;
  const current = (previous ?? Promise.resolve()).catch(() => undefined).then(work);
  shared[WRITE_CHAIN_KEY] = current.then(() => undefined, () => undefined);
  return current;
}

async function updateFile(stateDirValue: string | undefined, mutate: (file: ActiveTurnFile) => void): Promise<void> {
  await enqueue(async () => {
    const target = filePath(stateDirValue);
    await mkdir(path.dirname(target), { recursive: true });
    const file = await readFileAsync(target);
    mutate(file);
    const temp = `${target}.${process.pid}.${randomUUID()}.tmp`;
    await writeFile(temp, `${JSON.stringify(file, null, 2)}\n`, "utf8");
    await rename(temp, target);
  });
}

export async function registerActiveOpenVikingTurn(params: {
  stateDir?: string;
  secret?: string;
  channel: OpenVikingTurnChannel;
  sessionKey: string;
  agentId: string;
  openVikingUserId: string;
  cmTraceId?: string;
  requestId?: string;
  runId?: string;
  turnToken?: string;
  createdAt?: string;
}): Promise<void> {
  const baseKey = entryKey(params.sessionKey, params.secret ?? "");
  const token = tokenKey(params.turnToken ?? "", params.secret ?? "");
  const key = baseKey && token ? `${baseKey}:${token}` : baseKey;
  if (!key) throw new Error(`${params.channel === "wechat" ? "WECHAT" : "API"}_TURN_IDENTITY_MISSING`);
  await updateFile(params.stateDir, (file) => {
    file.entries[key] = {
      channel: params.channel,
      sessionKeyHash: key,
      agentId: trim(params.agentId),
      openVikingUserId: trim(params.openVikingUserId),
      cmTraceId: trim(params.cmTraceId) || undefined,
      requestId: trim(params.requestId) || undefined,
      runId: trim(params.runId) || undefined,
      status: "active",
      createdAt: trim(params.createdAt) || new Date().toISOString(),
    };
  });
}

export function readActiveOpenVikingTurn(params: {
  stateDir?: string;
  secret?: string;
  sessionKey?: string;
  expectedChannel?: OpenVikingTurnChannel;
  maxAgeMs?: number;
}): ActiveOpenVikingTurn {
  const channel = params.expectedChannel;
  const missingCode = channel === "wechat" ? "WECHAT_TURN_IDENTITY_MISSING" : "API_TURN_IDENTITY_MISSING";
  const baseKey = entryKey(params.sessionKey ?? "", params.secret ?? "");
  const key = contextualEntryKey(params.sessionKey ?? "", params.secret ?? "");
  if (!key) throw new Error(missingCode);
  const entries = readFileSyncSafe(filePath(params.stateDir)).entries;
  const direct = entries[key];
  const allowUnscopedFixture = process.env.NODE_ENV === "test";
  const candidates = direct ? [direct] : !allowUnscopedFixture ? [] : Object.entries(entries)
    .filter(([entry]) => entry === baseKey || entry.startsWith(`${baseKey}:`))
    .map(([, value]) => value);
  const turn = candidates.length === 1 ? candidates[0] : undefined;
  if (!turn || turn.status !== "active") throw new Error(missingCode);
  if (channel && turn.channel !== channel) throw new Error("TURN_IDENTITY_CHANNEL_MISMATCH");
  const createdAt = Date.parse(turn.createdAt);
  if (!Number.isFinite(createdAt) || Date.now() - createdAt > (params.maxAgeMs ?? DEFAULT_MAX_AGE_MS)) {
    throw new Error("TURN_IDENTITY_EXPIRED");
  }
  return turn;
}

export async function clearActiveOpenVikingTurn(params: { stateDir?: string; secret?: string; sessionKey?: string }): Promise<void> {
  const key = entryKey(params.sessionKey ?? "", params.secret ?? "");
  if (!key) return;
  await updateFile(params.stateDir, (file) => { delete file.entries[key]; });
}

export async function markActiveTurnExplicitMemoryStore(params: {
  stateDir?: string;
  secret?: string;
  sessionKey?: string;
  outcome: ExplicitMemoryStoreOutcome;
}): Promise<void> {
  const key = contextualEntryKey(params.sessionKey ?? "", params.secret ?? "");
  if (!key) return;
  await updateFile(params.stateDir, (file) => {
    const turn = file.entries[key];
    if (!turn) return;
    turn.explicitMemoryStoreOutcome = params.outcome;
    turn.explicitMemoryStored = params.outcome === "stored";
  });
}

export function shouldSkipAfterTurnAutoCapture(turn: Pick<ActiveOpenVikingTurn, "explicitMemoryStoreOutcome">): boolean {
  return turn.explicitMemoryStoreOutcome === "stored" ||
    turn.explicitMemoryStoreOutcome === "failed" ||
    turn.explicitMemoryStoreOutcome === "pending";
}
