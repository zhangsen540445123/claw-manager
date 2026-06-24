import { createHmac } from "node:crypto";

import { describe, expect, it, vi } from "vitest";

import contextEnginePlugin from "../../index.js";
import { OPENVIKING_IDENTITY_UNAVAILABLE_MESSAGE } from "../../plugin/openviking-runtime-utils.js";

type ToolDef = {
  name: string;
  execute: (toolCallId: string, params: Record<string, unknown>) => Promise<unknown>;
};

type CommandDef = {
  name: string;
  handler: (ctx: Record<string, unknown>) => Promise<{ text: string; details?: Record<string, unknown> }>;
};

type ToolResult = {
  content: Array<{ type: string; text: string }>;
  details?: Record<string, unknown>;
};

const SECRET = "sender-scoped-test-secret";
const ACCOUNT_ID = "claw-manager";

function expectedOpenVikingUserId(senderId: string): string {
  const hash = createHmac("sha256", SECRET).update(senderId.trim()).digest("hex").slice(0, 32);
  return `wx_${hash}`;
}

function okResponse(result: unknown): Response {
  return new Response(JSON.stringify({ status: "ok", result }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function setupPlugin(
  openVikingTransport = vi.fn(async () => okResponse({})),
  pluginConfigOverrides: Record<string, unknown> = {},
) {
  const factoryTools = new Map<string, (ctx: Record<string, unknown>) => ToolDef>();
  const commands = new Map<string, CommandDef>();
  const api = {
    pluginConfig: {
      mode: "remote",
      baseUrl: "http://127.0.0.1:1933",
      accountId: ACCOUNT_ID,
      identityHashSecret: SECRET,
      autoCapture: false,
      autoRecall: false,
      traceRecall: false,
      ...pluginConfigOverrides,
    },
    openVikingTransport,
    logger: {
      info: vi.fn(),
      warn: vi.fn(),
      error: vi.fn(),
      debug: vi.fn(),
    },
    registerTool: vi.fn((toolOrFactory: unknown) => {
      if (typeof toolOrFactory === "function") {
        const factory = toolOrFactory as (ctx: Record<string, unknown>) => ToolDef;
        const probe = factory({ sessionId: "probe-session", senderId: "probe-sender" });
        factoryTools.set(probe.name, factory);
      }
    }),
    registerCommand: vi.fn((command: unknown) => {
      const cmd = command as CommandDef;
      commands.set(cmd.name, cmd);
    }),
    registerHttpRoute: vi.fn(),
    registerService: vi.fn(),
    registerContextEngine: vi.fn(),
    registerGatewayMethod: vi.fn(),
    on: vi.fn(),
  };

  contextEnginePlugin.register(api as any);
  return { api, factoryTools, commands, openVikingTransport };
}

function expectTenantHeaders(init: RequestInit | undefined, senderId: string): void {
  const headers = new Headers(init?.headers);
  expect(headers.get("X-OpenViking-Account")).toBe(ACCOUNT_ID);
  expect(headers.get("X-OpenViking-User")).toBe(expectedOpenVikingUserId(senderId));
}

describe("sender-scoped OpenViking tools", () => {
  it("uses requesterSenderId before senderId for ov_search tenant headers", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.endsWith("/api/v1/search/find")) {
        return okResponse({ memories: [], resources: [], skills: [], total: 0 });
      }
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("ov_search")!({
      sessionId: "session-1",
      agentId: "main",
      senderId: "sender-b",
      requesterSenderId: "sender-a",
    });
    await tool.execute("tc-search", { query: "preference", uri: "viking://resources" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/search/find"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "sender-a");
  });

  it("falls back to senderId for memory_store writes", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.includes("/messages")) {
        return okResponse({ session_id: "stored-session" });
      }
      if (url.endsWith("/commit")) {
        return okResponse({ status: "completed", archived: false, memories_extracted: { core: 1 } });
      }
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("memory_store")!({
      sessionId: "session-1",
      agentId: "main",
      senderId: "sender-fallback",
    });
    await tool.execute("tc-store", { text: "remember blue" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).includes("/api/v1/sessions/") && String(url).includes("/messages"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "sender-fallback");
  });

  it("rejects user memory tools without sender identity and sends no OpenViking request", async () => {
    const openVikingTransport = vi.fn(async () => okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("memory_recall")!({
      sessionId: "session-1",
      agentId: "main",
    });
    const result = await tool.execute("tc-recall", { query: "what color" }) as ToolResult;

    expect(result.content[0]?.text).toBe(OPENVIKING_IDENTITY_UNAVAILABLE_MESSAGE);
    expect(openVikingTransport).not.toHaveBeenCalled();
  });

  it("uses sender-scoped headers for archive reads", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.includes("/archives/archive_001")) {
        return okResponse({ archive_id: "archive_001", abstract: "summary", overview: "", messages: [] });
      }
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("ov_archive_expand")!({
      sessionId: "session-1",
      agentId: "main",
      senderId: "archive-sender",
    });
    await tool.execute("tc-archive", { archiveId: "archive_001" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).includes("/api/v1/sessions/") && String(url).includes("/archives/archive_001"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "archive-sender");
  });

  it("uses sender-scoped headers for add_skill imports", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.endsWith("/api/v1/skills")) {
        return okResponse({ uri: "viking://user/skills/demo", name: "demo" });
      }
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("add_skill")!({
      sessionId: "session-1",
      agentId: "main",
      requesterSenderId: "skill-sender",
    });
    await tool.execute("tc-add-skill", { data: "name: demo\n" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/skills"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "skill-sender");
  });

  it("rejects add_resource without sender identity and sends no OpenViking request", async () => {
    const openVikingTransport = vi.fn(async () => okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport, { enableAddResourceTool: true });

    const tool = factoryTools.get("add_resource")!({
      sessionId: "session-1",
      agentId: "main",
    });
    const result = await tool.execute("tc-add-resource", {
      source: "https://example.com/docs",
      to: "viking://resources/docs",
    }) as ToolResult;

    expect(result.content[0]?.text).toBe(OPENVIKING_IDENTITY_UNAVAILABLE_MESSAGE);
    expect(openVikingTransport).not.toHaveBeenCalled();
  });

  it("uses sender-scoped headers for recall trace content reads", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      const requestUrl = new URL(url);
      if (requestUrl.pathname === "/api/v1/search/find") {
        return okResponse({
          memories: [],
          resources: [{
            uri: "viking://resources/spec.md",
            abstract: "Spec abstract",
            score: 0.9,
          }],
          skills: [],
          total: 1,
        });
      }
      if (requestUrl.pathname === "/api/v1/content/read") {
        return okResponse("Full spec content");
      }
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport, { traceRecall: true });

    const ctx = {
      sessionId: "session-1",
      agentId: "main",
      senderId: "trace-sender",
    };
    await factoryTools.get("ov_search")!(ctx).execute("tc-search", {
      query: "spec",
      uri: "viking://resources",
    });
    const traceResult = await factoryTools.get("ov_recall_trace")!(ctx).execute("tc-trace", {
      source: "ov_search",
      includeContent: true,
    }) as ToolResult;

    expect(traceResult.content[0]?.text).toContain("ov_search");
    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).includes("/api/v1/content/read"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "trace-sender");
  });

  it("uses requesterSenderId for ov-search slash command tenant headers", async () => {
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.endsWith("/api/v1/search/find")) {
        return okResponse({ memories: [], resources: [], skills: [], total: 0 });
      }
      return okResponse({});
    });
    const { commands } = setupPlugin(openVikingTransport);

    await commands.get("ov-search")!.handler({
      args: "docs --uri viking://resources",
      sessionId: "session-1",
      agentId: "main",
      senderId: "command-sender-b",
      requesterSenderId: "command-sender-a",
    });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/search/find"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, "command-sender-a");
  });
});
