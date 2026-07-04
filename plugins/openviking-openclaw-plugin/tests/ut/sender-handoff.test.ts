import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { readOpenVikingSenderHandoff, writeOpenVikingSenderHandoff } from "../../sender-handoff.js";

describe("OpenViking sender handoff", () => {
  it("stores only explicit OpenViking user identity for a session key", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-sender-handoff-"));
    try {
      const wrote = await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:claw-manager-api:global:direct:api:sender:conv",
        openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        secret: "identity-secret",
      });

      expect(wrote).toBe(true);
      await expect(readOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:claw-manager-api:global:direct:api:sender:conv",
        secret: "identity-secret",
      })).resolves.toMatchObject({
        openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      });
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("does not derive OpenViking identity from raw senderId and salt", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-sender-handoff-"));
    try {
      const wrote = await writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: "agent:main:openclaw-weixin:bot:direct:wxid_alpha",
        senderId: "wxid_alpha",
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
