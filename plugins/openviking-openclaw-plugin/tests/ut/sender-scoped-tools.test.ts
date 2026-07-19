import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import contextEnginePlugin from "../../index.js";
import { readActiveOpenVikingTurn, registerActiveOpenVikingTurn } from "../../active-turn-identity.js";

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
const EXPLICIT_USER_ID = "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const stateDirs: string[] = [];

async function registerApiTurn(sessionKey: string, openVikingUserId = EXPLICIT_USER_ID): Promise<void> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "ov-tool-turn-"));
  stateDirs.push(dir);
  process.env.OPENCLAW_STATE_DIR = dir;
  process.env.OPENVIKING_IDENTITY_HASH_SECRET = SECRET;
  await registerActiveOpenVikingTurn({
    stateDir: dir, secret: SECRET, channel: "api", sessionKey,
    agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", openVikingUserId, requestId: "request-a",
  });
}

afterEach(async () => {
  delete process.env.OPENCLAW_STATE_DIR;
  delete process.env.OPENVIKING_IDENTITY_HASH_SECRET;
  await Promise.all(stateDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

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

function expectTenantHeaders(init: RequestInit | undefined, openVikingUserId: string): void {
  const headers = new Headers(init?.headers);
  expect(headers.get("X-OpenViking-Account")).toBe(ACCOUNT_ID);
  expect(headers.get("X-OpenViking-User")).toBe(openVikingUserId);
}

describe("sender-scoped OpenViking tools", () => {
  it("uses only the active turn selected by exact ToolContext.sessionKey", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:a:conv";
    await registerApiTurn(sessionKey);
    const openVikingTransport = vi.fn(async (url: string) => url.endsWith("/api/v1/search/find")
      ? okResponse({ memories: [], resources: [], skills: [], total: 0 })
      : okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport);

    await factoryTools.get("ov_search")!({
      sessionKey,
      openVikingUserId: "wx_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      senderId: "must-not-be-used",
    }).execute("tc-search", { query: "preference", uri: "viking://resources" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) => String(url).endsWith("/api/v1/search/find")) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });
  it("uses explicit OpenViking user id for ov_search tenant headers", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:search:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
    });
    await tool.execute("tc-search", { query: "preference", uri: "viking://resources" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/search/find"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });

  it("rejects raw requesterSenderId/senderId instead of deriving tenant headers", async () => {
    const openVikingTransport = vi.fn(async () => okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("ov_search")!({
      sessionId: "session-1",
      agentId: "main",
      senderId: "sender-b",
      requesterSenderId: "sender-a",
    });
    await expect(tool.execute("tc-search", { query: "preference", uri: "viking://resources" }))
      .rejects.toThrow("API_TURN_IDENTITY_MISSING");
    expect(openVikingTransport).not.toHaveBeenCalled();
  });

  it("uses explicit OpenViking user id for memory_store writes", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:store:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
    });
    await tool.execute("tc-store", { text: "remember blue" });

    expect(readActiveOpenVikingTurn({ sessionKey, secret: SECRET, expectedChannel: "api" }))
      .toMatchObject({ explicitMemoryStored: true, explicitMemoryStoreOutcome: "stored" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).includes("/api/v1/sessions/") && String(url).includes("/messages"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });

  it("marks failed explicit memory_store so afterTurn cannot compensate it", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:failed-store:conv";
    await registerApiTurn(sessionKey);
    const openVikingTransport = vi.fn(async (url: string) => {
      if (url.includes("/messages")) return okResponse({ session_id: "stored-session" });
      if (url.endsWith("/commit")) return okResponse({ status: "failed", error: "provider rejected" });
      return okResponse({});
    });
    const { factoryTools } = setupPlugin(openVikingTransport);

    const result = await factoryTools.get("memory_store")!({ sessionKey }).execute("tc-store", { text: "remember blue" }) as ToolResult;

    expect(result.details).toMatchObject({ action: "failed" });
    expect(readActiveOpenVikingTurn({ sessionKey, secret: SECRET, expectedChannel: "api" }))
      .toMatchObject({ explicitMemoryStored: false, explicitMemoryStoreOutcome: "failed" });
  });

  it("rejects user memory tools without sender identity and sends no OpenViking request", async () => {
    const openVikingTransport = vi.fn(async () => okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport);

    const tool = factoryTools.get("memory_recall")!({
      sessionId: "session-1",
      agentId: "main",
    });
    await expect(tool.execute("tc-recall", { query: "what color" }))
      .rejects.toThrow("API_TURN_IDENTITY_MISSING");
    expect(openVikingTransport).not.toHaveBeenCalled();
  });

  it("uses explicit OpenViking user id for archive reads", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:archive:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
    });
    await tool.execute("tc-archive", { archiveId: "archive_001" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).includes("/api/v1/sessions/") && String(url).includes("/archives/archive_001"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });

  it("uses explicit OpenViking user id for add_skill imports", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:skill:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
    });
    await tool.execute("tc-add-skill", { data: "name: demo\n" });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/skills"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });

  it("rejects add_resource without sender identity and sends no OpenViking request", async () => {
    const openVikingTransport = vi.fn(async () => okResponse({}));
    const { factoryTools } = setupPlugin(openVikingTransport, { enableAddResourceTool: true });

    const tool = factoryTools.get("add_resource")!({
      sessionId: "session-1",
      agentId: "main",
    });
    await expect(tool.execute("tc-add-resource", {
      source: "https://example.com/docs",
      to: "viking://resources/docs",
    })).rejects.toThrow("API_TURN_IDENTITY_MISSING");
    expect(openVikingTransport).not.toHaveBeenCalled();
  });

  it("uses explicit OpenViking user id for recall trace content reads", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:trace:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
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
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });

  it("uses explicit OpenViking user id for ov-search slash command tenant headers", async () => {
    const sessionKey = "agent:user:claw-manager-api:global:direct:api:command:conv";
    await registerApiTurn(sessionKey);
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
      sessionKey,
    });

    const [, init] = openVikingTransport.mock.calls.find(([url]) =>
      String(url).endsWith("/api/v1/search/find"),
    ) as [string, RequestInit];
    expectTenantHeaders(init, EXPLICIT_USER_ID);
  });
});
