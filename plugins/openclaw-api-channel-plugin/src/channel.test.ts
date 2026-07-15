import { afterEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import {
  buildApiInboundContext,
  dispatchApiMessage,
  resolveApiDynamicAgentId,
  handleApiAssistantAgentEvent,
  monitorApiQueue,
  registerApiAgentEventStream,
  resetApiAgentEventStreamsForTest,
  writeApiQueueHeartbeat,
} from "./channel.js";

afterEach(() => {
  vi.unstubAllEnvs();
});

function makeRuntime(overrides: {
  dispatchReplyFromConfig?: ReturnType<typeof vi.fn>;
} = {}) {
  return {
    routing: {
      resolveAgentRoute: vi.fn(({ peer }) => ({
        agentId: "main",
        sessionKey: `agent:main:claw-manager-api:global:direct:${peer.id}`,
        mainSessionKey: "agent:main:main",
      })),
    },
    session: {
      resolveStorePath: vi.fn(() => "/tmp/openclaw/sessions"),
      recordInboundSession: vi.fn().mockResolvedValue(undefined),
    },
    reply: {
      finalizeInboundContext: vi.fn((ctx) => ctx),
      resolveHumanDelayConfig: vi.fn(() => ({})),
      createReplyDispatcherWithTyping: vi.fn(() => ({
        dispatcher: {},
        replyOptions: {},
        markDispatchIdle: vi.fn(),
      })),
      withReplyDispatcher: vi.fn(async ({ run }) => run()),
      dispatchReplyFromConfig: overrides.dispatchReplyFromConfig ?? vi.fn(async () => {}),
    },
  };
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

  it("does not stream deliver chunks after assistant agent events have streamed", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const dispatchReplyFromConfig = vi.fn(async () => {
      await handleApiAssistantAgentEvent({
        stream: "assistant",
        sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
        data: { delta: "你" },
      });
      await deliverReply?.({ text: "你好" });
    });
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "main",
          sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:main:main",
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
      message: "hello",
      openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: { session: {} } as any,
      channelRuntime: runtime as any,
      onDelta: async (text) => chunks.push(text),
    });

    expect(chunks).toEqual(["你"]);
    expect(result.text).toBe("你好");
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
          agentId: "main",
          sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:main:main",
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
      message: "hello",
      openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: { session: {} } as any,
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
      message: "hello",
      openVikingUserId: "api_0123456789abcdef0123456789abcdef",
      senderHash: "0123456789abcdef0123456789abcdef",
      conversationHash: "abcdef0123456789",
    });

    expect(ctx.SessionKey).toBe("api:0123456789abcdef0123456789abcdef:abcdef0123456789");
    expect(ctx.From).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.To).toBe("api:0123456789abcdef0123456789abcdef:abcdef0123456789");
    expect(ctx.openVikingUserId).toBe("api_0123456789abcdef0123456789abcdef");
    expect(ctx.openvikingUserId).toBe("api_0123456789abcdef0123456789abcdef");
    expect(ctx.SenderId).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.senderId).toBe("api:0123456789abcdef0123456789abcdef");
    expect(ctx.requesterSenderId).toBe("api:0123456789abcdef0123456789abcdef");
  });

  it("rejects missing identity instead of falling back to default user", () => {
    expect(() =>
      buildApiInboundContext({
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
      message: "hello a",
      openVikingUserId: "api_user_a",
      senderHash: "user_a",
      conversationHash: "conv_a",
    }), "utf8");
    await fs.writeFile(path.join(requests, "req-user-b.json"), JSON.stringify({
      requestId: "req-user-b",
      message: "hello b",
      openVikingUserId: "api_user_b",
      senderHash: "user_b",
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
      cfg: { session: {} } as any,
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
        message: "hello",
        openVikingUserId: "api_same_user",
        senderHash: "same_user",
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
      cfg: { session: {} } as any,
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

  it("logs OpenViking identity and sessionKey around API dispatch", async () => {
    const info = vi.fn();
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "main",
          sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:main:main",
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
      message: "hello",
      openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: { session: {} } as any,
      channelRuntime: runtime as any,
      log: { info },
    });

    expect(info).toHaveBeenCalledWith(
      expect.stringContaining("api dispatch route"),
    );
    expect(info).toHaveBeenCalledWith(
      expect.stringContaining("user=api_f9db8c63722f76a920d852d85f502177"),
    );
    expect(info).toHaveBeenCalledWith(
      expect.stringContaining("sessionKey=agent:main:claw-manager-api:global:direct:api:f9db:conv"),
    );
  });

  it("creates a per-API-user agent binding before dispatching from the default route", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-agent-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const senderHash = "f9db8c63722f76a920d852d85f502177";
    const agentId = resolveApiDynamicAgentId(senderHash);
    const initialCfg = {
      session: {},
      channels: { "claw-manager-api": { enabled: true } },
      agents: { list: [] },
      bindings: [],
    } as any;
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
      message: "hello",
      openVikingUserId: `api_${senderHash}`,
      senderHash,
      conversationHash: "convhash",
      cfg: initialCfg,
      channelRuntime: runtime as any,
      configRuntime: {
        current: () => currentCfg,
        mutateConfigFile,
      },
    });

    expect(mutateConfigFile).toHaveBeenCalledTimes(1);
    expect(currentCfg.agents.list).toEqual([
      expect.objectContaining({
        id: agentId,
        workspace: path.join(home, `.openclaw`, `workspace-${agentId}`),
        agentDir: path.join(home, `.openclaw`, "agents", agentId, "agent"),
      }),
    ]);
    expect(currentCfg.bindings).toEqual([
      expect.objectContaining({
        agentId,
        match: {
          channel: "claw-manager-api",
          accountId: "global",
          peer: {
            kind: "direct",
            id: `api:${senderHash}:convhash`,
          },
        },
      }),
    ]);
    await expect(fs.access(path.join(home, ".openclaw", `workspace-${agentId}`, "BOOTSTRAP.md")))
      .rejects.toThrow();
    await expect(fs.readFile(path.join(home, ".openclaw", `workspace-${agentId}`, "AGENTS.md"), "utf8"))
      .resolves.toContain("Claw Manager API Agent");
    const state = JSON.parse(
      await fs.readFile(path.join(home, ".openclaw", `workspace-${agentId}`, ".openclaw", "workspace-state.json"), "utf8"),
    );
    expect(typeof state.setupCompletedAt).toBe("string");
    expect(runtime.routing.resolveAgentRoute).toHaveBeenLastCalledWith(
      expect.objectContaining({
        cfg: currentCfg,
      }),
    );
  });

  it("keeps dynamic agent bindings when two API users are created concurrently", async () => {
    const home = await fs.mkdtemp(path.join(os.tmpdir(), "claw-manager-api-agent-concurrent-"));
    vi.stubEnv("OPENCLAW_HOME", home);
    const senderA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const senderB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const initialCfg = {
      session: {},
      channels: { "claw-manager-api": { enabled: true } },
      agents: { list: [] },
      bindings: [],
    } as any;
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
        message: "hello a",
        openVikingUserId: `api_${senderA}`,
        senderHash: senderA,
        conversationHash: "convhash",
        cfg: initialCfg,
        channelRuntime: runtime as any,
        configRuntime,
      }),
      dispatchApiMessage({
        requestId: "req-dynamic-agent-b",
        message: "hello b",
        openVikingUserId: `api_${senderB}`,
        senderHash: senderB,
        conversationHash: "convhash",
        cfg: initialCfg,
        channelRuntime: runtime as any,
        configRuntime,
      }),
    ]);

    expect(currentCfg.agents.list.map((entry: any) => entry.id).sort()).toEqual([
      resolveApiDynamicAgentId(senderA),
      resolveApiDynamicAgentId(senderB),
    ].sort());
    expect(currentCfg.bindings.map((entry: any) => entry.agentId).sort()).toEqual([
      resolveApiDynamicAgentId(senderA),
      resolveApiDynamicAgentId(senderB),
    ].sort());
  });

  it("keeps delivered reply chunks out of the token stream and only records final text", async () => {
    const chunks: string[] = [];
    let deliverReply: ((payload: { text?: string }) => Promise<void>) | undefined;
    const dispatchReplyFromConfig = vi.fn(async () => {
      await deliverReply?.({ text: "你" });
      await deliverReply?.({ text: "好" });
    });
    const runtime = {
      routing: {
        resolveAgentRoute: vi.fn(() => ({
          agentId: "main",
          sessionKey: "agent:main:claw-manager-api:global:direct:api:f9db:conv",
          mainSessionKey: "agent:main:main",
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

    const result = await dispatchApiMessage({
      requestId: "req-stream",
      message: "hello",
      openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
      senderHash: "f9db8c63722f76a920d852d85f502177",
      conversationHash: "convhash",
      cfg: { session: {} } as any,
      channelRuntime: runtime as any,
      onDelta: async (text) => {
        chunks.push(text);
      },
    });

    expect(chunks).toEqual([]);
    expect(result.text).toBe("你好");
    expect(dispatchReplyFromConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        replyOptions: expect.objectContaining({
          existing: true,
          runId: "req-stream",
        }),
      }),
    );
  });
});
