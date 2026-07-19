import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  clearActiveOpenVikingTurn,
  markActiveTurnExplicitMemoryStore,
  readActiveOpenVikingTurn,
  registerActiveOpenVikingTurn,
  shouldSkipAfterTurnAutoCapture,
} from "../../active-turn-identity.js";
import { registerApiOpenVikingTurn } from "../../../openclaw-api-channel-plugin/src/openviking-handoff.js";
import { registerWechatOpenVikingTurn } from "../../../openclaw-weixin-plugin/src/messaging/openviking-handoff.js";

const SECRET = "active-turn-test-secret";
const USER_A = "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const USER_B = "wx_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const stateDirs: string[] = [];

async function stateDir(): Promise<string> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "ov-active-turn-"));
  stateDirs.push(dir);
  return dir;
}

afterEach(async () => {
  await Promise.all(stateDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

describe("active OpenViking turn identity", () => {
  it("resolves only the exact active sessionKey", async () => {
    const dir = await stateDir();
    await registerActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      channel: "api",
      sessionKey: "agent:user-a:api:conversation-a",
      agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      openVikingUserId: USER_A,
      cmTraceId: "cmtrace_a",
      requestId: "request-a",
    });

    expect(readActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      sessionKey: "agent:user-a:api:conversation-a",
      expectedChannel: "api",
    })).toMatchObject({ openVikingUserId: USER_A, status: "active" });
    expect(() => readActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      sessionKey: "agent:user-a:api:conversation-b",
      expectedChannel: "api",
    })).toThrowError("API_TURN_IDENTITY_MISSING");
  });

  it("rejects channel mismatch and expired turns with explicit codes", async () => {
    const dir = await stateDir();
    await registerActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      channel: "wechat",
      sessionKey: "agent:user-a:wechat:one",
      agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      openVikingUserId: USER_A,
      runId: "run-a",
      createdAt: new Date(Date.now() - 31 * 60_000).toISOString(),
    });

    expect(() => readActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      sessionKey: "agent:user-a:wechat:one",
      expectedChannel: "api",
    })).toThrowError("TURN_IDENTITY_CHANNEL_MISMATCH");
    expect(() => readActiveOpenVikingTurn({
      stateDir: dir,
      secret: SECRET,
      sessionKey: "agent:user-a:wechat:one",
      expectedChannel: "wechat",
      maxAgeMs: 30 * 60_000,
    })).toThrowError("TURN_IDENTITY_EXPIRED");
  });

  it("isolates concurrent turns and clears only the completed turn", async () => {
    const dir = await stateDir();
    await Promise.all([
      registerActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, channel: "api", sessionKey: "session-a", agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", openVikingUserId: USER_A, requestId: "a" }),
      registerActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, channel: "wechat", sessionKey: "session-b", agentId: "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", openVikingUserId: USER_B, runId: "b" }),
    ]);

    await clearActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: "session-a" });

    expect(() => readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: "session-a", expectedChannel: "api" }))
      .toThrowError("API_TURN_IDENTITY_MISSING");
    expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: "session-b", expectedChannel: "wechat" }).openVikingUserId)
      .toBe(USER_B);
  });

  it("serializes active-turn writes across both channels and the OpenViking reader module", async () => {
    const dir = await stateDir();
    const writes: Array<Promise<unknown>> = [];
    for (let index = 0; index < 20; index += 1) {
      writes.push(registerApiOpenVikingTurn({
        stateDir: dir,
        secret: SECRET,
        sessionKey: `api-session-${index}`,
        agentId: `user_api_${index}`,
        openVikingUserId: USER_A,
        requestId: `api-${index}`,
      }));
      writes.push(registerWechatOpenVikingTurn({
        stateDir: dir,
        secret: SECRET,
        sessionKey: `wechat-session-${index}`,
        agentId: `user_wechat_${index}`,
        openVikingUserId: USER_B,
        runId: `wechat-${index}`,
      }));
      writes.push(registerActiveOpenVikingTurn({
        stateDir: dir,
        secret: SECRET,
        channel: "api",
        sessionKey: `plugin-session-${index}`,
        agentId: `user_plugin_${index}`,
        openVikingUserId: USER_A,
        requestId: `plugin-${index}`,
      }));
    }

    await Promise.all(writes);

    for (let index = 0; index < 20; index += 1) {
      expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: `api-session-${index}`, expectedChannel: "api" }).requestId)
        .toBe(`api-${index}`);
      expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: `wechat-session-${index}`, expectedChannel: "wechat" }).runId)
        .toBe(`wechat-${index}`);
      expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: `plugin-session-${index}`, expectedChannel: "api" }).requestId)
        .toBe(`plugin-${index}`);
    }
  }, 15_000);

  it("records explicit memory store success and failure for afterTurn decisions", async () => {
    const dir = await stateDir();
    await registerActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, channel: "api", sessionKey: "session-a", agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", openVikingUserId: USER_A, requestId: "a" });

    await markActiveTurnExplicitMemoryStore({ stateDir: dir, secret: SECRET, sessionKey: "session-a", outcome: "stored" });
    expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: "session-a", expectedChannel: "api" }))
      .toMatchObject({ explicitMemoryStored: true, explicitMemoryStoreOutcome: "stored" });

    await markActiveTurnExplicitMemoryStore({ stateDir: dir, secret: SECRET, sessionKey: "session-a", outcome: "failed" });
    expect(readActiveOpenVikingTurn({ stateDir: dir, secret: SECRET, sessionKey: "session-a", expectedChannel: "api" }))
      .toMatchObject({ explicitMemoryStored: false, explicitMemoryStoreOutcome: "failed" });
  });

  it("suppresses afterTurn compensation after explicit store stored, failed, or pending", () => {
    expect(shouldSkipAfterTurnAutoCapture({ explicitMemoryStoreOutcome: "stored" } as any)).toBe(true);
    expect(shouldSkipAfterTurnAutoCapture({ explicitMemoryStoreOutcome: "failed" } as any)).toBe(true);
    expect(shouldSkipAfterTurnAutoCapture({ explicitMemoryStoreOutcome: "pending" } as any)).toBe(true);
    expect(shouldSkipAfterTurnAutoCapture({} as any)).toBe(false);
  });
});
