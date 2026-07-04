import { describe, expect, it, vi } from "vitest";

import { createOpenVikingClientRuntime } from "../../plugin/openviking-client-runtime.js";

function okResponse(result: unknown): Response {
  return new Response(JSON.stringify({ status: "ok", result }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("createOpenVikingClientRuntime sender scoping", () => {
  it("resolves an explicit sender-scoped user key from Claw Manager broker and uses only X-API-Key for data APIs", async () => {
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

    const client = await runtime.getClientForSender({
      openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    });
    await client?.find("hello", { limit: 1 });

    const [brokerUrl, brokerInit] = transport.mock.calls[0] as [string, RequestInit];
    expect(brokerUrl).toBe("http://claw-manager-api:8080/api/internal/openviking/users/resolve");
    expect((brokerInit.headers as Headers).get("Authorization")).toBe("Bearer broker-token");
    expect(JSON.parse(String(brokerInit.body))).toEqual({
      openvikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    });

    const [, dataInit] = transport.mock.calls[1] as [string, RequestInit];
    const headers = dataInit.headers as Headers;
    expect(headers.get("X-API-Key")).toBe("user-key-a");
    expect(headers.get("X-OpenViking-Account")).toBeNull();
    expect(headers.get("X-OpenViking-User")).toBeNull();
  });

  it("caches broker-resolved user keys by explicit OpenViking user id", async () => {
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

    await (await runtime.getClientForSender("wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))?.find("hello", { limit: 1 });
    await (await runtime.getClientForSender("wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))?.find("again", { limit: 1 });

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

  it("treats explicit api OpenViking user id strings as scoped identities", async () => {
    const transport = vi.fn().mockImplementation(async (url: string) => {
      if (url === "http://claw-manager-api:8080/api/internal/openviking/users/resolve") {
        return okResponse({ openvikingUserId: "api_0123456789abcdef0123456789abcdef", userKey: "user-key-api" });
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

    const client = await runtime.getClientForSender("api_0123456789abcdef0123456789abcdef");
    await client?.find("hello", { limit: 1 });

    const [, brokerInit] = transport.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(brokerInit.body))).toEqual({
      openvikingUserId: "api_0123456789abcdef0123456789abcdef",
    });
    const [, dataInit] = transport.mock.calls[1] as [string, RequestInit];
    expect((dataInit.headers as Headers).get("X-API-Key")).toBe("user-key-api");
  });

  it("does not derive OpenViking user ids from API channel sender ids", async () => {
    const apiHash = "0123456789abcdef0123456789abcdef";
    const transport = vi.fn();
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

    const client = await runtime.getClientForSender(`api:${apiHash}`);
    expect(client).toBeUndefined();
    expect(transport).not.toHaveBeenCalled();
  });

  it("does not derive OpenViking user ids from raw channel sender ids", async () => {
    const transport = vi.fn();
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
    expect(client).toBeUndefined();
    expect(transport).not.toHaveBeenCalled();
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
