import { describe, expect, it, vi } from "vitest";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { buildApiInboundContext, dispatchApiMessage, writeApiQueueHeartbeat } from "./channel.js";

describe("buildApiInboundContext", () => {
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
});
