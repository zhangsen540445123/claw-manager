// Canonical ContextEngine lifecycle service: assemble / afterTurn / compact / commit orchestration.
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { getHeapStatistics } from "node:v8";

import { DEFAULT_PHASE2_POLL_TIMEOUT_MS, type OpenVikingClient, type OVMessage } from "../client.js";
import type { EffectiveQueryConfig } from "../query-config.js";
import { buildAutoRecallContext, isIdentityProfileQuery, prepareRecallQuery } from "../auto-recall.js";
import { toJsonLog } from "../memory-ranking.js";
import { openClawSessionToOvStorageId } from "../routing/identity-routing.js";
import {
  readActiveOpenVikingTurn,
  readRegisteredOpenVikingTurnChannel,
  shouldSkipAfterTurnAutoCapture,
} from "../active-turn-identity.js";
import { extractNewTurnMessages } from "../text-utils.js";
import { estimateAgentMessageTokens, estimateTextTokens } from "../token-estimator.js";
import {
  convertToAgentMessages,
  mergeConsecutiveAssistants,
  sanitizeAgentMessagesForProvider,
  toRoleId,
  type AgentMessage,
} from "./context-message-adapter.js";

type ExtractedTurnMessage = ReturnType<typeof extractNewTurnMessages>["messages"][number];

const TURN_IDENTITY_ERROR_CODES = new Set([
  "WECHAT_TURN_IDENTITY_MISSING",
  "API_TURN_IDENTITY_MISSING",
  "TURN_IDENTITY_CHANNEL_MISMATCH",
  "TURN_IDENTITY_EXPIRED",
]);

function errorType(error: unknown): string {
  if (error instanceof Error && error.message) return error.message.split(/[\s:]/, 1)[0] || error.name;
  return typeof error === "string" ? error.split(/[\s:]/, 1)[0] || "Error" : "Error";
}

function isTurnIdentityError(error: unknown): boolean {
  return TURN_IDENTITY_ERROR_CODES.has(errorType(error));
}

function hashLogValue(value: unknown): string {
  const normalized = typeof value === "string" ? value : String(value ?? "");
  return createHash("sha256").update(normalized, "utf8").digest("hex").slice(0, 12);
}

function agentIdPreview(value: unknown): string | undefined {
  if (typeof value !== "string" || !value) return undefined;
  return value.length <= 8 ? value : `${value.slice(0, 4)}...${value.slice(-4)}`;
}

type MemoryDiagnostics = {
  rssMiB: number;
  heapTotalMiB: number;
  heapUsedMiB: number;
  externalMiB: number;
  arrayBuffersMiB: number;
  heapLimitMiB: number;
  heapUsedPct: number;
};

function memoryDiagnostics(): MemoryDiagnostics {
  const usage = process.memoryUsage();
  const heapLimit = getHeapStatistics().heap_size_limit;
  const toMiB = (bytes: number) => Math.round(bytes / 1024 / 1024);
  return {
    rssMiB: toMiB(usage.rss),
    heapTotalMiB: toMiB(usage.heapTotal),
    heapUsedMiB: toMiB(usage.heapUsed),
    externalMiB: toMiB(usage.external),
    arrayBuffersMiB: toMiB(usage.arrayBuffers),
    heapLimitMiB: toMiB(heapLimit),
    heapUsedPct: heapLimit > 0 ? Math.round((usage.heapUsed / heapLimit) * 100) : 0,
  };
}

function completionDiagnostics(startedAt: number): {
  durationMs: number;
  memoryAfter: MemoryDiagnostics;
} {
  return {
    durationMs: Math.max(0, Date.now() - startedAt),
    memoryAfter: memoryDiagnostics(),
  };
}

function safeDiagnosticData(data: Record<string, unknown>): Record<string, unknown> {
  const safe: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(data)) {
    if (["sessionKey", "sessionId", "ovSessionId"].includes(key)) safe[`${key}Hash`] = hashLogValue(value);
    else if (key === "agentId") safe.agentIdPreview = agentIdPreview(value);
    else if (key === "error") safe.errorType = errorType(value);
    else if (/prompt|abstract|content|message/i.test(key) && key !== "messagesCount") continue;
    else if (/uri/i.test(key)) safe[`${key}Hash`] = hashLogValue(value);
    else safe[key] = value;
  }
  return safe;
}

function safeDiag(diag: (event: string, id: string, data: Record<string, unknown>) => void) {
  return (event: string, id: string, data: Record<string, unknown>) =>
    diag(event, hashLogValue(id), safeDiagnosticData(data));
}

export type ContextEngineLifecycleLogger = {
  info: (msg: string) => void;
  warn?: (msg: string) => void;
};

export type CommitOpenVikingSessionParams = {
  sessionId: string;
  sessionKey?: string;
  runtimeContext?: Record<string, unknown>;
  identityHashSecret?: string;
  getClient: () => Promise<Pick<OpenVikingClient, "commitSession">>;
  getClientForSender?: (senderId: unknown) => Promise<Pick<OpenVikingClient, "commitSession"> | undefined>;
  logger: ContextEngineLifecycleLogger;
  resolveAgentId: (sessionId: string, sessionKey?: string, ovSessionId?: string) => string;
  rememberSessionAgentId?: (ctx: {
    agentId?: string;
    sessionId?: string;
    sessionKey?: string;
    ovSessionId?: string;
  }) => void;
  isBypassedSession: (params: { sessionId?: string; sessionKey?: string }) => boolean;
};

export type CompactOpenVikingSessionResult = {
  ok: boolean;
  compacted: boolean;
  reason?: string;
  result?: {
    summary?: string;
    firstKeptEntryId?: string;
    tokensBefore: number;
    tokensAfter?: number;
    details?: unknown;
  };
};

export type AssembleOpenVikingSessionResult = {
  messages: AgentMessage[];
  estimatedTokens: number;
  systemPromptAddition?: string;
};

type AssembleBuiltContext = {
  sanitized: AgentMessage[];
  archive: { messages: AgentMessage[]; tokens: number };
  session: { messages: AgentMessage[]; tokens: number };
  budgets: { archiveMemory: number; sessionContext: number; reserved: number };
  instruction: { text: string; tokens: number };
};

export type AssembleOpenVikingSessionParams = {
  sessionId: string;
  sessionKey?: string;
  messages: AgentMessage[];
  prompt?: string;
  tokenBudget: number;
  runtimeContext?: Record<string, unknown>;
  isHeartbeat?: boolean;
  identityHashSecret?: string;
  isMainAssemble: boolean;
  cfg: any;
  getClient: () => Promise<OpenVikingClient>;
  getClientForSender?: (senderId: unknown) => Promise<OpenVikingClient | undefined>;
  logger: ContextEngineLifecycleLogger;
  resolveAgentId: (sessionId: string, sessionKey?: string, ovSessionId?: string) => string;
  rememberSessionAgentId?: (ctx: {
    agentId?: string;
    sessionId?: string;
    sessionKey?: string;
    ovSessionId?: string;
  }) => void;
  isBypassedSession: (params: { sessionId?: string; sessionKey?: string }) => boolean;
  queryConfigStore?: {
    getEffective(params: {
      agentId?: string;
      sessionId?: string;
      sessionKey?: string;
      ovSessionId?: string;
    }): Promise<EffectiveQueryConfig>;
  };
  traceRecorder?: unknown;
  diag: (stage: string, sessionId: string, data: Record<string, unknown>) => void;
  roughEstimate: (messages: AgentMessage[]) => number;
  messageDigest: (messages: AgentMessage[]) => Array<{role: string; chars: number; tokens: number; contentTypes: string[]}>;
  extractAgentMessageText: (message: AgentMessage | undefined) => string;
  hasAutoRecallBlock: (message: AgentMessage | undefined) => boolean;
  prependRecallToLatestUserMessage: (messages: AgentMessage[], recallBlock: string) => AgentMessage[];
};

type CompactClient = Pick<OpenVikingClient, "commitSession" | "getSessionContext">;

export type CompactOpenVikingSessionParams = {
  sessionId: string;
  sessionKey?: string;
  runtimeContext?: Record<string, unknown>;
  identityHashSecret?: string;
  tokenBudget: number;
  currentTokenCount?: unknown;
  force?: boolean;
  compactionTarget?: "budget" | "threshold";
  customInstructions?: string;
  getClient: () => Promise<CompactClient>;
  getClientForSender?: (senderId: unknown) => Promise<CompactClient | undefined>;
  logger: ContextEngineLifecycleLogger;
  resolveAgentId: (sessionId: string, sessionKey?: string, ovSessionId?: string) => string;
  isBypassedSession: (params: { sessionId?: string; sessionKey?: string }) => boolean;
  diag: (stage: string, sessionId: string, data: Record<string, unknown>) => void;
};

type AfterTurnClient = Pick<OpenVikingClient, "addSessionMessage" | "getSession" | "commitSession" | "getTask">;

export type AfterTurnOpenVikingSessionParams = {
  sessionId: string;
  sessionKey?: string;
  sessionFile?: string;
  messages?: AgentMessage[];
  prePromptMessageCount?: number;
  isHeartbeat?: boolean;
  runtimeContext?: Record<string, unknown>;
  identityHashSecret?: string;
  cfg: {
    autoCapture: boolean;
    commitTokenThreshold: number;
    commitOnMemoryIntent?: boolean;
    commitKeepRecentCount: number;
    logFindRequests: boolean;
  };
  getClient: () => Promise<AfterTurnClient>;
  getClientForSender?: (senderId: unknown) => Promise<AfterTurnClient | undefined>;
  logger: ContextEngineLifecycleLogger;
  resolveAgentId: (sessionId: string, sessionKey?: string, ovSessionId?: string) => string;
  rememberSessionAgentId?: (ctx: {
    agentId?: string;
    sessionId?: string;
    sessionKey?: string;
    ovSessionId?: string;
  }) => void;
  isBypassedSession: (params: { sessionId?: string; sessionKey?: string }) => boolean;
  diag: (stage: string, sessionId: string, data: Record<string, unknown>) => void;
};

export function totalExtractedMemories(memories?: Record<string, number>): number {
  if (!memories || typeof memories !== "object") {
    return 0;
  }
  return Object.values(memories).reduce((sum, count) => sum + (count ?? 0), 0);
}

type ContextBudgets = {
  archiveMemory: number;
  sessionContext: number;
  reserved: number;
};

const BUDGET_UNLIMITED = -1;
const ARCHIVE_BUDGET_RATIO = 0.15;
const ARCHIVE_BUDGET_CAP = 8_000;
const RESERVED_MIN = 20_000;
const RESERVED_RATIO = 0.15;
const PHASE2_POLL_INTERVAL_MS = 800;
const PHASE2_POLL_MAX_MS = DEFAULT_PHASE2_POLL_TIMEOUT_MS;
const MEMORY_COMMIT_INTENT_RE =
  /(?:请\s*)?(?:记住|记下)|我叫|我的名字|叫我|我是|我喜欢|我的偏好|\b(?:please\s+)?remember\b|\b(?:my\s+name\s+is|call\s+me|i\s+am|i'm|i\s+like|my\s+preference)\b/i;
const MEMORY_RECALL_QUESTION_RE =
  /我是谁|我叫什么|你认识我吗|\bwho\s+am\s+i\b|\bwhat\s+is\s+my\s+name\b|\bdo\s+you\s+know\s+me\b/i;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * After wait=false commit, Phase2 runs on the server. Poll task until completed/failed/timeout
 * so logs show memories_extracted (otherwise it looks like "nothing was saved").
 */
async function pollPhase2ExtractionOutcome(
  client: AfterTurnClient,
  taskId: string,
  agentId: string,
  logger: ContextEngineLifecycleLogger,
  sessionLabel: string,
): Promise<void> {
  const sessionHash = hashLogValue(sessionLabel);
  const deadline = Date.now() + PHASE2_POLL_MAX_MS;
  try {
    while (Date.now() < deadline) {
      await sleep(PHASE2_POLL_INTERVAL_MS);
      const task = await client.getTask(taskId, agentId).catch((e) => {
        logger.warn?.(`openviking: phase2 getTask failed taskPresent=true errorType=${errorType(e)}`);
        return null;
      });
      if (!task) {
        return;
      }
      const { status } = task;
      if (status === "completed") {
        const memoriesExtracted = task.result && typeof task.result === "object"
          ? (task.result as { memories_extracted?: Record<string, number> }).memories_extracted
          : undefined;
        logger.info(
          `openviking: phase2 completed taskPresent=true sessionHash=${sessionHash} memories=${totalExtractedMemories(memoriesExtracted)}`,
        );
        return;
      }
      if (status === "failed") {
        logger.warn?.(
          `openviking: phase2 failed taskPresent=true sessionHash=${sessionHash} errorType=${errorType(task.error)}`,
        );
        return;
      }
    }
    logger.warn?.(
      `openviking: phase2 poll timeout seconds=${PHASE2_POLL_MAX_MS / 1000} taskPresent=true sessionHash=${sessionHash}`,
    );
  } catch (e) {
    logger.warn?.(`openviking: phase2 poll exception taskPresent=true errorType=${errorType(e)}`);
  }
}

function allocateContextBudget(totalBudget: number, instructionTokens = 0): ContextBudgets {
  const reserveFloor = totalBudget >= RESERVED_MIN * 2 ? RESERVED_MIN : 0;
  const reserved = Math.min(totalBudget, Math.max(totalBudget * RESERVED_RATIO, reserveFloor));
  const usableBudget = Math.max(totalBudget - reserved - instructionTokens, 0);
  const archiveMemory = Math.min(usableBudget * ARCHIVE_BUDGET_RATIO, ARCHIVE_BUDGET_CAP);
  const sessionContext = Math.max(usableBudget - archiveMemory, 0);
  return { archiveMemory, sessionContext, reserved };
}

function buildSystemPromptAddition(): string {
  return [
    "## Session Context Guide",
    "",
    "Your conversation history includes two layers:",
    "",
    "1. **[Session History Summary]** — A compressed summary of all prior turns",
    "   in this session. It is organized into structured sections (Key Facts,",
    "   Timeline, People, etc.). Use it for background and continuity.",
    "   The summary is lossy: specific details (exact dates, numbers, names,",
    "   small events) may have been compressed away.",
    "",
    "2. **Active messages** — The most recent uncompressed turns.",
    "",
    "**Rules:**",
    "- When active messages conflict with the Summary, trust active messages",
    "  as the newer source of truth.",
    "- Do not fabricate details the Summary does not state explicitly.",
    "- **CRITICAL: Before answering 'no information' or 'not mentioned',",
    "  you MUST carefully re-read EVERY section of the [Session History Summary].",
    "  The answer may be expressed with different wording than the question.",
    "  Look for synonyms, related facts, and indirect references.**",
    "- If the Summary mentions a topic but lacks the specific detail asked,",
    "  use the `ov_archive_search` tool to grep the original archived messages",
    "  for the exact detail. Try 2-3 different keywords extracted from the question.",
    "- Only conclude information is unavailable AFTER both checking the Summary",
    "  thoroughly AND searching the archives with at least 2 keyword variations.",
  ].join("\n");
}

function buildInstructionPrompt(): { text: string; tokens: number } {
  const text = buildSystemPromptAddition();
  return { text, tokens: estimateTextTokens(text) };
}

function buildArchiveMemory(
  archiveOverview: string | undefined,
  _preAbstracts: Array<{ archive_id: string; abstract: string }>,
  _budget: number,
  roughEstimate: (messages: AgentMessage[]) => number,
): { messages: AgentMessage[]; tokens: number } {
  const messages: AgentMessage[] = [];

  if (archiveOverview) {
    messages.push({
      role: "user",
      content: `[Session History Summary]\n${archiveOverview}`,
    });
  }

  return { messages, tokens: roughEstimate(messages) };
}

function buildSessionContext(
  ovMessages: OVMessage[],
  budget: number,
  roughEstimate: (messages: AgentMessage[]) => number,
): { messages: AgentMessage[]; tokens: number } {
  const raw = ovMessages.flatMap((m) => convertToAgentMessages(m));
  const messages = mergeConsecutiveAssistants(raw);
  const tokens = roughEstimate(messages);
  if (budget === BUDGET_UNLIMITED || tokens <= budget) {
    return { messages, tokens };
  }
  const trimmed = [...messages];
  while (trimmed.length > 0 && roughEstimate(trimmed) > budget) {
    trimmed.shift();
  }
  return { messages: trimmed, tokens: roughEstimate(trimmed) };
}

function buildAssembledContext(
  overview: string | undefined,
  preAbstracts: Array<{ archive_id: string; abstract: string }>,
  ovMessages: OVMessage[],
  tokenBudget: number,
  ovSessionId: string,
  logger: ContextEngineLifecycleLogger,
  roughEstimate: (messages: AgentMessage[]) => number,
): AssembleBuiltContext {
  const hasArchives = Boolean(overview) || preAbstracts.length > 0;
  const instruction = hasArchives ? buildInstructionPrompt() : { text: "", tokens: 0 };

  // 4-layer context partitioning:
  //   Instruction — system prompt guide (Archive Index / Session History usage)
  //   Archive     — session history summary + per-archive one-line abstracts
  //   Session     — active OV messages converted to AgentMessage format
  //   Reserved    — headroom for model output (not consumed here)
  const budgets = allocateContextBudget(tokenBudget, instruction.tokens);
  const archive = buildArchiveMemory(overview, preAbstracts, budgets.archiveMemory, roughEstimate);
  const sessionBudget = Math.max(
    tokenBudget - budgets.reserved - instruction.tokens - archive.tokens,
    0,
  );
  const session = buildSessionContext(ovMessages, sessionBudget, roughEstimate);
  const assembled = [...archive.messages, ...session.messages];

  logger.info(`openviking: assemble entering session content sessionHash=${hashLogValue(ovSessionId)}, messagesCount=${assembled.length}`);

  const sanitized = sanitizeAgentMessagesForProvider(assembled);

  return { sanitized, archive, session, budgets, instruction };
}

export async function commitOpenVikingSession({
  sessionId,
  sessionKey,
  runtimeContext,
  identityHashSecret,
  getClient,
  getClientForSender,
  logger,
  resolveAgentId,
  rememberSessionAgentId,
  isBypassedSession,
}: CommitOpenVikingSessionParams): Promise<boolean> {
  if (isBypassedSession({ sessionId, sessionKey })) {
    logger.warn?.(
      `openviking: commit skipped because session is bypassed (sessionKeyPresent=${Boolean(sessionKey)})`,
    );
    return false;
  }
  try {
    const sender = await resolveLifecycleSenderIdentity({ runtimeContext, sessionKey, identityHashSecret });
    const ovId = openClawSessionToOvStorageId(sessionId, sessionKey);
    const client = getClientForSender
      ? sender.scope
        ? await getClientForSender(sender.scope)
        : undefined
      : await getClient();
    if (!client) {
      logger.warn?.(
        `openviking: commit skipped because sender identity is missing (sessionKeyPresent=${Boolean(sessionKey)})`,
      );
      return false;
    }
    rememberSessionAgentId?.({
      sessionId,
      sessionKey,
      ovSessionId: ovId,
    });
    const agentId = resolveAgentId(sessionId, sessionKey, ovId);
    const commitResult = await client.commitSession(ovId, {
      wait: true,
      agentId,
      keepRecentCount: 0,
    });
    const memCount = totalExtractedMemories(commitResult.memories_extracted);
    if (commitResult.status === "failed") {
      logger.warn?.(`openviking: commit Phase 2 failed sessionHash=${hashLogValue(sessionId)}, errorType=${errorType(commitResult.error)}`);
      return false;
    }
    if (commitResult.status === "timeout") {
      logger.warn?.(`openviking: commit Phase 2 timed out sessionHash=${hashLogValue(sessionId)}, taskPresent=${Boolean(commitResult.task_id)}`);
      return false;
    }
    logger.info(
      `openviking: committed sessionHash=${hashLogValue(sessionId)}, ovIdHash=${hashLogValue(ovId)}, archived=${commitResult.archived ?? false}, memories=${memCount}`,
    );
    return true;
  } catch (err) {
    if (isTurnIdentityError(err)) throw err;
    logger.warn?.(`openviking: commit failed sessionHash=${hashLogValue(sessionId)}, errorType=${errorType(err)}`);
    return false;
  }
}

function assemblePassthrough(
  params: Pick<AssembleOpenVikingSessionParams, "diag"> & {
    ovSessionId: string;
    reason: string;
    liveMessages: AgentMessage[];
    originalTokens: number;
    systemPromptAddition?: string;
    extra?: Record<string, unknown>;
  },
): AssembleOpenVikingSessionResult {
  const { diag, ovSessionId, reason, liveMessages, originalTokens, systemPromptAddition, extra } = params;
  diag("assemble_result", ovSessionId, {
    passthrough: true,
    reason,
    outputMessagesCount: liveMessages.length,
    inputTokenEstimate: originalTokens,
    estimatedTokens: originalTokens,
    tokensSaved: 0,
    savingPct: 0,
    ...extra,
  });
  return {
    messages: liveMessages,
    estimatedTokens: originalTokens,
    ...(systemPromptAddition?.trim() ? { systemPromptAddition } : {}),
  };
}

function joinSystemPromptAdditions(...parts: Array<string | undefined>): string | undefined {
  const joined = parts
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part))
    .join("\n\n");
  return joined || undefined;
}

type AutoRecallAttemptResult =
  | {
      kind: "injected";
      block: string;
      memoryCount: number;
      estimatedTokens: number;
      source: "message" | "prompt";
    }
  | {
      kind: "passthrough";
      reason: string;
      extra?: Record<string, unknown>;
    };

async function tryBuildSenderScopedAutoRecall(
  params: Pick<
    AssembleOpenVikingSessionParams,
    | "sessionId"
    | "sessionKey"
    | "messages"
    | "prompt"
    | "cfg"
    | "queryConfigStore"
    | "traceRecorder"
    | "logger"
    | "extractAgentMessageText"
    | "hasAutoRecallBlock"
  > & {
    ovSessionId: string;
    sender: LifecycleSenderIdentity;
    agentId: string;
    reasonPrefix: string;
    getClient: () => Promise<OpenVikingClient | undefined>;
    recallDiag?: (stage: string, sessionId: string, data: Record<string, unknown>) => void;
  },
): Promise<AutoRecallAttemptResult> {
  const {
    sessionId,
    sessionKey,
    messages,
    prompt,
    cfg,
    queryConfigStore,
    traceRecorder,
    logger,
    extractAgentMessageText,
    hasAutoRecallBlock,
    ovSessionId,
    sender,
    agentId,
    reasonPrefix,
    getClient,
    recallDiag,
  } = params;
  const latestMessage = messages.at(-1);
  const promptText = typeof prompt === "string" ? prompt.trim() : "";
  let recallSource: "message" | "prompt" = "message";
  let recallMessage = latestMessage;

  if (recallMessage?.role !== "user") {
    if (!promptText) {
      return {
        kind: "passthrough",
        reason: `${reasonPrefix}_non_user_tail`,
        extra: { latestRole: latestMessage?.role ?? null },
      };
    }
    recallSource = "prompt";
    recallMessage = { role: "user", content: promptText } as AgentMessage;
  }
  if (!cfg.autoRecall) {
    return { kind: "passthrough", reason: `${reasonPrefix}_auto_recall_disabled` };
  }
  if (hasAutoRecallBlock(recallMessage)) {
    return { kind: "passthrough", reason: `${reasonPrefix}_recall_already_injected` };
  }

  const recallQuery = prepareRecallQuery(extractAgentMessageText(recallMessage));
  if (!recallQuery.query || (!isIdentityProfileQuery(recallQuery.query) && recallQuery.query.length < 5)) {
    return { kind: "passthrough", reason: `${reasonPrefix}_empty_recall_query` };
  }
  if (recallQuery.truncated) {
    logger.info(
      `openviking: recall query truncated (` +
        `chars=${recallQuery.originalChars}->${recallQuery.finalChars})`,
    );
  }

  const recallStartedAt = Date.now();
  const recallMemoryBefore = memoryDiagnostics();
  recallDiag?.("recall_start", ovSessionId, {
    queryChars: recallQuery.finalChars,
    queryTruncated: recallQuery.truncated,
    source: recallSource,
    memoryBefore: recallMemoryBefore,
  });
  const emitRecallEnd = (status: "injected" | "no_hits" | "skipped" | "failed", data: Record<string, unknown> = {}) => {
    recallDiag?.("recall_end", ovSessionId, {
      status,
      source: recallSource,
      ...data,
      ...completionDiagnostics(recallStartedAt),
    });
  };

  try {
    const client = await getClient();
    if (!client) {
      logger.info("openviking.identity_missing.skip_recall");
      emitRecallEnd("skipped", { reason: "identity_missing", memoryCount: 0, estimatedTokens: 0 });
      return {
        kind: "passthrough",
        reason: "identity_missing",
        extra: { senderIdFound: sender.found },
      };
    }
    const queryConfig = await queryConfigStore?.getEffective({
      agentId,
      sessionId,
      sessionKey,
      ovSessionId,
    });
    const recall = await buildAutoRecallContext({
      cfg,
      queryConfig,
      client,
      agentId,
      queryText: recallQuery.query,
      logger,
      verbose: (message) => logger.info(message),
      traceRecorder: traceRecorder as never,
      sessionId,
      sessionKey,
      ovSessionId,
      queryTruncated: recallQuery.truncated,
      rawUserTextPreview: recallQuery.query,
    });

    if (!recall.block) {
      emitRecallEnd("no_hits", {
        memoryCount: recall.memoryCount,
        estimatedTokens: recall.estimatedTokens,
      });
      return {
        kind: "passthrough",
        reason: `${reasonPrefix}_no_recall_hits`,
        extra: { memoryCount: recall.memoryCount },
      };
    }

    emitRecallEnd("injected", {
      memoryCount: recall.memoryCount,
      estimatedTokens: recall.estimatedTokens,
    });
    return {
      kind: "injected",
      block: recall.block,
      memoryCount: recall.memoryCount,
      estimatedTokens: recall.estimatedTokens,
      source: recallSource,
    };
  } catch (err) {
    logger.warn?.(`openviking: auto-recall failed errorType=${errorType(err)}`);
    emitRecallEnd("failed", { error: err, memoryCount: 0, estimatedTokens: 0 });
    return {
      kind: "passthrough",
      reason: `${reasonPrefix}_recall_failed`,
      extra: { error: String(err) },
    };
  }
}

export async function assembleOpenVikingSession({
  sessionId,
  sessionKey,
  messages,
  prompt,
  tokenBudget,
  runtimeContext,
  isHeartbeat,
  identityHashSecret,
  isMainAssemble,
  cfg,
  getClient,
  getClientForSender,
  logger,
  resolveAgentId,
  rememberSessionAgentId,
  isBypassedSession,
  queryConfigStore,
  traceRecorder,
  diag,
  roughEstimate,
  messageDigest,
  extractAgentMessageText,
  hasAutoRecallBlock,
  prependRecallToLatestUserMessage,
}: AssembleOpenVikingSessionParams): Promise<AssembleOpenVikingSessionResult> {
  const startedAt = Date.now();
  const memoryBefore = memoryDiagnostics();
  const ovSessionId = openClawSessionToOvStorageId(sessionId, sessionKey);
  diag = safeDiag(diag);
  const baseDiag = diag;
  const completionDiag = (event: string, id: string, data: Record<string, unknown>) =>
    baseDiag(event, id, { ...data, ...completionDiagnostics(startedAt) });
  const isTransformContextAssemble = !isMainAssemble;
  const originalTokens = roughEstimate(messages);
  let messagesWithRecall = messages;
  let tokensWithRecall = originalTokens;

  if (isHeartbeat) {
    logger.info(`openviking: context assemble bypassed reason=heartbeat_bypassed sessionHash=${hashLogValue(ovSessionId)}`);
    return assemblePassthrough({
      diag: completionDiag,
      ovSessionId,
      reason: "heartbeat_bypassed",
      liveMessages: messages,
      originalTokens,
    });
  }

  if (isBypassedSession({ sessionId, sessionKey })) {
    return assemblePassthrough({ diag: completionDiag, ovSessionId, reason: "session_bypassed", liveMessages: messages, originalTokens });
  }

  const sender = await resolveLifecycleSenderIdentity({ runtimeContext, sessionKey, identityHashSecret });
  rememberSessionAgentId?.({
    sessionId,
    sessionKey,
    agentId: extractRuntimeAgentId(runtimeContext),
    ovSessionId,
  });
  baseDiag("assemble_entry", ovSessionId, {
    messagesCount: messages.length,
    memoryBefore,
    inputTokenEstimate: originalTokens,
    tokenBudget,
    sessionKey: sessionKey ?? null,
    senderIdFound: sender.found,
    messages: messageDigest(messages),
  });

  diag = completionDiag;

  let clientResolved = false;
  let resolvedClient: OpenVikingClient | undefined;
  const resolveSenderScopedClient = async (): Promise<OpenVikingClient | undefined> => {
    if (!clientResolved) {
      resolvedClient = getClientForSender
        ? await getClientForSender(sender.scope)
        : await getClient();
      clientResolved = true;
    }
    return resolvedClient;
  };

  if (isTransformContextAssemble) {
    const routingRef = sessionId ?? sessionKey ?? ovSessionId;
    const agentId = resolveAgentId(routingRef, sessionKey, ovSessionId);
    const recall = await tryBuildSenderScopedAutoRecall({
      sessionId,
      sessionKey,
      messages,
      prompt,
      cfg,
      queryConfigStore,
      traceRecorder,
      logger,
      extractAgentMessageText,
      hasAutoRecallBlock,
      ovSessionId,
      sender,
      agentId,
      reasonPrefix: "transform_context",
      getClient: resolveSenderScopedClient,
      recallDiag: baseDiag,
    });

    if (recall.kind !== "injected") {
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: recall.reason,
        liveMessages: messages,
        originalTokens,
        extra: recall.extra,
      });
    }

    const withRecall = prependRecallToLatestUserMessage(messages, recall.block);
      const estimatedTokens = roughEstimate(withRecall);
      diag("assemble_result", ovSessionId, {
        passthrough: false,
        phase: "transform_context",
        outputMessagesCount: withRecall.length,
        inputTokenEstimate: originalTokens,
        estimatedTokens,
        autoRecallMemoryCount: recall.memoryCount,
        autoRecallTokens: recall.estimatedTokens,
        messages: messageDigest(withRecall),
      });
      return { messages: withRecall, estimatedTokens };
  }

  let promptRecallBlock: string | undefined;

  try {
    const routingRef = sessionId ?? sessionKey ?? ovSessionId;
    const agentId = resolveAgentId(routingRef, sessionKey, ovSessionId);
    const recall = await tryBuildSenderScopedAutoRecall({
      sessionId,
      sessionKey,
      messages,
      prompt,
      cfg,
      queryConfigStore,
      traceRecorder,
      logger,
      extractAgentMessageText,
      hasAutoRecallBlock,
      ovSessionId,
      sender,
      agentId,
      reasonPrefix: "main_assemble",
      getClient: resolveSenderScopedClient,
      recallDiag: baseDiag,
    });
    promptRecallBlock = recall.kind === "injected" && recall.source === "prompt"
      ? recall.block
      : undefined;
    messagesWithRecall = recall.kind === "injected" && recall.source === "message"
      ? prependRecallToLatestUserMessage(messages, recall.block)
      : messages;
    tokensWithRecall = recall.kind === "injected" && recall.source === "message"
      ? roughEstimate(messagesWithRecall)
      : originalTokens + (promptRecallBlock ? estimateTextTokens(promptRecallBlock) : 0);
    const client = await resolveSenderScopedClient();
    if (!client) {
      logger.info("openviking.identity_missing.skip_context");
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: "identity_missing",
        liveMessages: messagesWithRecall,
        originalTokens: tokensWithRecall,
        systemPromptAddition: promptRecallBlock,
        extra: { senderIdFound: sender.found },
      });
    }
    const ctx = await client.getSessionContext(ovSessionId, tokenBudget, agentId);

    const preAbstracts = ctx?.pre_archive_abstracts ?? [];
    const hasArchives = !!ctx?.latest_archive_overview || preAbstracts.length > 0;
    const activeCount = ctx?.messages?.length ?? 0;

    if (!ctx || (!hasArchives && activeCount === 0)) {
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: "no_ov_data",
        liveMessages: messagesWithRecall,
        originalTokens: tokensWithRecall,
        systemPromptAddition: promptRecallBlock,
        extra: { archiveCount: 0, activeCount: 0 },
      });
    }
    if (!hasArchives && ctx.messages.length < messages.length) {
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: "ov_msgs_fewer_than_input",
        liveMessages: messagesWithRecall,
        originalTokens: tokensWithRecall,
        systemPromptAddition: promptRecallBlock,
        extra: { archiveCount: 0, activeCount },
      });
    }

    const { sanitized, archive, session, budgets, instruction } = buildAssembledContext(
      ctx.latest_archive_overview,
      preAbstracts,
      ctx.messages,
      tokenBudget,
      ovSessionId,
      logger,
      roughEstimate,
    );

    if (sanitized.length === 0 && messages.length > 0) {
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: "sanitized_empty",
        liveMessages: messagesWithRecall,
        originalTokens: tokensWithRecall,
        systemPromptAddition: promptRecallBlock,
        extra: { archiveCount: preAbstracts.length, activeCount },
      });
    }

    const outputMessages = recall.kind === "injected" && recall.source === "message"
      ? prependRecallToLatestUserMessage(sanitized, recall.block)
      : sanitized;
    const systemPromptAddition = joinSystemPromptAdditions(promptRecallBlock, instruction.text);
    const assembledTokens =
      roughEstimate(outputMessages) +
      instruction.tokens +
      (promptRecallBlock ? estimateTextTokens(promptRecallBlock) : 0);
    const tokensSaved = originalTokens - assembledTokens;
    const savingPct = originalTokens > 0 ? Math.round((tokensSaved / originalTokens) * 100) : 0;

    diag("assemble_result", ovSessionId, {
      passthrough: false,
      archiveCount: preAbstracts.length,
      activeCount,
      outputMessagesCount: outputMessages.length,
      inputTokenEstimate: originalTokens,
      estimatedTokens: assembledTokens,
      tokensSaved,
      savingPct,
      archiveTokens: archive.tokens,
      archiveBudget: budgets.archiveMemory,
      sessionTokens: session.tokens,
      sessionBudget: budgets.sessionContext,
      reservedBudget: budgets.reserved,
      senderIdFound: sender.found,
      autoRecallMemoryCount: recall.kind === "injected" ? recall.memoryCount : 0,
      autoRecallTokens: recall.kind === "injected" ? recall.estimatedTokens : 0,
      messages: messageDigest(outputMessages),
    });

    return {
      messages: outputMessages,
      estimatedTokens: assembledTokens,
      ...(systemPromptAddition ? { systemPromptAddition } : {}),
    };
  } catch (err) {
    const errorMessage = String(err);
    if (errorMessage.includes("[NOT_FOUND]") && errorMessage.includes("Session not found")) {
      const routingRef = sessionId ?? sessionKey ?? ovSessionId;
      const agentId = resolveAgentId(routingRef, sessionKey, ovSessionId);
      logger.info(
        `openviking: assemble skipped because OV session does not exist sessionHash=${hashLogValue(ovSessionId)}, agentIdPreview=${agentIdPreview(agentId)}`,
      );
      return assemblePassthrough({
        diag,
        ovSessionId,
        reason: "session_not_found",
        liveMessages: messagesWithRecall,
        originalTokens: tokensWithRecall,
        systemPromptAddition: promptRecallBlock,
        extra: { error: errorMessage, senderIdFound: sender.found },
      });
    }
    logger.warn?.(
      `openviking: assemble failed sessionHash=${hashLogValue(ovSessionId)}, tokenBudget=${tokenBudget}, agentIdPreview=${agentIdPreview(resolveAgentId(ovSessionId))}, errorType=${errorType(err)}`,
    );
    diag("assemble_error", ovSessionId, {
      error: errorMessage,
      tokenBudget,
      agentId: resolveAgentId(ovSessionId),
      senderIdFound: sender.found,
    });
    return { messages, estimatedTokens: roughEstimate(messages) };
  }
}

function normalizeTimestamp(value: unknown): string | undefined {
  if (typeof value === "number" && Number.isFinite(value)) {
    const timestampMs = Math.abs(value) < 100_000_000_000 ? value * 1000 : value;
    return new Date(timestampMs).toISOString();
  }
  return undefined;
}

function pickLatestCreatedAt(messages: AgentMessage[]): string | undefined {
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i] as Record<string, unknown>;
    const role = typeof message.role === "string" ? message.role : "";
    if (!role || role === "system") {
      continue;
    }
    const normalized = normalizeTimestamp(message.timestamp);
    if (normalized) {
      return normalized;
    }
  }
  return undefined;
}

function toSessionFileAgentMessage(entry: unknown): AgentMessage | undefined {
  if (!entry || typeof entry !== "object") {
    return undefined;
  }
  const record = entry as Record<string, unknown>;
  const rawMessage = record.message && typeof record.message === "object"
    ? record.message as Record<string, unknown>
    : record;
  const role = typeof rawMessage.role === "string" ? rawMessage.role : "";
  if (!role) {
    return undefined;
  }
  return {
    ...(rawMessage as AgentMessage),
    timestamp: rawMessage.timestamp ?? record.timestamp,
  };
}

async function readLatestTurnMessagesFromSessionFile(sessionFile?: string): Promise<AgentMessage[]> {
  const file = typeof sessionFile === "string" ? sessionFile.trim() : "";
  if (!file) {
    return [];
  }
  let raw = "";

  try {
    raw = await readFile(file, "utf8");
  } catch {
    return [];
  }
  const messages: AgentMessage[] = [];
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      const message = toSessionFileAgentMessage(parsed);
      if (message) {
        messages.push(message);
      }
    } catch {
      continue;
    }
  }
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    if (messages[i]?.role === "user") {
      return messages.slice(i);
    }
  }
  return [];
}

function hasExplicitMemoryCommitIntent(messages: ExtractedTurnMessage[]): boolean {
  const text = messages
    .filter((msg) => msg.role === "user")
    .flatMap((msg) => msg.parts)
    .filter((part) => part.type === "text")
    .map((part) => part.text)
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
  if (!text || MEMORY_RECALL_QUESTION_RE.test(text)) {
    return false;
  }
  return MEMORY_COMMIT_INTENT_RE.test(text);
}

type LifecycleSenderIdentity = {
  found: boolean;
  senderId?: string;
  senderHash?: string;
  openVikingUserId?: string;
  scope?: unknown;
  source?: "active_turn";
};

async function resolveLifecycleSenderIdentity(params: {
  runtimeContext: Record<string, unknown> | undefined;
  sessionKey?: string;
  identityHashSecret?: string;
}): Promise<LifecycleSenderIdentity> {
  const secret = params.identityHashSecret?.trim() || process.env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() || "";
  const turn = readActiveOpenVikingTurn({
    sessionKey: params.sessionKey,
    secret,
    expectedChannel: readRegisteredOpenVikingTurnChannel({ sessionKey: params.sessionKey, secret }),
  });
  return {
    found: true,
    openVikingUserId: turn.openVikingUserId,
    source: "active_turn",
    scope: turn.openVikingUserId,
  };
}

function extractRuntimeAgentId(runtimeContext: Record<string, unknown> | undefined): string | undefined {
  if (!runtimeContext) {
    return undefined;
  }
  const agentId = runtimeContext.agentId;
  return typeof agentId === "string" && agentId.trim() ? agentId.trim() : undefined;
}

function isToolOnlyMessage(msg: ExtractedTurnMessage): boolean {
  return msg.role === "user" && msg.parts.length > 0 && msg.parts.every((part) => part.type === "tool");
}

function coalesceConsecutiveToolMessages(messages: ExtractedTurnMessage[]): ExtractedTurnMessage[] {
  const result: ExtractedTurnMessage[] = [];
  let pendingTools: ExtractedTurnMessage | undefined;

  const flush = () => {
    if (pendingTools) {
      result.push(pendingTools);
      pendingTools = undefined;
    }
  };

  for (const msg of messages) {
    if (isToolOnlyMessage(msg)) {
      if (!pendingTools) {
        pendingTools = { role: "user", parts: [] };
      }
      pendingTools.parts.push(...msg.parts);
      continue;
    }
    flush();
    result.push(msg);
  }
  flush();
  return result;
}

function messageDigest(messages: AgentMessage[], maxCharsPerMsg = 2000): Array<{role: string; content: string; tokens: number; truncated: boolean}> {
  return messages.map((msg) => {
    const m = msg as Record<string, unknown>;
    const role = String(m.role ?? "unknown");
    const raw = m.content;
    const textLength = typeof raw === "string" ? raw.length : JSON.stringify(raw ?? "").length;
    const truncated = textLength > maxCharsPerMsg;
    return {
      role,
      content: "",
      tokens: estimateAgentMessageTokens(msg),
      truncated,
    };
  });
}

export async function afterTurnOpenVikingSession({
  sessionId,
  sessionKey,
  sessionFile,
  messages: rawMessages,
  prePromptMessageCount,
  isHeartbeat,
  runtimeContext,
  identityHashSecret,
  cfg,
  getClient,
  getClientForSender,
  logger,
  resolveAgentId,
  rememberSessionAgentId,
  isBypassedSession,
  diag,
}: AfterTurnOpenVikingSessionParams): Promise<void> {
  const startedAt = Date.now();
  const memoryBefore = memoryDiagnostics();
  diag = safeDiag(diag);
  const completionDiag = (event: string, id: string, data: Record<string, unknown>) =>
    diag(event, id, { ...data, ...completionDiagnostics(startedAt) });

  if (!cfg.autoCapture) {
    return;
  }

  if (isHeartbeat) {
    const ovSessionId = openClawSessionToOvStorageId(sessionId, sessionKey);
    completionDiag("afterTurn_skip", ovSessionId, {
      reason: "heartbeat_bypassed",
      totalMessages: rawMessages?.length ?? 0,
    });
    logger.info(`openviking: afterTurn bypassed reason=heartbeat_bypassed sessionHash=${hashLogValue(ovSessionId)}`);
    return;
  }

  try {
    const sender = await resolveLifecycleSenderIdentity({ runtimeContext, sessionKey, identityHashSecret });
    const activeTurn = readActiveOpenVikingTurn({
      sessionKey,
      secret: identityHashSecret?.trim() || process.env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() || "",
      expectedChannel: readRegisteredOpenVikingTurnChannel({ sessionKey, secret: identityHashSecret?.trim() || process.env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() || "" }),
    });
    if (shouldSkipAfterTurnAutoCapture(activeTurn)) {
      diag("afterTurn_skip", sessionId ?? sessionKey ?? "unknown", {
        reason: "explicit_memory_store_handled",
        explicitMemoryStoreOutcome: activeTurn.explicitMemoryStoreOutcome,
      });
      return;
    }
    const ovSessionId = openClawSessionToOvStorageId(sessionId, sessionKey);
    const runtimeAgentId = extractRuntimeAgentId(runtimeContext);
    if (runtimeAgentId) {
      rememberSessionAgentId?.({
        agentId: runtimeAgentId,
        sessionId,
        sessionKey,
        ovSessionId,
      });
    }
    const routingRef = sessionId ?? sessionKey ?? ovSessionId;
    const agentId = resolveAgentId(routingRef, sessionKey, ovSessionId);

    if (isBypassedSession({ sessionId, sessionKey })) {
      diag("afterTurn_skip", ovSessionId, {
        reason: "session_bypassed",
        totalMessages: rawMessages?.length ?? 0,
        senderIdFound: sender.found,
      });
      return;
    }

    let messages = rawMessages ?? [];
    let start =
      typeof prePromptMessageCount === "number" && prePromptMessageCount >= 0
        ? prePromptMessageCount
        : 0;
    let extractionSource = "host_messages";

    let { messages: extractedMessagesRaw, newCount } = extractNewTurnMessages(messages, start);
    let extractedMessages = coalesceConsecutiveToolMessages(extractedMessagesRaw);

    if (extractedMessages.length === 0) {
      const fallbackMessages = await readLatestTurnMessagesFromSessionFile(sessionFile);
      if (fallbackMessages.length > 0) {
        messages = fallbackMessages;
        start = 0;
        extractionSource = "session_file";
        const fallback = extractNewTurnMessages(messages, start);
        extractedMessagesRaw = fallback.messages;
        newCount = fallback.newCount;
        extractedMessages = coalesceConsecutiveToolMessages(extractedMessagesRaw);
      }
    }

    if (messages.length === 0) {
      diag("afterTurn_skip", ovSessionId, {
        reason: "no_messages",
        totalMessages: 0,
        senderIdFound: sender.found,
      });
      return;
    }

    if (extractedMessages.length === 0) {
      diag("afterTurn_skip", ovSessionId, {
        reason: "no_new_turn_messages",
        totalMessages: messages.length,
        prePromptMessageCount: start,
        extractionSource,
        senderIdFound: sender.found,
      });
      return;
    }

    const turnMessages = messages.slice(start) as AgentMessage[];
    const newMessages = turnMessages.filter((m: AgentMessage) => {
      const role = (m as Record<string, unknown>).role as string;
      return role === "user" || role === "assistant";
    }) as AgentMessage[];
    const newMsgFull = messageDigest(newMessages);
    const newTurnTokens = newMsgFull.reduce((sum, digest) => sum + digest.tokens, 0);

    diag("afterTurn_entry", ovSessionId, {
      totalMessages: messages.length,
      memoryBefore,
      newMessageCount: newCount,
      prePromptMessageCount: start,
      newTurnTokens,
      extractionSource,
      senderIdFound: sender.found,
      messages: newMsgFull,
    });

    const client = getClientForSender
      ? await getClientForSender(sender.scope)
      : await getClient();
    if (!client) {
      diag("afterTurn_skip", ovSessionId, {
        reason: "identity_missing",
        totalMessages: messages.length,
        senderIdFound: sender.found,
      });
      return;
    }
    const createdAt = pickLatestCreatedAt(turnMessages);
    const senderRoleId = toRoleId(sender.senderId ?? sender.openVikingUserId);
    let capturedCount = 0;
    for (const msg of extractedMessages) {
      const ovParts = msg.parts.map((part) => {
        if (part.type === "text") {
          const cleaned = part.text
            .replace(/<relevant-memories>[\s\S]*?<\/relevant-memories>/gi, " ")
            .replace(/\s+/g, " ")
            .trim();
          return { type: "text" as const, text: cleaned };
        }
        return {
          type: "tool" as const,
          tool_id: part.toolCallId,
          tool_name: part.toolName,
          tool_input: part.toolInput,
          tool_output: part.toolOutput,
          tool_status: part.toolStatus,
        };
      });

      if (ovParts.length > 0) {
        await client.addSessionMessage(
          ovSessionId,
          msg.role,
          ovParts,
          agentId,
          createdAt,
          msg.role === "user" ? senderRoleId : undefined,
        );
        capturedCount += 1;
      }
    }
    logger.info?.(`openviking: afterTurn captured messages count=${capturedCount}, source=${extractionSource}`);

    const session = await client.getSession(ovSessionId, agentId);
    const pendingTokens = session.pending_tokens ?? 0;
    const forceCommitForMemoryIntent =
      cfg.commitOnMemoryIntent !== false && hasExplicitMemoryCommitIntent(extractedMessages);

    if (pendingTokens < cfg.commitTokenThreshold && !forceCommitForMemoryIntent) {
      diag("afterTurn_skip", ovSessionId, {
        reason: "below_threshold",
        pendingTokens,
        commitTokenThreshold: cfg.commitTokenThreshold,
        senderIdFound: sender.found,
      });
      return;
    }
    if (forceCommitForMemoryIntent && pendingTokens < cfg.commitTokenThreshold) {
      logger.info?.(
        `openviking: afterTurn force commit for memory intent sessionHash=${hashLogValue(ovSessionId)}, pendingTokens=${pendingTokens}, threshold=${cfg.commitTokenThreshold}`,
      );
    }
    const commitKeepRecentCount =
      forceCommitForMemoryIntent && pendingTokens < cfg.commitTokenThreshold
        ? 0
        : cfg.commitKeepRecentCount;

    const commitResult = await client.commitSession(ovSessionId, {
      wait: false,
      agentId,
      keepRecentCount: commitKeepRecentCount,
    });
    logger.info(
      `openviking: committed sessionHash=${hashLogValue(ovSessionId)}, status=${commitResult.status}, archived=${commitResult.archived ?? false}, taskPresent=${Boolean(commitResult.task_id)}`,
    );

    completionDiag("afterTurn_commit", ovSessionId, {
      pendingTokens,
      commitTokenThreshold: cfg.commitTokenThreshold,
      commitReason: forceCommitForMemoryIntent && pendingTokens < cfg.commitTokenThreshold ? "memory_intent" : "threshold",
      status: commitResult.status,
      archived: commitResult.archived ?? false,
      taskId: commitResult.task_id ?? null,
      extractedMemories: totalExtractedMemories(commitResult.memories_extracted),
      senderIdFound: sender.found,
    });
    if (commitResult.task_id && cfg.logFindRequests) {
      logger.info(
        `openviking: Phase2 memory extraction runs asynchronously on the server (task_id=${commitResult.task_id}). ` +
          "memories_extracted appears only after that task completes — not in this immediate response.",
      );
      void pollPhase2ExtractionOutcome(client, commitResult.task_id, agentId, logger, ovSessionId);
    }
  } catch (err) {
    logger.warn?.(`openviking: afterTurn failed errorType=${errorType(err)}`);
    completionDiag("afterTurn_error", sessionId ?? "(unknown)", {
      error: String(err),
      senderIdFound: false,
    });
    if (/^(?:WECHAT_TURN_IDENTITY_MISSING|API_TURN_IDENTITY_MISSING|TURN_IDENTITY_CHANNEL_MISMATCH|TURN_IDENTITY_EXPIRED)$/.test(
      err instanceof Error ? err.message : String(err),
    )) {
      throw err;
    }
  }
}

function validTokenCount(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? value
    : undefined;
}

function compactFailureResult(
  reason: string,
  tokensBefore: number,
  details: unknown,
): CompactOpenVikingSessionResult {
  return {
    ok: false,
    compacted: false,
    reason,
    result: {
      summary: "",
      firstKeptEntryId: "",
      tokensBefore,
      tokensAfter: undefined,
      details,
    },
  };
}

export async function compactOpenVikingSession({
  sessionId,
  sessionKey,
  runtimeContext,
  identityHashSecret,
  tokenBudget,
  currentTokenCount,
  force,
  compactionTarget,
  customInstructions,
  getClient,
  getClientForSender,
  logger,
  resolveAgentId,
  isBypassedSession,
  diag,
}: CompactOpenVikingSessionParams): Promise<CompactOpenVikingSessionResult> {
  const ovSessionId = openClawSessionToOvStorageId(sessionId, sessionKey);
  diag = safeDiag(diag);
  diag("compact_entry", ovSessionId, {
    tokenBudget,
    force: force ?? false,
    currentTokenCount: currentTokenCount ?? null,
    compactionTarget: compactionTarget ?? null,
    hasCustomInstructions: typeof customInstructions === "string" &&
      customInstructions.trim().length > 0,
  });

  if (isBypassedSession({ sessionId, sessionKey })) {
    diag("compact_result", ovSessionId, {
      ok: true,
      compacted: false,
      reason: "session_bypassed",
    });
    return {
      ok: true,
      compacted: false,
      reason: "session_bypassed",
    };
  }

  const sender = await resolveLifecycleSenderIdentity({ runtimeContext, sessionKey, identityHashSecret });

  const client = getClientForSender
    ? sender.scope
      ? await getClientForSender(sender.scope)
      : undefined
    : await getClient();
  if (!client) {
    diag("compact_result", ovSessionId, {
      ok: true,
      compacted: false,
      reason: "identity_missing",
    });
    logger.warn?.(
      `openviking: compact skipped because sender identity is missing (sessionKeyPresent=${Boolean(sessionKey)})`,
    );
    return {
      ok: true,
      compacted: false,
      reason: "identity_missing",
    };
  }
  const agentId = resolveAgentId(sessionId, sessionKey, ovSessionId);
  const tokensBeforeOriginal = validTokenCount(currentTokenCount);
  let preCommitEstimatedTokens: number | undefined;
  if (typeof tokensBeforeOriginal !== "number") {
    try {
      const preCtx = await client.getSessionContext(ovSessionId, tokenBudget, agentId);
      if (typeof preCtx.estimatedTokens === "number" && Number.isFinite(preCtx.estimatedTokens)) {
        preCommitEstimatedTokens = preCtx.estimatedTokens;
      }
    } catch (preCtxErr) {
      logger.info(
        `openviking: compact pre-ctx fetch failed sessionHash=${hashLogValue(ovSessionId)}, tokenBudget=${tokenBudget}, agentIdPreview=${agentIdPreview(agentId)}, errorType=${errorType(preCtxErr)}`,
      );
    }
  }

  const tokensBefore = tokensBeforeOriginal ?? preCommitEstimatedTokens ?? -1;

  try {
    logger.info(
      `openviking: compact committing sessionHash=${hashLogValue(ovSessionId)}, wait=true, tokenBudget=${tokenBudget}`,
    );
    const commitResult = await client.commitSession(ovSessionId, {
      wait: true,
      agentId,
      keepRecentCount: 0,
    });
    const memCount = totalExtractedMemories(commitResult.memories_extracted);

    if (commitResult.status === "failed") {
      logger.warn?.(
        `openviking: compact commit Phase 2 failed sessionHash=${hashLogValue(ovSessionId)}, errorType=${errorType(commitResult.error)}`,
      );
      diag("compact_result", ovSessionId, {
        ok: false,
        compacted: false,
        reason: "commit_failed",
        status: commitResult.status,
        archived: commitResult.archived ?? false,
        taskId: commitResult.task_id ?? null,
        error: commitResult.error ?? null,
      });
      return compactFailureResult("commit_failed", tokensBefore, { commit: commitResult });
    }

    if (commitResult.status === "timeout") {
      logger.warn?.(
        `openviking: compact commit Phase 2 timed out sessionHash=${hashLogValue(ovSessionId)}, taskPresent=${Boolean(commitResult.task_id)}`,
      );
      diag("compact_result", ovSessionId, {
        ok: false,
        compacted: false,
        reason: "commit_timeout",
        status: commitResult.status,
        archived: commitResult.archived ?? false,
        taskId: commitResult.task_id ?? null,
      });
      return compactFailureResult("commit_timeout", tokensBefore, { commit: commitResult });
    }

    logger.info(
      `openviking: compact committed sessionHash=${hashLogValue(ovSessionId)}, archived=${commitResult.archived ?? false}, memories=${memCount}, taskPresent=${Boolean(commitResult.task_id)}`,
    );

    if (!commitResult.archived) {
      logger.info(
        `openviking: compact no archive sessionHash=${hashLogValue(ovSessionId)}, ` +
          `tokensBefore=${tokensBefore}, tokensAfter=${tokensBefore}`,
      );
      diag("compact_result", ovSessionId, {
        ok: true,
        compacted: false,
        reason: "commit_no_archive",
        status: commitResult.status,
        archived: commitResult.archived ?? false,
        taskId: commitResult.task_id ?? null,
        memories: memCount,
        tokensBefore,
      });
      return {
        ok: true,
        compacted: false,
        reason: "commit_no_archive",
        result: {
          summary: "",
          tokensBefore,
          tokensAfter: tokensBefore >= 0 ? tokensBefore : undefined,
          details: {
            commit: commitResult,
          },
        },
      };
    }

    let summary = "";
    const firstKeptEntryId = commitResult.archive_uri?.split("/").pop() ?? "";
    let tokensAfter: number | undefined;
    let contextFetchError: string | undefined;

    try {
      const ctx = await client.getSessionContext(ovSessionId, tokenBudget, agentId);
      logger.info(`openviking: compact context loaded sessionHash=${hashLogValue(ovSessionId)}, messagesCount=${ctx.messages?.length ?? 0}`);
      if (typeof ctx.latest_archive_overview === "string") {
        summary = ctx.latest_archive_overview.trim();
      }
      if (typeof ctx.estimatedTokens === "number" && Number.isFinite(ctx.estimatedTokens)) {
        tokensAfter = ctx.estimatedTokens;
      }
      logger.info(
        `openviking: compact restored session content sessionHash=${hashLogValue(ovSessionId)}: ` +
          `messages=${ctx.messages?.length ?? 0}, ` +
          `latestArchiveOverview=${summary.length > 0 ? "present" : "empty"} (${summary.length} chars), ` +
          `preArchiveAbstracts=${ctx.pre_archive_abstracts?.length ?? 0}, ` +
          `estimatedTokens=${ctx.estimatedTokens}`,
      );
    } catch (ctxErr) {
      contextFetchError = String(ctxErr);
      logger.info(
        `openviking: compact context fetch failed sessionHash=${hashLogValue(ovSessionId)}, tokenBudget=${tokenBudget}, agentIdPreview=${agentIdPreview(agentId)}, errorType=${errorType(ctxErr)}`,
      );
    }

    logger.info(
      `openviking: compact tokens sessionHash=${hashLogValue(ovSessionId)}, ` +
        `tokensBefore=${tokensBefore}, tokensAfter=${tokensAfter ?? "unknown"}, ` +
        `latestArchiveId=${firstKeptEntryId || "none"}`,
    );

    diag("compact_result", ovSessionId, {
      ok: true,
      compacted: true,
      reason: "commit_completed",
      status: commitResult.status,
      archived: commitResult.archived ?? false,
      taskId: commitResult.task_id ?? null,
      memories: memCount,
      tokensBefore,
      tokensAfter: tokensAfter ?? null,
      latestArchiveId: firstKeptEntryId || null,
      summaryPresent: summary.length > 0,
    });

    return {
      ok: true,
      compacted: true,
      reason: "commit_completed",
      result: {
        summary,
        firstKeptEntryId,
        tokensBefore,
        tokensAfter,
        details: contextFetchError
          ? {
              commit: commitResult,
              contextError: contextFetchError,
            }
          : {
              commit: commitResult,
            },
      },
    };
  } catch (err) {
    const errorMessage = String(err);
    if (errorMessage.includes("[NOT_FOUND]") && errorMessage.includes("Session not found")) {
      logger.info(
        `openviking: compact skipped because OV session does not exist sessionHash=${hashLogValue(ovSessionId)}, agentIdPreview=${agentIdPreview(agentId)}`,
      );
      diag("compact_result", ovSessionId, {
        ok: true,
        compacted: false,
        reason: "session_not_found",
        error: errorMessage,
      });
      return {
        ok: true,
        compacted: false,
        reason: "session_not_found",
      };
    }
    logger.warn?.(`openviking: compact commit failed sessionHash=${hashLogValue(ovSessionId)}, errorType=${errorType(err)}`);
    diag("compact_error", ovSessionId, {
      error: errorMessage,
    });
    return compactFailureResult("commit_error", tokensBefore, { error: errorMessage });
  }
}
