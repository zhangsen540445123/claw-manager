import { describe, expect, it, vi } from "vitest";
import { createModelCallAuditReporter, registerModelCallAuditHooks } from "../../model-call-audit.js";

describe("model call audit hooks", () => {
  it("posts the full llm input with context", async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
    const reporter = createModelCallAuditReporter({
      enabled: true,
      baseUrl: "http://api:8080",
      token: "secret",
      instanceId: "inst-1",
      fetchImpl,
    });

    await reporter.record("llm_input", {
      runId: "run-1", sessionId: "session-1", provider: "openai", model: "gpt-5.6",
      systemPrompt: "system", prompt: "<relevant-memories>memory</relevant-memories>",
      historyMessages: [{ role: "user", content: "你好" }], imagesCount: 0,
    }, { agentId: "agent-main", sessionKey: "agent:agent-main:session-1" });

    expect(fetchImpl).toHaveBeenCalledWith("http://api:8080/api/internal/model-call-audits", expect.objectContaining({
      method: "POST", headers: { "content-type": "application/json", authorization: "Bearer secret" },
    }));
    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(body).toMatchObject({
      eventType: "llm_input",
      instanceId: "inst-1",
      prompt: expect.stringContaining("relevant-memories"),
      apiTransport: "",
      pluginVersion: "2026.6.41",
    });
    expect(body.historyMessages).toEqual([{ role: "user", content: "你好" }]);
  });

  it("stores OpenClaw llm_output assistantTexts as the model output", async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
    const reporter = createModelCallAuditReporter({
      enabled: true, baseUrl: "http://api", token: "t", instanceId: "i", fetchImpl,
    });

    await reporter.record("llm_output", {
      runId: "run-1", sessionId: "session-1", provider: "openai", model: "gpt-5.6",
      assistantTexts: ["第一段回复", "2017"],
    });

    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(body.output).toBe("第一段回复\n\n2017");
  });

  it("computes duration for model_call_ended from started callId when missing", async () => {
    vi.useFakeTimers();
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
    const reporter = createModelCallAuditReporter({
      enabled: true, baseUrl: "http://api", token: "t", instanceId: "i", fetchImpl,
    });

    await reporter.record("model_call_started", { runId: "r", callId: "c" });
    vi.advanceTimersByTime(42);
    await reporter.record("model_call_ended", { runId: "r", callId: "c", outcome: "success" });

    const endedBody = JSON.parse(fetchImpl.mock.calls[1][1].body);
    expect(endedBody.durationMs).toBe(42);
    vi.useRealTimers();
  });

  it("registers each lifecycle hook once and failures are best effort", async () => {
    const on = vi.fn();
    const fetchImpl = vi.fn().mockRejectedValue(new Error("offline"));
    registerModelCallAuditHooks({ on, logger: { warn: vi.fn() }, reporter: createModelCallAuditReporter({
      enabled: true, baseUrl: "http://api", token: "t", instanceId: "i", fetchImpl,
    }) });
    expect(on.mock.calls.map((call) => call[0])).toEqual(["llm_input", "model_call_started", "model_call_ended", "llm_output"]);
    await on.mock.calls[0][1]({ runId: "r", prompt: "p" }, { sessionId: "s" });
    expect(fetchImpl).toHaveBeenCalled();
  });
});
