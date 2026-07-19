import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import {
  clearApiOpenVikingTurn,
  readApiOpenVikingHandoff,
  registerApiOpenVikingTurn,
  writeApiOpenVikingHandoff,
} from "./openviking-handoff.js";
import { writeOpenVikingSenderHandoff } from "../../openclaw-weixin-plugin/src/messaging/openviking-handoff.js";

describe("API OpenViking handoff", () => {
  it("registers and clears an api-only active turn", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "api-ov-turn-"));
    try {
      await registerApiOpenVikingTurn({
        stateDir,
        secret: "identity-secret",
        sessionKey: "agent:user:api:one",
        agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        cmTraceId: "cmtrace_a",
        requestId: "request-a",
      });
      const filePath = path.join(stateDir, "openviking", "active-turns.json");
      const registered = JSON.parse(await readFile(filePath, "utf8"));
      expect(Object.values(registered.entries)[0]).toMatchObject({ channel: "api", status: "active", requestId: "request-a" });

      await clearApiOpenVikingTurn({ stateDir, secret: "identity-secret", sessionKey: "agent:user:api:one", requestId: "request-a" });
      const cleared = JSON.parse(await readFile(filePath, "utf8"));
      expect(Object.keys(cleared.entries)).toHaveLength(0);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
  it("does not let an older API request clear a newer active turn", async () => {
    const stateDir = await mkdtemp(path.join(os.tmpdir(), "api-ov-turn-race-"));
    try {
      const common = { stateDir, secret: "identity-secret", sessionKey: "agent:user:api:shared", agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", openVikingUserId: "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" };
      await registerApiOpenVikingTurn({ ...common, requestId: "request-old" });
      await registerApiOpenVikingTurn({ ...common, requestId: "request-new" });
      expect(await clearApiOpenVikingTurn({ stateDir, secret: common.secret, sessionKey: common.sessionKey, requestId: "request-old" })).toBe(true);
      const file = JSON.parse(await readFile(path.join(stateDir, "openviking", "active-turns.json"), "utf8"));
      expect(Object.values(file.entries)).toEqual([expect.objectContaining({ requestId: "request-new" })]);
    } finally {
      await rm(stateDir, { recursive: true, force: true });
    }
  });
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
