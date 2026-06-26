import { describe, expect, it, vi } from "vitest";

import { quickRecallPrecheck } from "../../process-manager.js";

describe("quickRecallPrecheck", () => {
  function makeClient(healthCheck: ReturnType<typeof vi.fn>) {
    return { healthCheck } as any;
  }

  it("uses the configured health check timeout", async () => {
    const healthCheck = vi.fn().mockResolvedValue(undefined);

    const result = await quickRecallPrecheck(makeClient(healthCheck), "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => 1_000,
    });

    expect(result).toEqual({ ok: true });
    expect(healthCheck).toHaveBeenCalledWith(2000, "agent-1");
  });

  it("uses a recent healthy cache entry without repeating health checks", async () => {
    let now = 1_000;
    const healthCheck = vi.fn().mockResolvedValue(undefined);
    const client = makeClient(healthCheck);

    await quickRecallPrecheck(client, "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => now,
    });
    healthCheck.mockRejectedValue(new Error("network is slow"));
    now += 10_000;

    const result = await quickRecallPrecheck(client, "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => now,
    });

    expect(result).toEqual({ ok: true });
    expect(healthCheck).toHaveBeenCalledTimes(1);
  });

  it("allows recall when health fails but OpenViking was healthy recently", async () => {
    let now = 1_000;
    const healthCheck = vi.fn().mockResolvedValue(undefined);
    const client = makeClient(healthCheck);

    await quickRecallPrecheck(client, "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => now,
    });
    healthCheck.mockRejectedValue(new Error("timeout"));
    now += 60_000;

    const result = await quickRecallPrecheck(client, "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => now,
    });

    expect(result).toEqual({ ok: true, degraded: true, reason: "recent health check is stale" });
    expect(healthCheck).toHaveBeenCalledTimes(2);
  });

  it("skips recall when health fails and no recent healthy check exists", async () => {
    const healthCheck = vi.fn().mockRejectedValue(new Error("timeout"));

    const result = await quickRecallPrecheck(makeClient(healthCheck), "agent-1", {
      timeoutMs: 2000,
      cacheTtlMs: 30_000,
      staleTtlMs: 300_000,
      nowMs: () => 1_000,
    });

    expect(result).toEqual({ ok: false, reason: "no recent healthy OpenViking check" });
  });
});
