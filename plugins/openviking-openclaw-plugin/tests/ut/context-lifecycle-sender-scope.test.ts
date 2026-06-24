import { describe, expect, it, vi } from "vitest";

import {
  commitOpenVikingSession,
  compactOpenVikingSession,
} from "../../services/context-lifecycle-service.js";

describe("context lifecycle sender-scoped clients", () => {
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
      runtimeContext: { senderId: "wxid_A" },
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      rememberSessionAgentId: vi.fn(),
      isBypassedSession: () => false,
    });

    expect(ok).toBe(true);
    expect(getClientForSender).toHaveBeenCalledWith("wxid_A");
    expect(senderCommitSession).toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
    expect(staticCommitSession).not.toHaveBeenCalled();
  });

  it("skips explicit commits when sender identity is unavailable in sender-scoped mode", async () => {
    const getClient = vi.fn(async () => ({ commitSession: vi.fn() }));
    const getClientForSender = vi.fn();

    const ok = await commitOpenVikingSession({
      sessionId: "session-1",
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      rememberSessionAgentId: vi.fn(),
      isBypassedSession: () => false,
    });

    expect(ok).toBe(false);
    expect(getClientForSender).not.toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
  });

  it("skips compact when sender identity is unavailable in sender-scoped mode", async () => {
    const getClient = vi.fn(async () => ({
      commitSession: vi.fn(),
      getSessionContext: vi.fn(),
    }));
    const getClientForSender = vi.fn();

    const result = await compactOpenVikingSession({
      sessionId: "session-1",
      sessionKey: "agent:main:session-1",
      tokenBudget: 1000,
      getClient,
      getClientForSender,
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      resolveAgentId: () => "agent-main",
      isBypassedSession: () => false,
      diag: vi.fn(),
    });

    expect(result).toMatchObject({
      ok: true,
      compacted: false,
      reason: "identity_missing",
    });
    expect(getClientForSender).not.toHaveBeenCalled();
    expect(getClient).not.toHaveBeenCalled();
  });
});
