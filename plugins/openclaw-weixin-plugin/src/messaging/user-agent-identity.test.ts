import { describe, expect, it, vi } from "vitest";

import { resolveUserAgentIdentity } from "./user-agent-identity.js";

describe("Claw Manager user Agent identity resolver", () => {
  it("resolves a persisted Agent and OpenViking identity through the broker", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({
      agentId: "user_0123456789abcdef0123456789abcdef",
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      created: true,
    }), { status: 200, headers: { "content-type": "application/json" } }));

    const identity = await resolveUserAgentIdentity("wechat-user-secret", {
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: fetcher as typeof fetch,
    });

    expect(identity).toEqual({
      agentId: "user_0123456789abcdef0123456789abcdef",
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      created: true,
    });
    const [url, init] = fetcher.mock.calls[0]! as unknown as [string, RequestInit];
    expect(url).toBe("http://claw-manager-api:8080/api/internal/user-agents/resolve");
    expect(init.headers).toMatchObject({ authorization: "Bearer broker-secret" });
    expect(JSON.parse(String(init.body))).toEqual({
      instanceId: "inst-1",
      wechatUserId: "wechat-user-secret",
    });
  });

  it("fails closed when configuration, broker response, or identity format is invalid", async () => {
    await expect(resolveUserAgentIdentity("wechat-user-secret", { env: {} }))
      .rejects.toThrow("identity resolver configuration");
    await expect(resolveUserAgentIdentity("wechat-user-secret", {
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: vi.fn(async () => new Response("unavailable", { status: 503 })) as typeof fetch,
    })).rejects.toThrow("identity resolver rejected");
    await expect(resolveUserAgentIdentity("wechat-user-secret", {
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: vi.fn(async () => new Response(JSON.stringify({
        agentId: "main",
        openVikingUserId: "wx_invalid",
      }), { status: 200 })) as typeof fetch,
    })).rejects.toThrow("invalid identity resolver response");
  });

  it("aborts a stalled resolver request after the configured timeout", async () => {
    const fetcher = vi.fn((_url: string | URL | Request, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), { once: true });
    }));

    await expect(resolveUserAgentIdentity("wechat-user-secret", {
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: fetcher as typeof fetch,
      timeoutMs: 10,
    })).rejects.toThrow("identity resolver timed out");

    const [, init] = fetcher.mock.calls[0]! as unknown as [string, RequestInit];
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(init.signal?.aborted).toBe(true);
  });
});
