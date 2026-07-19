import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import {
  buildApiInboundContext,
  dispatchApiMessage,
  ensureApiUserAgentBinding,
  handleApiAssistantAgentEvent,
  imageRequestIntentReason,
  monitorApiQueue,
  reportApiTrace,
  requestsImageGeneration,
  registerApiAgentEventStream,
  resetApiAgentEventStreamsForTest,
  startApiStreamHeartbeat,
  writeApiQueueHeartbeat,
} from "./channel.js";

let activeTurnStateDir: string | undefined;

beforeEach(async () => {
  activeTurnStateDir = await fs.mkdtemp(path.join(os.tmpdir(), "api-channel-active-turn-"));
  vi.stubEnv("OPENCLAW_STATE_DIR", activeTurnStateDir);
  vi.stubEnv("OPENVIKING_IDENTITY_HASH_SECRET", "api-channel-test-secret");
});

afterEach(async () => {
  if (activeTurnStateDir) await fs.rm(activeTurnStateDir, { recursive: true, force: true });
  activeTurnStateDir = undefined;
  vi.unstubAllEnvs();
});

describe("API trace reporting", () => {
  it("marks image requests without retaining request text", () => {
    expect(requestsImageGeneration("随机添加待办后生成一张图片")).toBe(true);
    expect(requestsImageGeneration("新增一个待办")).toBe(false);
    expect(requestsImageGeneration("不要生成图片，只给我文字建议")).toBe(false);
    expect(requestsImageGeneration("你支持图片吗？")).toBe(false);
    expect(requestsImageGeneration("帮我制定这个月的目标九宫格")).toBe(false);
    expect(imageRequestIntentReason("不要生成图片，只给我文字建议")).toBe("negated");
    expect(imageRequestIntentReason("生成一张目标海报")).toBe("explicit_request");
  });

  it("writes internal stream heartbeats until stopped", async () => {
    vi.useFakeTimers();
    try {
      const events: string[] = [];
      const stop = startApiStreamHeartbeat(async () => {
        events.push("heartbeat");
      }, 1000);

      await vi.advanceTimersByTimeAsync(2500);
      expect(events).toEqual(["heartbeat", "heartbeat"]);

      await stop();
      await vi.advanceTimersByTimeAsync(2000);
      expect(events).toEqual(["heartbeat", "heartbeat"]);
    } finally {
      vi.useRealTimers();
    }
  });
  it("uses the broker token without including user identity fields", async () => {
    const fetcher = vi.fn(async () => new Response("{\"accepted\":true}", { status: 200 }));

    await reportApiTrace({
      traceId: "cmtrace_api123",
      requestId: "req-1",
      stage: "api.request.received",
      status: "completed",
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: fetcher as typeof fetch,
    });

    const [url, init] = fetcher.mock.calls[0]! as unknown as [string, RequestInit];
    expect(url).toContain("/api/internal/integration-traces/events");
    expect(init.headers).toMatchObject({ authorization: "Bearer broker-secret", "X-CM-Trace-Id": "cmtrace_api123" });
    expect(String(init.body)).not.toContain("openid");
    expect(JSON.parse(String(init.body))).toMatchObject({ component: "api-channel", stage: "api.request.received", channel: "api" });
  });
});

function makeRuntime(overrides: {
  dispatchReplyFromConfig?: ReturnType<typeof vi.fn>;
  createReplyDispatcherWithTyping?: ReturnType<typeof vi.fn>;
} = {}) {
  return {
    routing: {
      resolveAgentRoute: vi.fn(({ cfg, peer }) => {
        const binding = (cfg.bindings ?? []).find((entry: any) => entry.match?.peer?.id === peer.id);
        const agentId = binding?.agentId ?? "main";
        return {
          agentId,
          sessionKey: `agent:${agentId}:claw-manager-api:global:direct:${peer.id}`,
          mainSessionKey: `agent:${agentId}:main`,
        };
      }),
    },
    session: {
      resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
      recordInboundSession: vi.fn().mockResolvedValue(undefined),
    },
    reply: {
      finalizeInboundContext: vi.fn((ctx) => ctx),
      resolveHumanDelayConfig: vi.fn(() => ({})),
      createReplyDispatcherWithTyping: overrides.createReplyDispatcherWithTyping ?? vi.fn(() => ({
        dispatcher: {},
        replyOptions: {},
        markDispatchIdle: vi.fn(),
      })),
      withReplyDispatcher: vi.fn(async ({ run }) => run()),
      dispatchReplyFromConfig: overrides.dispatchReplyFromConfig ?? vi.fn(async () => {}),
    },
  };
}

function persistedApiConfig(
  agentId = "user_f9db8c63722f76a920d852d85f502177",
  senderHash = "f9db8c63722f76a920d852d85f502177",
) {
  const home = process.env.OPENCLAW_HOME?.trim() || os.homedir();
  return {
    session: {},
    agents: {
      list: [{
        id: agentId,
        workspace: path.join(home, ".openclaw", `workspace-${agentId}`),
        agentDir: path.join(home, ".openclaw", "agents", agentId, "agent"),
        tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] },
      }],
    },
    bindings: [{
      agentId,
      match: {
        channel: "claw-manager-api",
        accountId: "global",
        peer: { kind: "direct", id: `api:${senderHash}` },
      },
    }],
  } as any;
}

function persistedApiConfigForUsers(users: Array<{ agentId: string; senderHash: string }>) {
  const home = process.env.OPENCLAW_HOME?.trim() || os.homedir();
  return {
    session: {},
    agents: { list: users.map(({ agentId }) => ({
      id: agentId,
      workspace: path.join(home, ".openclaw", `workspace-${agentId}`),
      agentDir: path.join(home, ".openclaw", "agents", agentId, "agent"),
      tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] },
    })) },
    bindings: users.map(({ agentId, senderHash }) => ({
      agentId,
      match: { channel: "claw-manager-api", accountId: "global", peer: { kind: "direct", id: `api:${senderHash}` } },
    })),
  } as any;
}

async function waitUntil(condition: () => boolean | Promise<boolean>, timeoutMs = 1500): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await condition()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("timed out waiting for condition");
}

describe("buildApiInboundContext", () => {
  it("forwards assistant agent event deltas for the matching API session", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-event",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:other:conv",
      data: { delta: "错" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { delta: "你" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { delta: "好" },
    });

    expect(chunks).toEqual(["你", "好"]);
  });

  it("forwards trusted miniapp artifact tool results for the matching API session", async () => {
    const artifacts: Array<Record<string, unknown>> = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-artifact",
      runId: "req-artifact",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async () => {},
      onArtifact: async (artifact) => artifacts.push(artifact),
    });

    await handleApiAssistantAgentEvent({
      stream: "tool",
      runId: "req-artifact",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      seq: 7,
      data: {
        toolName: "miniapp_artifact",
        result: { details: { artifact: { id: "artifact-1", type: "image_report", miniappPath: "/pages/html-viewer/index?contentKey=x" } } },
      },
    });

    expect(artifacts).toEqual([{ id: "artifact-1", type: "image_report", miniappPath: "/pages/html-viewer/index?contentKey=x" }]);
  });

  it("rejects a delayed artifact from another run on the same API session", async () => {
    const artifacts: Array<Record<string, unknown>> = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-current",
      runId: "req-current",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async () => {},
      onArtifact: async (artifact) => artifacts.push(artifact),
    });

    const handled = await handleApiAssistantAgentEvent({
      stream: "tool",
      runId: "req-previous",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: {
        toolName: "miniapp_artifact",
        details: { artifact: { id: "artifact-old", type: "html_report", miniappPath: "/pages/html-viewer/index?contentKey=old" } },
      },
    });

    expect(handled).toBe(false);
    expect(artifacts).toEqual([]);
  });

  it("derives a delta from cumulative assistant agent event text", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-text",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "你好" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "你好呀" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "你好呀" },
    });

    expect(chunks).toEqual(["你好", "呀"]);
  });

  it("preserves a legitimate repeated character in cumulative assistant text", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-repeated-character",
      runId: "run-agent-repeated-character",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-repeated-character",
      data: { text: "Skil", delta: "l" },
      seq: 1,
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-repeated-character",
      data: { text: "Skill", delta: "l" },
      seq: 2,
    });

    expect(chunks.join("")).toBe("Skill");
  });

  it("prefers the monotonic assistant text suffix over an overlapping explicit delta", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-overlap",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "确认接口响应", delta: "响应" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "确认接口响应头", delta: "响应头" },
    });

    expect(chunks).toEqual(["确认接口响应", "头"]);
  });

  it("trims overlap between adjacent assistant event chunks", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-overlap-trim",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { delta: "必要的" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { delta: "的跨域配置" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { delta: "。" },
    });

    expect(chunks).toEqual(["必要的", "跨域配置", "。"]);
  });

  it("keeps the emitted transcript as the overlap baseline for segmented text events", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-segmented-text-overlap",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "1. 先确认" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      data: { text: "确认服务端响应头" },
    });

    expect(chunks).toEqual(["1. 先确认", "服务端响应头"]);
  });

  it("uses assistant agent event deltas by runId when sessionKey is absent", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-event-without-session",
      runId: "run-agent-event-without-session",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-without-session",
      data: { delta: "你" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-without-session",
      data: { delta: "好" },
    });

    expect(chunks).toEqual(["你", "好"]);
  });

  it("deduplicates assistant agent events by runId and seq", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-event-dedupe",
      runId: "run-agent-event-dedupe",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-dedupe",
      data: { delta: "你" },
      seq: 1,
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-dedupe",
      data: { delta: "你" },
      seq: 1,
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-dedupe",
      data: { delta: "好" },
      seq: 2,
    });

    expect(chunks).toEqual(["你", "好"]);
  });

  it("preserves repeated single-character deltas when events have no duplicate seq", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-tail-dedupe",
      runId: "run-agent-tail-dedupe",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    for (const delta of ["甲", "乙", "乙", "丙"]) {
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        runId: "run-agent-tail-dedupe",
        data: { delta },
      });
    }

    expect(chunks).toEqual(["甲", "乙", "乙", "丙"]);
  });

  it("uses explicit delta to correct duplicated suffixes in cumulative text events", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-cumulative-duplicate",
      runId: "run-agent-cumulative-duplicate",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-duplicate",
      data: { text: "甲乙丙丁戊己庚辛" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-duplicate",
      data: { text: "甲乙丙丁戊己庚辛壬壬癸", delta: "壬癸" },
    });

    expect(chunks).toEqual(["甲乙丙丁戊己庚辛", "壬癸"]);
  });

  it("preserves repeated leading characters in cumulative text suffixes without explicit delta", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-cumulative-leading-duplicate",
      runId: "run-agent-cumulative-leading-duplicate",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-leading-duplicate",
      data: { text: "甲乙丙丁戊" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-leading-duplicate",
      data: { text: "甲乙丙丁戊己己庚辛壬癸" },
    });

    expect(chunks).toEqual(["甲乙丙丁戊", "己己庚辛壬癸"]);
  });

  it("preserves repeated internal characters in cumulative text suffixes without explicit delta", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-cumulative-internal-duplicate",
      runId: "run-agent-cumulative-internal-duplicate",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-internal-duplicate",
      data: { text: "甲乙丙丁戊己" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-cumulative-internal-duplicate",
      data: { text: "甲乙丙丁戊己庚庚辛壬癸" },
    });

    expect(chunks).toEqual(["甲乙丙丁戊己", "庚庚辛壬癸"]);
  });

  it("does not emit non-monotonic cumulative assistant text without an explicit delta", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-non-monotonic-text",
      runId: "run-agent-non-monotonic-text",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-non-monotonic-text",
      data: { text: "甲乙丙丁戊" },
    });
    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-non-monotonic-text",
      data: { text: "庚辛壬癸" },
    });

    expect(chunks).toEqual(["甲乙丙丁戊"]);
  });

  it("ignores assistant agent events without matching sessionKey or runId", async () => {
    const chunks: string[] = [];
    resetApiAgentEventStreamsForTest();
    registerApiAgentEventStream({
      requestId: "req-agent-event-a",
      runId: "run-agent-event-a",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv-a",
      onDelta: async (text) => chunks.push(text),
    });
    registerApiAgentEventStream({
      requestId: "req-agent-event-b",
      runId: "run-agent-event-b",
      sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv-b",
      onDelta: async (text) => chunks.push(text),
    });

    await handleApiAssistantAgentEvent({
      stream: "assistant",
      runId: "run-agent-event-other",
      data: { delta: "错" },
    });

    expect(chunks).toEqual([]);
  });

  it("uses deliver output to complete partial assistant agent-event text", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const dispatchReplyFromConfig = vi.fn(async () => {
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        sessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api:global:direct:api:f9db8c63722f76a920d852d85f502177:convhash",
        data: { delta: "你" },
      });
      await deliverReply?.({ text: "你好" });
    });
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "user_f9db8c63722f76a920d852d85f502177",
          sessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:main",
        })),
      },
      session: {
        resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
        recordInboundSession: vi.fn().mockResolvedValue(undefined),
      },
      reply: {
        finalizeInboundContext: vi.fn((ctx) => ctx),
        resolveHumanDelayConfig: vi.fn(() => ({})),
        createReplyDispatcherWithTyping: vi.fn((opts) => {
          deliverReply = opts.deliver;
          return {
            dispatcher: {},
            replyOptions: {},
            markDispatchIdle: vi.fn(),
          };
        }),
        withReplyDispatcher: vi.fn(async ({ run }) => run()),
        dispatchReplyFromConfig,
      },
    };

    const result = await dispatchApiMessage({
      requestId: "req-agent-stream",
      agentId: "user_f9db8c63722f76a920d852d85f502177",
      message: "hello",
      openVikingUserId: "wx_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: persistedApiConfig(
        "user_f9db8c63722f76a920d852d85f502177",
        "f9db8c63722f76a920d852d85f502177",
      ),
      channelRuntime: runtime as any,
      onDelta: async (text) => chunks.push(text),
    });

    expect(chunks).toEqual(["你", "好"]);
    expect(result.text).toBe("你好");
    expect(result.streamDiagnostics).toMatchObject({
      streamMode: "agent-events+deliver-fallback",
      agentEventDeltaCount: 1,
      deliverDeltaCount: 1,
      deltaCount: 2,
    });
  });

  it("routes dispatch assistant events by the injected runId", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const dispatchReplyFromConfig = vi.fn(async ({ replyOptions }) => {
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        runId: replyOptions.runId,
        data: { delta: "你" },
      });
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        runId: "other-run",
        data: { delta: "错" },
      });
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        runId: replyOptions.runId,
        data: { delta: "好" },
      });
      await deliverReply?.({ text: "你好" });
    });
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "user_f9db8c63722f76a920d852d85f502177",
          sessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:main",
        })),
      },
      session: {
        resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
        recordInboundSession: vi.fn().mockResolvedValue(undefined),
      },
      reply: {
        finalizeInboundContext: vi.fn((ctx) => ctx),
        resolveHumanDelayConfig: vi.fn(() => ({})),
        createReplyDispatcherWithTyping: vi.fn((opts) => {
          deliverReply = opts.deliver;
          return {
            dispatcher: {},
            replyOptions: {},
            markDispatchIdle: vi.fn(),
          };
        }),
        withReplyDispatcher: vi.fn(async ({ run }) => run()),
        dispatchReplyFromConfig,
      },
    };

    const result = await dispatchApiMessage({
      requestId: "req-run-id-stream",
      agentId: "user_f9db8c63722f76a920d852d85f502177",
      message: "hello",
      openVikingUserId: "wx_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: persistedApiConfig(
        "user_f9db8c63722f76a920d852d85f502177",
        "f9db8c63722f76a920d852d85f502177",
      ),
      channelRuntime: runtime as any,
      onDelta: async (text) => chunks.push(text),
    });

    expect(chunks).toEqual(["你", "好"]);
    expect(result.text).toBe("你好");
    expect(dispatchReplyFromConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        replyOptions: expect.objectContaining({
          runId: "req-run-id-stream",
        }),
      }),
    );
  });

  it("propagates explicit OpenViking identity and sender fields", () => {
    const ctx = buildApiInboundContext({
      agentId: "user_0123456789abcdef0123456789abcdef",
      message: "hello",
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      senderHash: "0123456789abcdef0123456789abcdef",
      conversationHash: "abcdef0123456789",
    });

    expect(ctx.SessionKey).toBe("api:0123456789abcdef0123456789abcdef:abcdef0123456789");
    expect(ctx.From).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.To).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.openVikingUserId).toBe("wx_0123456789abcdef0123456789abcdef");
    expect(ctx.openvikingUserId).toBe("wx_0123456789abcdef0123456789abcdef");
    expect(ctx.SenderId).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.senderId).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.requesterSenderId).toBe("api:0123456789abcdef0123456789abcdef");
  });

  it("rejects missing identity instead of falling back to default user", () => {
    expect(() =>
      buildApiInboundContext({
        agentId: "user_0123456789abcdef0123456789abcdef",
        message: "hello",
        senderHash: "0123456789abcdef0123456789abcdef",
        conversationHash: "abcdef0123456789",
      }),
    ).toThrow("openVikingUserId");
  });

  it("writes a queue heartbeat that the manager can use when channels.start is temporarily unavailable", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-"));

    await writeApiQueueHeartbeat(root, true);

    const raw = await fs.readFile(path.join(root, "status.json"), "utf8");
    const status = JSON.parse(raw) as Record<string, unknown>;
    expect(status.running).toBe(true);
    expect(typeof status.updatedAt).toBe("string");
    expect(typeof status.updatedAtEpochMs).toBe("number");
  });

  it("processes different API users concurrently in the resident monitor", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-monitor-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const root = path.join(home, ".openclaw", "claw-manager-api");
    const requests = path.join(root, "requests");
    await fs.mkdir(requests, { recursive: true });
    await fs.writeFile(path.join(requests, "req-user-a.json"), JSON.stringify({
      requestId: "req-user-a",
      agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      message: "hello a",
      openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      conversationHash: "conv_a",
    }), "utf8");
    await fs.writeFile(path.join(requests, "req-user-b.json"), JSON.stringify({
      requestId: "req-user-b",
      agentId: "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      message: "hello b",
      openVikingUserId: "wx_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      senderHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      conversationHash: "conv_b",
    }), "utf8");

    const activeUsers = new Set<string>();
    let sawOverlap = false;
    let dispatchStarted = 0;
    let releaseDispatch!: () => void;
    const dispatchGate = new Promise<void>((resolve) => {
      releaseDispatch = resolve;
    });
    const abortController = new AbortController();
    const runtime = makeRuntime({
      dispatchReplyFromConfig: vi.fn(async ({ ctx }) => {
        const user = String(ctx.openVikingUserId);
        activeUsers.add(user);
        dispatchStarted += 1;
        if (activeUsers.size >= 2) {
          sawOverlap = true;
          releaseDispatch();
        }
        await dispatchGate;
        activeUsers.delete(user);
      }),
    });

    const monitor = monitorApiQueue({
      cfg: persistedApiConfigForUsers([
        { agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
        { agentId: "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", senderHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
      ]),
      channelRuntime: runtime as any,
      abortSignal: abortController.signal,
      log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    await waitUntil(() => sawOverlap || dispatchStarted >= 2);
    expect(sawOverlap).toBe(true);
    abortController.abort();
    releaseDispatch();
    await monitor;
  });

  it("serializes requests for the same API user in the resident monitor", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-monitor-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const root = path.join(home, ".openclaw", "claw-manager-api");
    const requests = path.join(root, "requests");
    await fs.mkdir(requests, { recursive: true });
    for (const requestId of ["req-same-user-1", "req-same-user-2"]) {
      await fs.writeFile(path.join(requests, `${requestId}.json`), JSON.stringify({
        requestId,
        agentId: "user_cccccccccccccccccccccccccccccccc",
        message: "hello",
        openVikingUserId: "wx_cccccccccccccccccccccccccccccccc",
        senderHash: "cccccccccccccccccccccccccccccccc",
        conversationHash: requestId,
      }), "utf8");
    }

    let activeCount = 0;
    let maxActiveCount = 0;
    let startedCount = 0;
    let releaseFirst!: () => void;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const abortController = new AbortController();
    const runtime = makeRuntime({
      dispatchReplyFromConfig: vi.fn(async () => {
        activeCount += 1;
        startedCount += 1;
        maxActiveCount = Math.max(maxActiveCount, activeCount);
        if (startedCount === 1) {
          await firstGate;
        }
        activeCount -= 1;
      }),
    });

    const monitor = monitorApiQueue({
      cfg: persistedApiConfig(
        "user_cccccccccccccccccccccccccccccccc",
        "cccccccccccccccccccccccccccccccc",
      ),
      channelRuntime: runtime as any,
      abortSignal: abortController.signal,
      log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    await waitUntil(() => startedCount === 1);
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(startedCount).toBe(1);
    releaseFirst();
    await waitUntil(() => startedCount === 2);
    expect(maxActiveCount).toBe(1);
    abortController.abort();
    await monitor;
  });

  it("logs only hashed OpenViking identity and session identifiers around API dispatch", async () => {
    const info = vi.fn();
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "user_f9db8c63722f76a920d852d85f502177",
          sessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:main",
        })),
      },
      session: {
        resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
        recordInboundSession: vi.fn().mockResolvedValue(undefined),
      },
      reply: {
        finalizeInboundContext: vi.fn((ctx) => ctx),
        resolveHumanDelayConfig: vi.fn(() => ({})),
        createReplyDispatcherWithTyping: vi.fn((opts) => {
          deliverReply = opts.deliver;
          return {
            dispatcher: {},
            replyOptions: {},
            markDispatchIdle: vi.fn(),
          };
        }),
        withReplyDispatcher: vi.fn(async ({ run }) => run()),
        dispatchReplyFromConfig: vi.fn(async () => {
          await deliverReply?.({ text: "ok" });
        }),
      },
    };

    await dispatchApiMessage({
      requestId: "req-1",
      agentId: "user_f9db8c63722f76a920d852d85f502177",
      message: "hello",
      openVikingUserId: "wx_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: persistedApiConfig(),
      channelRuntime: runtime as any,
      log: { info },
    });

    expect(info).toHaveBeenCalledWith(
      expect.stringContaining("api dispatch route"),
    );
    const output = info.mock.calls.flat().join("\n");
    expect(output).toContain("openVikingUserHash=");
    expect(output).toContain("sessionKeyHash=");
    expect(output).not.toContain("wx_f9db8c63722f76a920d852d85f502177");
    expect(output).not.toContain("agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api");
    expect(output).not.toContain("hello");
  });

  it("uses a persisted API binding without mutating config during dispatch", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-agent-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const senderHash = "f9db8c63722f76a920d852d85f502177";
    const agentId = "user_f9db8c63722f76a920d852d85f502177";
    const initialCfg = persistedApiConfig(agentId, senderHash);
    let currentCfg = initialCfg;
    const runtime = makeRuntime();
    runtime.routing.resolveAgentRoute = vi.fn(({ cfg, peer }) => {
      const binding = (cfg.bindings ?? []).find((entry: any) =>
        entry.match?.channel === "claw-manager-api" &&
        entry.match?.accountId === "global" &&
        entry.match?.peer?.kind === "direct" &&
        entry.match?.peer?.id === peer.id
      );
      if (binding) {
        return {
          agentId: binding.agentId,
          sessionKey: `agent:${binding.agentId}:claw-manager-api:global:direct:${peer.id}`,
          mainSessionKey: `agent:${binding.agentId}:main`,
          matchedBy: "binding",
        };
      }
      return {
        agentId: "main",
        sessionKey: `agent:main:claw-manager-api:global:direct:${peer.id}`,
        mainSessionKey: "agent:main:main",
        matchedBy: "default",
      };
    });
    const mutateConfigFile = vi.fn(async ({ mutate }) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result };
    });

    await dispatchApiMessage({
      requestId: "req-dynamic-agent",
      agentId,
      message: "hello",
      openVikingUserId: `wx_${senderHash}`,
      senderHash,
      conversationHash: "convhash",
      cfg: initialCfg,
      channelRuntime: runtime as any,
      configRuntime: {
        current: () => currentCfg,
        mutateConfigFile,
      },
    });

    expect(mutateConfigFile).not.toHaveBeenCalled();
    expect(currentCfg).toBe(initialCfg);
    expect(runtime.routing.resolveAgentRoute).toHaveBeenLastCalledWith(
      expect.objectContaining({
        cfg: currentCfg,
      }),
    );
  });

  it("uses persisted bindings for concurrent API users without mutating config", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-agent-concurrent-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const senderA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const senderB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const agentA = "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const agentB = "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const initialCfg = persistedApiConfigForUsers([
      { agentId: agentA, senderHash: senderA },
      { agentId: agentB, senderHash: senderB },
    ]);
    let currentCfg = initialCfg;
    const runtime = makeRuntime();
    runtime.routing.resolveAgentRoute = vi.fn(({ cfg, peer }) => {
      const binding = (cfg.bindings ?? []).find((entry: any) =>
        entry.match?.channel === "claw-manager-api" &&
        entry.match?.accountId === "global" &&
        entry.match?.peer?.kind === "direct" &&
        entry.match?.peer?.id === peer.id
      );
      if (binding) {
        return {
          agentId: binding.agentId,
          sessionKey: `agent:${binding.agentId}:claw-manager-api:global:direct:${peer.id}`,
          mainSessionKey: `agent:${binding.agentId}:main`,
          matchedBy: "binding",
        };
      }
      return {
        agentId: "main",
        sessionKey: `agent:main:claw-manager-api:global:direct:${peer.id}`,
        mainSessionKey: "agent:main:main",
        matchedBy: "default",
      };
    });
    const mutateConfigFile = vi.fn(async ({ mutate }) => {
      const draft = structuredClone(currentCfg);
      await new Promise((resolve) => setTimeout(resolve, 25));
      const result = await mutate(draft);
      currentCfg = draft;
      return { result };
    });
    const configRuntime = {
      current: () => currentCfg,
      mutateConfigFile,
    };

    await Promise.all([
      dispatchApiMessage({
        requestId: "req-dynamic-agent-a",
        agentId: agentA,
        message: "hello a",
        openVikingUserId: `wx_${senderA}`,
        senderHash: senderA,
        conversationHash: "convhash",
        cfg: initialCfg,
        channelRuntime: runtime as any,
        configRuntime,
      }),
      dispatchApiMessage({
        requestId: "req-dynamic-agent-b",
        agentId: agentB,
        message: "hello b",
        openVikingUserId: `wx_${senderB}`,
        senderHash: senderB,
        conversationHash: "convhash",
        cfg: initialCfg,
        channelRuntime: runtime as any,
        configRuntime,
      }),
    ]);

    expect(currentCfg.agents.list.map((entry: any) => entry.id).sort()).toEqual([
      agentA,
      agentB,
    ].sort());
    expect(currentCfg.bindings.map((entry: any) => entry.agentId).sort()).toEqual([
      agentA,
      agentB,
    ].sort());
    expect(mutateConfigFile).not.toHaveBeenCalled();
  });

  it("uses one API binding while conversations get distinct sessions", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-conversations-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const agentId = "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const senderHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    let currentCfg: any = persistedApiConfig(agentId, senderHash);
    const recordInboundSession = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime();
    runtime.session.recordInboundSession = recordInboundSession;
    runtime.routing.resolveAgentRoute = vi.fn(({ cfg, peer }) => {
      const binding = (cfg.bindings ?? []).find((entry: any) => entry.match?.peer?.id === peer.id);
      return binding
        ? { agentId: binding.agentId, sessionKey: "binding-session", mainSessionKey: `agent:${binding.agentId}:main` }
        : { agentId: "main", sessionKey: "agent:main:main", mainSessionKey: "agent:main:main", matchedBy: "default" };
    });
    const mutateConfigFile = vi.fn(async ({ mutate }) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result };
    });
    const base = {
      agentId,
      message: "hello",
      openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      senderHash,
      cfg: currentCfg,
      channelRuntime: runtime as any,
      configRuntime: { current: () => currentCfg, mutateConfigFile },
    };

    await dispatchApiMessage({ ...base, requestId: "conversation-a", conversationHash: "conv-a" });
    await dispatchApiMessage({ ...base, requestId: "conversation-b", conversationHash: "conv-b" });

    expect(currentCfg.bindings).toHaveLength(1);
    expect(currentCfg.bindings[0].match.peer.id).toBe(`api:${senderHash}`);
    expect(mutateConfigFile).not.toHaveBeenCalled();
    expect(recordInboundSession.mock.calls.map(([call]) => call.sessionKey)).toEqual([
      `agent:${agentId}:claw-manager-api:global:direct:api:${senderHash}:conv-a`,
      `agent:${agentId}:claw-manager-api:global:direct:api:${senderHash}:conv-b`,
    ]);
  });

  it("provisions one user agent idempotently with a WeChat binding", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-ensure-agent-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const presetDir = path.join(home, ".openclaw", "claw-manager");
    await fs.mkdir(presetDir, { recursive: true });
    await fs.writeFile(path.join(presetDir, "workspace-preset.json"), JSON.stringify({
      agentsMd: "# Unified preset agents\n",
      soulMd: "# Unified preset soul\n",
      identityMd: "# Unified preset identity\n",
      toolsMd: "# Unified preset tools\n",
      heartbeatMd: "# Unified preset heartbeat\n",
      userMd: "# Unified preset user\n",
      version: 1,
    }), "utf8");
    const agentId = "user_0123456789abcdef0123456789abcdef";
    const initialCfg = { agents: { list: [] }, bindings: [] } as any;
    let currentCfg = initialCfg;
    const mutateConfigFile = vi.fn(async ({ mutate }) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result };
    });
    const params = {
      cfg: initialCfg,
      configRuntime: { current: () => currentCfg, mutateConfigFile },
      agentId,
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      wechatAccountId: "bot-a",
      wechatPeerId: "wechat-peer-a",
    };

    await ensureApiUserAgentBinding(params);
    await ensureApiUserAgentBinding(params);

    expect(currentCfg.agents.list).toHaveLength(1);
    expect(currentCfg.agents.list[0]).toMatchObject({
      id: agentId,
      workspace: path.join(home, ".openclaw", `workspace-${agentId}`),
      tools: { deny: expect.arrayContaining(["write", "edit", "apply_patch", "exec", "process"]) },
    });
    expect(currentCfg.bindings).toEqual([{
      agentId,
      match: {
        channel: "openclaw-weixin",
        accountId: "bot-a",
        peer: { kind: "direct", id: "wechat-peer-a" },
      },
    }]);
    await expect(fs.readFile(path.join(home, ".openclaw", `workspace-${agentId}`, "AGENTS.md"), "utf8"))
      .resolves.toBe("# Unified preset agents\n");
  });

  it("rejects legacy ensure_user_agent queue operations without mutating config", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-ensure-queue-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const root = path.join(home, ".openclaw", "claw-manager-api");
    await fs.mkdir(path.join(root, "requests"), { recursive: true });
    await fs.writeFile(path.join(root, "requests", "ensure-1.json"), JSON.stringify({
      operation: "ensure_user_agent",
      requestId: "ensure-1",
      agentId: "user_0123456789abcdef0123456789abcdef",
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      wechatAccountId: "bot-a",
      wechatPeerId: "wechat-peer-a",
    }), "utf8");
    let currentCfg: any = { agents: { list: [] }, bindings: [] };
    const mutateConfigFile = vi.fn(async ({ mutate }) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result };
    });
    const abortController = new AbortController();
    const dispatchReplyFromConfig = vi.fn();
    const monitor = monitorApiQueue({
      cfg: currentCfg,
      channelRuntime: makeRuntime({ dispatchReplyFromConfig }) as any,
      configRuntime: { current: () => currentCfg, mutateConfigFile },
      abortSignal: abortController.signal,
      log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    const responsePath = path.join(root, "responses", "ensure-1.json");
    await waitUntil(async () => fs.access(responsePath).then(() => true, () => false));
    abortController.abort();
    await monitor;

    expect(JSON.parse(await fs.readFile(responsePath, "utf8"))).toMatchObject({
      ok: false,
      requestId: "ensure-1",
    });
    expect(dispatchReplyFromConfig).not.toHaveBeenCalled();
    expect(mutateConfigFile).not.toHaveBeenCalled();
    expect(currentCfg.agents.list).toHaveLength(0);
  });

  it("rejects missing or malformed persisted user identities", async () => {
    const runtime = makeRuntime();
    await expect(dispatchApiMessage({
      requestId: "req-missing-agent",
      message: "hello",
      openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
      senderHash: "0123456789abcdef0123456789abcdef",
      conversationHash: "convhash",
      cfg: persistedApiConfig(),
      channelRuntime: runtime as any,
    })).rejects.toThrow("agentId");
    await expect(dispatchApiMessage({
      requestId: "req-invalid-openviking",
      agentId: "user_0123456789abcdef0123456789abcdef",
      message: "hello",
      openVikingUserId: "wx_invalid",
      senderHash: "0123456789abcdef0123456789abcdef",
      conversationHash: "convhash",
      cfg: persistedApiConfig(),
      channelRuntime: runtime as any,
    })).rejects.toThrow("openVikingUserId");
  });

  it("streams delivered reply chunks when assistant agent events are unavailable", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    let releaseDispatch: (() => void) | undefined;
    let markDelivered: (() => void) | undefined;
    const dispatchGate = new Promise<void>((resolve) => { releaseDispatch = resolve; });
    const delivered = new Promise<void>((resolve) => { markDelivered = resolve; });
    const dispatchReplyFromConfig = vi.fn(async () => {
      await deliverReply?.({ text: "你" });
      await deliverReply?.({ text: "好" });
      await deliverReply?.({ text: "好" });
      markDelivered?.();
      await dispatchGate;
    });
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "user_f9db8c63722f76a920d852d85f502177",
          sessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:user_f9db8c63722f76a920d852d85f502177:main",
        })),
      },
      session: {
        resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
        recordInboundSession: vi.fn().mockResolvedValue(undefined),
      },
      reply: {
        finalizeInboundContext: vi.fn((ctx) => ctx),
        resolveHumanDelayConfig: vi.fn(() => ({})),
        createReplyDispatcherWithTyping: vi.fn((opts) => {
          deliverReply = opts.deliver;
          return {
            dispatcher: {},
            replyOptions: { existing: true },
            markDispatchIdle: vi.fn(),
          };
        }),
        withReplyDispatcher: vi.fn(async ({ run }) => run()),
        dispatchReplyFromConfig,
      },
    };

    const resultPromise = dispatchApiMessage({
      requestId: "req-stream",
      agentId: "user_f9db8c63722f76a920d852d85f502177",
      message: "hello",
      openVikingUserId: "wx_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: persistedApiConfig(),
      channelRuntime: runtime as any,
      onDelta: async (text) => {
        chunks.push(text);
      },
    });

    await delivered;
    expect(chunks).toEqual(["你", "好", "好"]);
    releaseDispatch?.();
    const result = await resultPromise;
    expect(result.text).toBe("你好好");
    expect(result.streamDiagnostics).toMatchObject({
      streamMode: "deliver-fallback",
      agentEventDeltaCount: 0,
      deliverDeltaCount: 3,
      deltaCount: 3,
    });
    expect(dispatchReplyFromConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        replyOptions: expect.objectContaining({
          existing: true,
          runId: "req-stream",
        }),
      }),
    );
  });

  it("deduplicates cumulative deliver payloads and returns the canonical transcript", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const runtime = makeRuntime({
      dispatchReplyFromConfig: vi.fn(async () => {
        await deliverReply?.({ text: "已把计划整理好。" });
        await deliverReply?.({ text: "已把计划整理好。\n查看链接：https://example.test/view" });
        await deliverReply?.({ text: "已把计划整理好。\n查看链接：https://example.test/view\n可以继续调整。" });
      }),
      createReplyDispatcherWithTyping: vi.fn((opts) => {
        deliverReply = opts.deliver;
        return { dispatcher: {}, replyOptions: {}, markDispatchIdle: vi.fn() };
      }),
    });

    const result = await dispatchApiMessage({
      requestId: "req-cumulative-deliver",
      agentId: "user_f9db8c63722f76a920d852d85f502177",
      message: "hello",
      openVikingUserId: "wx_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: persistedApiConfig(),
      channelRuntime: runtime as any,
      onDelta: async (text) => chunks.push(text),
    });

    expect(chunks.join("")).toBe("已把计划整理好。\n查看链接：https://example.test/view\n可以继续调整。");
    expect(result.text).toBe("已把计划整理好。\n查看链接：https://example.test/view\n可以继续调整。");
  });
});
