import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach } from "vitest";

import { readActiveOpenVikingTurn, registerActiveOpenVikingTurn, runWithOpenVikingTurnContext } from "../../active-turn-identity.js";
import { openClawSessionToOvStorageId } from "../../routing/identity-routing.js";

export const TEST_IDENTITY_SECRET = "strict-active-turn-test-secret";
export const TEST_API_SESSION_KEY = "agent:user-test:claw-manager-api:global:direct:api:test:conversation";
export const TEST_WECHAT_SESSION_KEY = "agent:user-test:openclaw-weixin:bot:direct:wx_test";
export const TEST_OPENVIKING_USER_ID = "wx_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

export function strictTestSessionKey(sessionId: string): string {
  return `${TEST_API_SESSION_KEY}:${sessionId}`;
}

export function strictTestOvSessionId(sessionId: string): string {
  return openClawSessionToOvStorageId(sessionId, strictTestSessionKey(sessionId));
}

let stateDir: string | undefined;
let previousStateDir: string | undefined;
let previousSecret: string | undefined;

function channelFor(sessionKey: string): "api" | "wechat" {
  return sessionKey.includes(":openclaw-weixin:") ? "wechat" : "api";
}

export function useStrictActiveTurnFixtures(extraSessionKeys: string[] = []): void {
  beforeEach(async () => {
    previousStateDir = process.env.OPENCLAW_STATE_DIR;
    previousSecret = process.env.OPENVIKING_IDENTITY_HASH_SECRET;
    stateDir = await mkdtemp(path.join(os.tmpdir(), "ov-strict-turn-test-"));
    process.env.OPENCLAW_STATE_DIR = stateDir;
    process.env.OPENVIKING_IDENTITY_HASH_SECRET = TEST_IDENTITY_SECRET;
    const keys = new Set([TEST_API_SESSION_KEY, ...extraSessionKeys]);
    await Promise.all([...keys].map((sessionKey) => registerActiveOpenVikingTurn({
      stateDir,
      secret: TEST_IDENTITY_SECRET,
      channel: channelFor(sessionKey),
      sessionKey,
      agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      openVikingUserId: TEST_OPENVIKING_USER_ID,
      requestId: channelFor(sessionKey) === "api" ? "request-test" : undefined,
      runId: channelFor(sessionKey) === "wechat" ? "run-test" : undefined,
      turnToken: channelFor(sessionKey) === "api" ? "request-test" : "run-test",
    })));
  });

  afterEach(async () => {
    if (stateDir) await rm(stateDir, { recursive: true, force: true });
    stateDir = undefined;
    if (previousStateDir === undefined) delete process.env.OPENCLAW_STATE_DIR;
    else process.env.OPENCLAW_STATE_DIR = previousStateDir;
    if (previousSecret === undefined) delete process.env.OPENVIKING_IDENTITY_HASH_SECRET;
    else process.env.OPENVIKING_IDENTITY_HASH_SECRET = previousSecret;
  });
}

const STRICT_METHODS = new Set(["assemble", "transformContext", "afterTurn", "compact", "commitOVSession"]);

export function withDefaultActiveTurnSession<T extends object>(engine: T): T {
  return new Proxy(engine, {
    get(target, property, receiver) {
      const value = Reflect.get(target, property, receiver);
      if (typeof property !== "string" || !STRICT_METHODS.has(property) || typeof value !== "function") return value;
      return async (params: Record<string, unknown>) => {
        if (params?.__skipActiveTurnFixture === true) {
          const { __skipActiveTurnFixture: _skip, ...strictParams } = params;
          return value.call(target, strictParams);
        }
        const sessionKey = typeof params?.sessionKey === "string" && params.sessionKey
          ? params.sessionKey
          : `${TEST_API_SESSION_KEY}:${String(params?.sessionId ?? "conversation")}`;
        const identitySecret = typeof params?.identityHashSecret === "string" && params.identityHashSecret
          ? params.identityHashSecret
          : process.env.OPENVIKING_IDENTITY_HASH_SECRET ?? TEST_IDENTITY_SECRET;
        const expectedChannel = channelFor(sessionKey);
        const runtimeContext = params?.runtimeContext && typeof params.runtimeContext === "object"
          ? params.runtimeContext as Record<string, unknown>
          : {};
        const explicitUserId = [runtimeContext.openVikingUserId, runtimeContext.openvikingUserId]
          .find((candidate) => typeof candidate === "string" && /^(?:wx|api)_[0-9a-f]{32}$/.test(candidate)) as string | undefined;
        await registerActiveOpenVikingTurn({
          secret: identitySecret,
          channel: expectedChannel,
          sessionKey,
          agentId: "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          openVikingUserId: explicitUserId ?? TEST_OPENVIKING_USER_ID,
          requestId: expectedChannel === "api" ? "request-test" : undefined,
          runId: expectedChannel === "wechat" ? "run-test" : undefined,
          turnToken: expectedChannel === "api" ? "request-test" : "run-test",
        });
        return runWithOpenVikingTurnContext(expectedChannel, expectedChannel === "api" ? "request-test" : "run-test", () => value.call(target, { ...params, sessionKey }));
      };
    },
  });
}
