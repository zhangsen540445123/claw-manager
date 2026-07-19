import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { readApiOpenVikingHandoff, writeApiOpenVikingHandoff } from "./openviking-handoff.js";
import { writeOpenVikingSenderHandoff } from "../../openclaw-weixin-plugin/src/messaging/openviking-handoff.js";

describe("API OpenViking handoff", () => {
  it("stores an explicit api user identity without persisting raw session data", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "api-ov-handoff-"));
    try {
      const wrote = await writeApiOpenVikingHandoff({
        stateDir,
        sessionKey: "agent:main:claw-manager-api:global:direct:api:raw-openid:conv",
        openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
        senderHash: "f9db8c63722f76a920d852d85f502177",
        secret: "identity-secret",
      });

      expect(wrote).toBe(true);
      const handoff = await readApiOpenVikingHandoff({
        stateDir,
        sessionKey: "agent:main:claw-manager-api:global:direct:api:raw-openid:conv",
        secret: "identity-secret",
      });

      expect(handoff).toMatchObject({
        openVikingUserId: "api_f9db8c63722f76a920d852d85f502177",
        senderHash: "f9db8c63722f76a920d852d85f502177",
      });

      const raw = await readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8");
      expect(raw).not.toContain("raw-openid");
      expect(raw).not.toContain("agent:main:claw-manager-api");
      expect(raw).toContain("api_f9db8c63722f76a920d852d85f502177");
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("does not write a handoff when required identity input is missing", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "api-ov-handoff-"));
    try {
      const wrote = await writeApiOpenVikingHandoff({
        stateDir,
        sessionKey: "agent:main:claw-manager-api:global:direct:someone",
        openVikingUserId: "",
        senderHash: "f9db8c63722f76a920d852d85f502177",
        secret: "identity-secret",
      });

      expect(wrote).toBe(false);
      await expect(readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8"))
        .rejects.toMatchObject({ code: "ENOENT" });
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("preserves all entries when multiple API requests write handoff concurrently", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "api-ov-handoff-"));
    try {
      const users = Array.from({ length: 5 }, (_, index) => ({
        sessionKey: `agent:main:claw-manager-api:global:direct:api:user-${index}:conv`,
        openVikingUserId: `api_user_${index}`,
        senderHash: `user_${index}`,
      }));

      await Promise.all(users.map((user) =>
        writeApiOpenVikingHandoff({
          stateDir,
          secret: "identity-secret",
          ...user,
        }),
      ));

      for (const user of users) {
        await expect(readApiOpenVikingHandoff({
          stateDir,
          sessionKey: user.sessionKey,
          secret: "identity-secret",
        })).resolves.toMatchObject({
          openVikingUserId: user.openVikingUserId,
          senderHash: user.senderHash,
        });
      }
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });

  it("preserves entries when API and WeChat plugins write concurrently", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "cross-channel-ov-handoff-"));
    try {
      const apiWrites = Array.from({ length: 10 }, (_, index) => writeApiOpenVikingHandoff({
        stateDir,
        sessionKey: `agent:user:api:${index}`,
        openVikingUserId: `wx_${index.toString(16).padStart(32, "0")}`,
        senderHash: index.toString(16).padStart(32, "0"),
        secret: "identity-secret",
      }));
      const wechatWrites = Array.from({ length: 10 }, (_, index) => writeOpenVikingSenderHandoff({
        stateDir,
        sessionKey: `agent:user:wechat:${index}`,
        openVikingUserId: `wx_${(index + 10).toString(16).padStart(32, "0")}`,
        secret: "identity-secret",
      }));

      await Promise.all([...apiWrites, ...wechatWrites]);

      const file = JSON.parse(await readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8"));
      expect(Object.keys(file.entries)).toHaveLength(20);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
});
