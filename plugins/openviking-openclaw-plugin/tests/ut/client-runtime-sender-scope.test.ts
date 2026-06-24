import { describe, expect, it, vi } from "vitest";

import { createOpenVikingClientRuntime } from "../../plugin/openviking-client-runtime.js";
import { resolveSenderIdentity } from "../../identity.js";

function okResponse(result: unknown): Response {
  return new Response(JSON.stringify({ status: "ok", result }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("createOpenVikingClientRuntime sender scoping", () => {
  it("resolves a sender-scoped user key from Claw Manager broker and uses only X-API-Key for data APIs", async () => {
    const transport = vi.fn().mockImplementation(async (url: string) => {
      if (url === "http://claw-manager-api:8080/api/internal/openviking/users/resolve") {
        return okResponse({
          accountId: "claw-manager",
          openvikingUserId: "wx_cached",
          userKey: "user-key-a",
          created: true,
        });
      }
      return okResponse({ items: [] });
    });
    const runtime = createOpenVikingClientRuntime({
      cfg: {
        baseUrl: "http://127.0.0.1:1933",
        apiKey: "",
        peer_role: "assistant",
        peer_prefix: "agent",
        timeoutMs: 5000,
        accountId: "claw-manager",
        userId: "",
        identityHashSecret: "secret",
        clawManagerInternalBaseUrl: "http://claw-manager-api:8080",
        openVikingBrokerToken: "broker-token",
        logFindRequests: false,
      },
      rawPeerPrefix: "",
      logger: { info: vi.fn() },
      transport,
    });

    const client = await runtime.getClientForSender("wxid_Alpha");
    await client?.find("hello", { limit: 1 });

    const expectedIdentity = resolveSenderIdentity("wxid_Alpha", "secret");
    const [brokerUrl, brokerInit] = transport.mock.calls[0] as [string, RequestInit];
    expect(brokerUrl).toBe("http://claw-manager-api:8080/api/internal/openviking/users/resolve");
    expect((brokerInit.headers as Headers).get("Authorization")).toBe("Bearer broker-token");
    expect(JSON.parse(String(brokerInit.body))).toEqual({
      senderId: "wxid_Alpha",
      openvikingUserId: expectedIdentity?.openVikingUserId,
    });

    const [, dataInit] = transport.mock.calls[1] as [string, RequestInit];
    const headers = dataInit.headers as Headers;
    expect(headers.get("X-API-Key")).toBe("user-key-a");
    expect(headers.get("X-OpenViking-Account")).toBeNull();
    expect(headers.get("X-OpenViking-User")).toBeNull();
  });

  it("caches broker-resolved user keys by derived OpenViking user id", async () => {
    const transport = vi.fn().mockImplementation(async (url: string) => {
      if (url === "http://claw-manager-api:8080/api/internal/openviking/users/resolve") {
        return okResponse({ openvikingUserId: "wx_cached", userKey: "user-key-a" });
      }
      return okResponse({ items: [] });
    });
    const runtime = createOpenVikingClientRuntime({
      cfg: {
        baseUrl: "http://127.0.0.1:1933",
        apiKey: "",
        peer_role: "assistant",
        peer_prefix: "agent",
        timeoutMs: 5000,
        accountId: "claw-manager",
        userId: "",
        identityHashSecret: "secret",
        clawManagerInternalBaseUrl: "http://claw-manager-api:8080",
        openVikingBrokerToken: "broker-token",
        logFindRequests: false,
      },
      rawPeerPrefix: "",
      logger: { info: vi.fn() },
      transport,
    });

    await (await runtime.getClientForSender("wxid_Alpha"))?.find("hello", { limit: 1 });
    await (await runtime.getClientForSender("wxid_Alpha"))?.find("again", { limit: 1 });

    expect(transport.mock.calls.filter(([url]) => url === "http://claw-manager-api:8080/api/internal/openviking/users/resolve")).toHaveLength(1);
  });

  it("resolves a broker user key from a handoff-derived OpenViking user id without raw sender id", async () => {
    const transport = vi.fn().mockImplementation(async (url: string) => {
      if (url === "http://claw-manager-api:8080/api/internal/openviking/users/resolve") {
        return okResponse({ openvikingUserId: "wx_handoff", userKey: "user-key-handoff" });
      }
      return okResponse({ items: [] });
    });
    const runtime = createOpenVikingClientRuntime({
      cfg: {
        baseUrl: "http://127.0.0.1:1933",
        apiKey: "",
        peer_role: "assistant",
        peer_prefix: "agent",
        timeoutMs: 5000,
        accountId: "claw-manager",
        userId: "",
        identityHashSecret: "secret",
        clawManagerInternalBaseUrl: "http://claw-manager-api:8080",
        openVikingBrokerToken: "broker-token",
        logFindRequests: false,
      },
      rawPeerPrefix: "",
      logger: { info: vi.fn() },
      transport,
    });

    const client = await runtime.getClientForSender({
      openVikingUserId: "wx_handoff",
      senderHash: "0123456789abcdef0123456789abcdef",
    });
    await client?.find("hello", { limit: 1 });

    const [, brokerInit] = transport.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(brokerInit.body))).toEqual({
      openvikingUserId: "wx_handoff",
    });
    const [, dataInit] = transport.mock.calls[1] as [string, RequestInit];
    expect((dataInit.headers as Headers).get("X-API-Key")).toBe("user-key-handoff");
  });

  it("does not return a client when sender identity is unavailable", async () => {
    const runtime = createOpenVikingClientRuntime({
      cfg: {
        baseUrl: "http://127.0.0.1:1933",
        apiKey: "",
        peer_role: "assistant",
        peer_prefix: "agent",
        timeoutMs: 5000,
        accountId: "claw-manager",
        userId: "",
        identityHashSecret: "secret",
        clawManagerInternalBaseUrl: "http://claw-manager-api:8080",
        openVikingBrokerToken: "broker-token",
        logFindRequests: false,
      },
      rawPeerPrefix: "",
      logger: { info: vi.fn() },
      transport: vi.fn(),
    });

    await expect(runtime.getClientForSender("   ")).resolves.toBeUndefined();
  });
});
