import { describe, expect, it, vi } from "vitest";

import {
  commitOpenVikingSession,
  compactOpenVikingSession,
} from "../../services/context-lifecycle-service.js";
import {
  TEST_API_SESSION_KEY,
  TEST_OPENVIKING_USER_ID,
  useStrictActiveTurnFixtures,
} from "../helpers/active-turn.js";

describe("context lifecycle sender-scoped clients", () => {
  useStrictActiveTurnFixtures(["agent:main:session-1"]);
  it("uses sender-scoped client for explicit session commits", async () => {
    const staticCommitSession = vi.fn();
    const senderCommitSession = vi.fn().mockResolvedValue({
      status: "completed",
      memories_extracted: { core: 1 },
    });
    const getClient = vi.fn(async () => ({ commitSession: staticCommitSession }));
    const getClientForSender = vi.fn(async () => ({ commitSession: senderCommitSession }));

    const ok = await commitOpenVikingSession({
      sessionId: "session-1",
      sessionKey: TEST_API_SESSION_KEY,
      runtimeContext: { senderId: "wxid_A" },
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      rememberSessionAgentId: vi.fn(),
      isBypassedSession: () => false,
    });

    expect(ok).toBe(true);
    expect(getClientForSender).toHaveBeenCalledWith(TEST_OPENVIKING_USER_ID);
    expect(senderCommitSession).toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
    expect(staticCommitSession).not.toHaveBeenCalled();
  });

  it("fails explicit commits when active Turn identity is unavailable", async () => {
    const getClient = vi.fn(async () => ({ commitSession: vi.fn() }));
    const getClientForSender = vi.fn();

    await expect(commitOpenVikingSession({
      sessionId: "session-1",
      sessionKey: "agent:missing:claw-manager-api:global:direct:api:missing:conv",
      identityHashSecret: "missing-secret",
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      rememberSessionAgentId: vi.fn(),
      isBypassedSession: () => false,
    })).rejects.toThrow("API_TURN_IDENTITY_MISSING");

    expect(getClientForSender).not.toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
  });

  it("fails compact when active Turn identity is unavailable", async () => {
    const getClient = vi.fn(async () => ({
      commitSession: vi.fn(),
      getSessionContext: vi.fn(),
    }));
    const getClientForSender = vi.fn();

    await expect(compactOpenVikingSession({
      sessionId: "session-1",
      sessionKey: "agent:main:session-1",
      identityHashSecret: "missing-secret",
      tokenBudget: 1000,
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      isBypassedSession: () => false,
      diag: vi.fn(),
    })).rejects.toThrow("API_TURN_IDENTITY_MISSING");
    expect(getClientForSender).not.toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
  });
});
