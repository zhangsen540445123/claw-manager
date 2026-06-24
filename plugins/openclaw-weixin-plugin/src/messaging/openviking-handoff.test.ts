import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { readOpenVikingSenderHandoff, writeOpenVikingSenderHandoff } from "./openviking-handoff.js";

describe("OpenViking sender handoff", () => {
  it("stores derived identity for a session without persisting the raw sender id", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-handoff-"));
    try {
      await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:wx_sender_ABC",
        senderId: "wx_sender_ABC",
        secret: "identity-secret",
      });

      const handoff = await readOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:wx_sender_ABC",
        secret: "identity-secret",
      });

      expect(handoff?.openVikingUserId).toMatch(/^wx_[a-f0-9]{32}$/);
      expect(handoff?.senderHash).toMatch(/^[a-f0-9]{32}$/);

      const raw = await readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8");
      expect(raw).not.toContain("wx_sender_ABC");
      expect(raw).not.toContain("agent:main:openclaw-weixin:bot:direct");
      expect(raw).toContain(handoff!.openVikingUserId);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("does not write a handoff when sender identity is missing", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-handoff-"));
    try {
      const wrote = await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:someone",
        senderId: "",
        secret: "identity-secret",
      });

      expect(wrote).toBe(false);
      await expect(readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8"))
        .rejects.toMatchObject({ code: "ENOENT" });
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
});
