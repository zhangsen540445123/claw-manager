type Logger = {
  warn?: (message: string) => void;
  debug?: (message: string) => void;
};

type HookContext = {
  agentId?: string;
  sessionId?: string;
  sessionKey?: string;
  [key: string]: unknown;
};

type ReporterOptions = {
  enabled: boolean;
  baseUrl?: string;
  token?: string;
  instanceId?: string;
  pluginVersion?: string;
  fetchImpl?: typeof fetch;
  logger?: Logger;
};

type HookOptions = {
  on: (hookName: string, handler: (event: unknown, ctx?: HookContext) => unknown, opts?: { priority?: number }) => void;
  reporter: Pick<ReturnType<typeof createModelCallAuditReporter>, "record">;
  logger?: Logger;
};

const HOOKS = ["llm_input", "model_call_started", "model_call_ended", "llm_output"] as const;
export type ModelCallAuditEventType = (typeof HOOKS)[number];

function recordOf(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function firstString(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim();
    if (typeof value === "number" && Number.isFinite(value)) return String(value);
  }
  return "";
}

function firstValue(...values: unknown[]): unknown {
  return values.find((value) => value !== undefined && value !== null);
}

function jsonSafe(value: unknown): unknown {
  if (value === undefined || value === null) return null;
  try {
    JSON.stringify(value);
    return value;
  } catch {
    return String(value);
  }
}

function assistantTextsOutput(value: unknown): string | undefined {
  if (!Array.isArray(value)) return undefined;
  const texts = value.filter((item): item is string => typeof item === "string" && item.trim().length > 0).map((item) => item.trim());
  return texts.length > 0 ? texts.join("\n\n") : undefined;
}

function normalizeEvent(type: ModelCallAuditEventType, event: unknown, ctx: HookContext | undefined, options: ReporterOptions) {
  const body = recordOf(event);
  const context = recordOf(ctx);
  const session = recordOf(body.session);
  const usage = firstValue(body.usage, body.tokenUsage, body.tokens);
  const historyMessages = firstValue(body.historyMessages, body.history, body.messages);
  const output = firstValue(body.output, body.text, body.response, body.content, assistantTextsOutput(body.assistantTexts), body.lastAssistant);
  const imagesCount = firstValue(body.imagesCount, body.imageCount, Array.isArray(body.images) ? body.images.length : undefined);

  return {
    eventType: type,
    instanceId: options.instanceId || firstString(body.instanceId, context.instanceId),
    agentId: firstString(body.agentId, context.agentId, session.agentId),
    sessionId: firstString(body.sessionId, context.sessionId, session.sessionId),
    sessionKey: firstString(body.sessionKey, context.sessionKey, session.sessionKey),
    runId: firstString(body.runId, body.run_id, context.runId),
    callId: firstString(body.callId, body.call_id, context.callId),
    provider: firstString(body.provider, body.providerId, body.providerName),
    model: firstString(body.model, body.modelName, body.modelId),
    api: firstString(body.api, body.apiMode, body.transport),
    transport: firstString(body.transport, body.api, body.apiMode),
    apiTransport: firstString(body.apiTransport, body.transport, body.api, body.apiMode),
    systemPrompt: firstString(body.systemPrompt, body.system, body.system_message),
    prompt: firstString(body.prompt, body.input, body.userPrompt),
    historyMessages: jsonSafe(historyMessages),
    imagesCount: typeof imagesCount === "number" ? imagesCount : Number(imagesCount) || 0,
    output: jsonSafe(output),
    usage: jsonSafe(usage),
    stopReason: firstString(body.stopReason, body.finishReason, body.stop_reason),
    durationMs: typeof body.durationMs === "number" ? body.durationMs : Number(body.durationMs) || null,
    outcome: firstString(body.outcome, body.status, type === "model_call_ended" || type === "llm_output" ? "success" : "started"),
    errorCategory: firstString(body.errorCategory, body.errorCode, recordOf(body.error).category),
    errorMessage: firstString(body.errorMessage, recordOf(body.error).message),
    createdAt: firstString(body.createdAt, body.timestamp, body.ts) || new Date().toISOString(),
    pluginVersion: options.pluginVersion || "2026.6.41",
  };
}

export function createModelCallAuditReporter(options: ReporterOptions) {
  const fetchImpl = options.fetchImpl ?? fetch;
  const baseUrl = (options.baseUrl ?? "").replace(/\/+$/, "");
  const startedAtByCall = new Map<string, number>();

  return {
    async record(type: ModelCallAuditEventType, event: unknown, ctx?: HookContext): Promise<void> {
      if (!options.enabled || !baseUrl || !options.token) return;
      const payload = normalizeEvent(type, event, ctx, options);
      const callKey = payload.callId || payload.runId;
      const now = Date.now();
      if (type === "model_call_started" && callKey) {
        startedAtByCall.set(callKey, now);
      }
      if (type === "model_call_ended" && callKey && payload.durationMs == null) {
        const startedAt = startedAtByCall.get(callKey);
        if (startedAt != null) payload.durationMs = Math.max(0, now - startedAt);
        startedAtByCall.delete(callKey);
      }
      try {
        const response = await fetchImpl(`${baseUrl}/api/internal/model-call-audits`, {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${options.token}` },
          body: JSON.stringify(payload),
        });
        if (!response.ok) options.logger?.warn?.(`openviking: model-call audit rejected (${response.status})`);
      } catch (error) {
        options.logger?.warn?.(`openviking: model-call audit unavailable (${error instanceof Error ? error.message : String(error)})`);
      }
    },
  };
}

export function registerModelCallAuditHooks(options: HookOptions): void {
  for (const hook of HOOKS) {
    options.on(hook, (event, ctx) => options.reporter.record(hook, event, ctx).catch((error) => {
      options.logger?.warn?.(`openviking: model-call audit hook failed (${error instanceof Error ? error.message : String(error)})`);
    }));
  }
}
