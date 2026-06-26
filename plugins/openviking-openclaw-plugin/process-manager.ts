import type { OpenVikingClient } from "./client.js";

export function withTimeout<T>(promise: Promise<T>, timeoutMs: number, timeoutMessage: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(timeoutMessage)), timeoutMs);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
}

export async function quickHealthCheck(
  client: OpenVikingClient,
  agentId: string | undefined,
  timeoutMs: number,
): Promise<boolean> {
  try {
    await client.healthCheck(timeoutMs, agentId);
    return true;
  } catch {
    return false;
  }
}

type RecallHealthState = {
  lastHealthyAtMs?: number;
};

export type RecallPrecheckOptions = {
  timeoutMs?: number;
  cacheTtlMs?: number;
  staleTtlMs?: number;
  nowMs?: () => number;
};

const recallHealthStates = new WeakMap<object, RecallHealthState>();

export async function quickRecallPrecheck(
  client: OpenVikingClient,
  agentId?: string,
  options: RecallPrecheckOptions = {},
): Promise<{ ok: true; degraded?: true; reason?: string } | { ok: false; reason: string }> {
  const timeoutMs = Math.max(1, Math.floor(options.timeoutMs ?? 500));
  const cacheTtlMs = Math.max(0, Math.floor(options.cacheTtlMs ?? 0));
  const staleTtlMs = Math.max(0, Math.floor(options.staleTtlMs ?? 0));
  const now = options.nowMs ?? Date.now;
  const nowMs = now();
  const state = recallHealthStates.get(client as object);

  if (state?.lastHealthyAtMs !== undefined && nowMs - state.lastHealthyAtMs <= cacheTtlMs) {
    return { ok: true };
  }

  const healthOk = await quickHealthCheck(client, agentId, timeoutMs);
  if (healthOk) {
    recallHealthStates.set(client as object, { lastHealthyAtMs: now() });
    return { ok: true };
  }

  const lastHealthyAtMs = state?.lastHealthyAtMs;
  if (lastHealthyAtMs !== undefined && nowMs - lastHealthyAtMs <= staleTtlMs) {
    return { ok: true, degraded: true, reason: "recent health check is stale" };
  }
  return { ok: false, reason: "no recent healthy OpenViking check" };
}
