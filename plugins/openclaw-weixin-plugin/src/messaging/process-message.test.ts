import { describe, expect, it, vi } from "vitest";

import { attachSenderRuntimeIdentity, reportWechatTrace, requestsImageGeneration } from "./process-message.js";

describe("WeChat trace reporting", () => {
  it("marks image requests without retaining the message text", () => {
    expect(requestsImageGeneration("生成一张目标九宫格海报")).toBe(true);
    expect(requestsImageGeneration("帮我查询今日待办")).toBe(false);
  });
  it("reports media delivery failures without user identity data", async () => {
    const fetcher = vi.fn(async () => new Response("{\"accepted\":true}", { status: 200 }));
    await reportWechatTrace({
      traceId: "cmtrace_wechat123",
      stage: "wechat.media.send.failed",
      status: "failed",
      requestId: "run-1",
      elapsedMs: 35,
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: fetcher as typeof fetch,
    });

    const [, init] = fetcher.mock.calls[0]! as unknown as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toMatchObject({
      component: "wechat-plugin", stage: "wechat.media.send.failed", channel: "wechat", elapsedMs: 35,
    });
    expect(String(init.body)).not.toContain("openid");
  });
});

describe("attachSenderRuntimeIdentity", () => {
  it("adds sender identity fields to the finalized dispatch context", () => {
    const finalized = { Body: "hello" };

    const ctx = attachSenderRuntimeIdentity(finalized, "wx_sender_ABC");

    expect(ctx).toBe(finalized);
    expect(ctx.SenderId).toBe("wx_sender_ABC");
    expect(ctx.senderId).toBe("wx_sender_ABC");
    expect(ctx.requesterSenderId).toBe("wx_sender_ABC");
  });

  it("keeps identity fields empty when the inbound Weixin sender is empty", () => {
    const ctx = attachSenderRuntimeIdentity({ Body: "hello" }, "");

    expect(ctx.SenderId).toBe("");
    expect(ctx.senderId).toBe("");
    expect(ctx.requesterSenderId).toBe("");
  });
});
