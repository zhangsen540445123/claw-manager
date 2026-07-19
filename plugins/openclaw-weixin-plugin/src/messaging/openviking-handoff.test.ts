import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import {
  clearWechatOpenVikingTurn,
  readOpenVikingSenderHandoff,
  registerWechatOpenVikingTurn,
  writeOpenVikingSenderHandoff,
} from "./openviking-handoff.js";

describe("OpenViking sender handoff", () => {
  it("registers and clears a wechat-only active turn", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "wechat-ov-turn-"));
    try {
      await registerWechatOpenVikingTurn({
        stateDir,
        secret: "identity-secret",
        sessionKey: "agent:user:wechat:one",
        agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        cmTraceId: "cmtrace_a",
        runId: "run-a",
      });
      const filePath = path.join(stateDir, "openviking", "active-turns.json");
      const registered = JSON.parse(await readFile(filePath, "utf8"));
      expect(Object.values(registered.entries)[0]).toMatchObject({ channel: "wechat", status: "active", runId: "run-a" });

      await clearWechatOpenVikingTurn({ stateDir, secret: "identity-secret", sessionKey: "agent:user:wechat:one", runId: "run-a" });
      const cleared = JSON.parse(await readFile(filePath, "utf8"));
      expect(Object.keys(cleared.entries)).toHaveLength(0);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
  it("does not let an older WeChat run clear a newer active turn", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "wechat-ov-turn-race-"));
    try {
      const common = { stateDir, secret: "identity-secret", sessionKey: "agent:user:wechat:shared", agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" };
      await registerWechatOpenVikingTurn({ ...common, runId: "run-old" });
      await registerWechatOpenVikingTurn({ ...common, runId: "run-new" });
      expect(await clearWechatOpenVikingTurn({ stateDir, secret: common.secret, sessionKey: common.sessionKey, runId: "run-old" })).toBe(true);
      const file = JSON.parse(await readFile(path.join(stateDir, "openviking", "active-turns.json"), "utf8"));
      expect(Object.values(file.entries)).toEqual([expect.objectContaining({ runId: "run-new" })]);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
  it("stores the persisted identity for a session without deriving it from the salt", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-handoff-"));
    try {
      await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:wx_sender_ABC",
        openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
        secret: "identity-secret",
      });

      const handoff = await readOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:wx_sender_ABC",
        secret: "identity-secret",
      });

      expect(handoff?.openVikingUserId).toBe("wx_0123456789abcdef0123456789abcdef");
      expect(handoff?.senderHash).toBe("0123456789abcdef0123456789abcdef");

      const raw = await readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8");
      expect(raw).not.toContain("wx_sender_ABC");
      expect(raw).not.toContain("agent:main:openclaw-weixin:bot:direct");
      expect(raw).toContain(handoff!.openVikingUserId);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("does not write a handoff when the persisted identity is missing or invalid", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-handoff-"));
    try {
      const wrote = await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:someone",
        openVikingUserId: "wx_invalid",
        secret: "identity-secret",
      });

      expect(wrote).toBe(false);
      await expect(readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8"))
        .rejects.toMatchObject({ code: "ENOENT" });
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("preserves every session entry during concurrent handoff writes", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-handoff-concurrent-"));
    try {
      const sessions = Array.from({ length: 12 }, (_, index) => `agent:user:wechat:session-${index}`);
      await Promise.all(sessions.map((sessionKey, index) => writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey,
        openVikingUserId: `wx_${index.toString(16).padStart(32, "0")}`,
        secret: "identity-secret",
      })));

      const entries = await Promise.all(sessions.map((sessionKey) => readOpenVikingSenderHandoff({
        stateDir,
        sessionKey,
        secret: "identity-secret",
      })));
      expect(entries).toHaveLength(sessions.length);
      expect(entries.every(Boolean)).toBe(true);
      expect(new Set(entries.map((entry) => entry?.openVikingUserId)).size).toBe(sessions.length);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
});
