import { describe, expect, it, vi } from "vitest";

import {
  createHeartbeatMessageSendingHook,
  isExplicitHeartbeatContext,
  isExplicitHeartbeatSession,
} from "./heartbeat-isolation.js";

describe("API heartbeat outbound isolation", () => {
  it.each([
    [{ sessionKey: "agent:user_abc:heartbeat:heartbeat" }, true],
    [{ sessionKey: "agent:user_abc:heartbeat" }, true],
    [{ sessionKey: "agent:user_abc:heartbeat-preview" }, false],
    [{ sessionKey: undefined }, false],
    [{ trigger: "heartbeat", sessionKey: "agent:user_abc:normal" }, false],
    [{ isHeartbeat: true, sessionKey: "agent:user_abc:normal" }, false],
  ])("recognizes heartbeat only from the official canonical session key: %o", (context, expected) => {
    expect(isExplicitHeartbeatContext(context)).toBe(expected);
  });

  it.each([
    ["agent:main:heartbeat", true],
    ["agent:user_abc:heartbeat", true],
    [" agent:user_abc:heartbeat ", true],
    ["agent:user_abc:claw-manager-api:global:direct:peer:heartbeat:other", false],
    ["agent:user_abc:heartbeat-preview", false],
    ["heartbeat", false],
    ["", false],
    [undefined, false],
  ])("recognizes only the official isolated heartbeat session suffix: %s", (sessionKey, expected) => {
    expect(isExplicitHeartbeatSession(sessionKey)).toBe(expected);
  });

  it.each(["2018", "-1", "HEARTBEAT_OK", "用户询问 HEARTBEAT_OK 是什么"])(
    "does not suppress ordinary API text solely because of its content: %s",
    async (content) => {
      const hook = createHeartbeatMessageSendingHook({
        channelId: "claw-manager-api",
        log: vi.fn(),
      });

      await expect(hook(
        { to: "peer", content },
        { channelId: "claw-manager-api", sessionKey: "agent:user_abc:claw-manager-api:global:direct:peer" },
      )).resolves.toBeUndefined();
    },
  );

  it("does not trust unofficial heartbeat fields when the canonical session is ordinary", async () => {
    const hook = createHeartbeatMessageSendingHook({ channelId: "claw-manager-api" });

    await expect(hook(
      { to: "peer", content: "2018" },
      {
        channelId: "claw-manager-api",
        messageProvider: "claw-manager-api",
        trigger: "heartbeat",
        isHeartbeat: true,
        sessionKey: "agent:user_abc:claw-manager-api:global:direct:peer",
      },
    )).resolves.toBeUndefined();
  });

  it("uses the official channelId instead of an unofficial messageProvider alias", async () => {
    const hook = createHeartbeatMessageSendingHook({ channelId: "claw-manager-api" });

    await expect(hook(
      { to: "peer", content: "2018" },
      {
        channelId: "other-channel",
        messageProvider: "claw-manager-api",
        sessionKey: "agent:user_abc:heartbeat",
      },
    )).resolves.toBeUndefined();
  });

  it("suppresses every outbound payload from an explicit API heartbeat session", async () => {
    const log = vi.fn();
    const hook = createHeartbeatMessageSendingHook({ channelId: "claw-manager-api", log });

    await expect(hook(
      { to: "peer", content: "2018" },
      { channelId: "claw-manager-api", sessionKey: "agent:user_abc:heartbeat", runId: "run-1" },
    )).resolves.toMatchObject({
      cancel: true,
      cancelReason: "heartbeat_direct_delivery_blocked",
    });

    const output = log.mock.calls.flat().join("\n");
    expect(output).toContain("heartbeat outbound suppressed");
    expect(output).toContain("sessionHash=");
    expect(output).not.toContain("agent:user_abc:heartbeat");
    expect(output).not.toContain("2018");
  });

  it("does not suppress another channel from this plugin's global hook", async () => {
    const hook = createHeartbeatMessageSendingHook({ channelId: "claw-manager-api" });

    await expect(hook(
      { to: "peer", content: "HEARTBEAT_OK" },
      { channelId: "other-channel", sessionKey: "agent:user_abc:heartbeat" },
    )).resolves.toBeUndefined();
  });
});
