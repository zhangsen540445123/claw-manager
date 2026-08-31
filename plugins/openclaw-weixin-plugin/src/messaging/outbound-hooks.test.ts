import { describe, expect, it } from "vitest";

import { buildWeixinMessageSendingContext } from "./outbound-hooks.js";

describe("Weixin message_sending context", () => {
  it("passes the routed session and run identity to the hook context", () => {
    expect(buildWeixinMessageSendingContext({
      accountId: "account-1",
      sessionKey: "agent:user_1:openclaw-weixin:global:direct:peer-1",
      runId: "run-1",
    })).toEqual({
      channelId: "openclaw-weixin",
      accountId: "account-1",
      sessionKey: "agent:user_1:openclaw-weixin:global:direct:peer-1",
      runId: "run-1",
    });
  });

  it("does not invent heartbeat metadata for ordinary messages", () => {
    const context = buildWeixinMessageSendingContext({
      accountId: "account-1",
      sessionKey: "agent:user_1:openclaw-weixin:global:direct:peer-1",
      runId: "run-1",
    });

    expect(context).not.toHaveProperty("isHeartbeat");
    expect(context).not.toHaveProperty("trigger");
    expect(context).not.toHaveProperty("runKind");
    expect(context).not.toHaveProperty("runType");
  });

  it("includes explicit heartbeat metadata when supplied by the runtime", () => {
    expect(buildWeixinMessageSendingContext({
      accountId: "account-1",
      sessionKey: "agent:user_1:heartbeat",
      runId: "run-heartbeat",
      isHeartbeat: true,
      trigger: "heartbeat",
      runKind: "heartbeat",
      runType: "heartbeat",
    })).toEqual({
      channelId: "openclaw-weixin",
      accountId: "account-1",
      sessionKey: "agent:user_1:heartbeat",
      runId: "run-heartbeat",
      isHeartbeat: true,
      trigger: "heartbeat",
      runKind: "heartbeat",
      runType: "heartbeat",
    });
  });
});