import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  getUpdates,
  processOneMessage,
  saveGetUpdatesBuf,
  appendMessageQuarantine,
  runtimeLog,
  runtimeError,
  loggerInfo,
  loggerError,
} = vi.hoisted(() => ({
  getUpdates: vi.fn(),
  processOneMessage: vi.fn(),
  saveGetUpdatesBuf: vi.fn(),
  appendMessageQuarantine: vi.fn(),
  runtimeLog: vi.fn(),
  runtimeError: vi.fn(),
  loggerInfo: vi.fn(),
  loggerError: vi.fn(),
}));

vi.mock("../api/api.js", () => ({
  getUpdates,
  classifyFetchError: () => ({ type: "unknown", description: "test" }),
}));
vi.mock("../api/config-cache.js", () => ({
  WeixinConfigManager: class {
    async getForUser() {
      return {};
    }
  },
}));
vi.mock("../api/session-guard.js", () => ({
  STALE_TOKEN_ERRCODE: 42,
  pauseSession: vi.fn(),
  getRemainingPauseMs: () => 1,
}));
vi.mock("../messaging/process-message.js", () => ({ processOneMessage }));
vi.mock("../storage/message-quarantine.js", () => ({ appendMessageQuarantine }));
vi.mock("../storage/sync-buf.js", () => ({
  getSyncBufFilePath: () => "sync.buf",
  loadGetUpdatesBuf: () => "cursor-old",
  saveGetUpdatesBuf,
}));
vi.mock("../util/logger.js", () => {
  const log = {
    debug: vi.fn(),
    info: loggerInfo,
    warn: vi.fn(),
    error: loggerError,
  };
  return { logger: { ...log, withAccount: () => log } };
});

import { monitorWeixinProvider, sleep } from "./monitor.js";

describe("WeChat monitor message isolation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("retries a poison message three times, quarantines it, processes the rest, and advances the cursor", async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    const poison = message(1, "poison-peer");
    const healthy = message(2, "healthy-peer");
    getUpdates.mockResolvedValueOnce({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [poison, healthy],
    });
    processOneMessage.mockImplementation(async (current) => {
      if (current === poison) {
        throw new Error("WECHAT_AGENT_NOT_READY");
      }
      abortController.abort();
    });

    const monitor = monitorWeixinProvider(options(abortController.signal));
    await vi.advanceTimersByTimeAsync(4_000);
    await monitor;

    expect(processOneMessage).toHaveBeenCalledTimes(4);
    expect(processOneMessage.mock.calls.map(([current]) => current)).toEqual([
      poison,
      poison,
      poison,
      healthy,
    ]);
    expect(appendMessageQuarantine).toHaveBeenCalledOnce();
    expect(appendMessageQuarantine).toHaveBeenCalledWith(
      "account-test",
      poison,
      "WECHAT_AGENT_NOT_READY",
    );
    expect(saveGetUpdatesBuf).toHaveBeenCalledWith("sync.buf", "cursor-next");
    expect(runtimeError.mock.calls.flat().join("\n")).toContain("agent not ready");
    expect(runtimeError.mock.calls.flat().join("\n")).toContain("message quarantined");
    expect(runtimeError.mock.calls.flat().join("\n")).not.toContain("weixin getUpdates error");
    expect(runtimeError.mock.calls.flat().join("\n")).not.toContain("backing off 30s");
    vi.useRealTimers();
  });

  it("logs a non-agent business failure as a message routing error without network backoff", async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    const poison = message(1, "poison-peer");
    getUpdates.mockResolvedValueOnce({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [poison],
    });
    processOneMessage.mockRejectedValue(new Error("handler exploded"));
    appendMessageQuarantine.mockImplementation(() => {
      abortController.abort();
    });

    const monitor = monitorWeixinProvider(options(abortController.signal));
    await vi.advanceTimersByTimeAsync(4_000);
    abortController.abort();
    await monitor;

    const errors = runtimeError.mock.calls.flat().join("\n");
    expect(errors).toContain("message routing error");
    expect(errors).toContain("message quarantined");
    expect(errors).not.toContain("weixin getUpdates error");
    expect(errors).not.toContain("backing off 30s");
    expect(saveGetUpdatesBuf).toHaveBeenCalledWith("sync.buf", "cursor-next");
    vi.useRealTimers();
  });

  it("continues and advances the cursor even when writing quarantine fails", async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    getUpdates.mockResolvedValueOnce({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [message(1, "poison-peer")],
    });
    processOneMessage.mockRejectedValue(new Error("WECHAT_AGENT_NOT_READY"));
    appendMessageQuarantine.mockImplementation(() => {
      abortController.abort();
      throw new Error("disk full");
    });

    const monitor = monitorWeixinProvider(options(abortController.signal));
    await vi.advanceTimersByTimeAsync(4_000);
    abortController.abort();
    await monitor;

    expect(saveGetUpdatesBuf).toHaveBeenCalledWith("sync.buf", "cursor-next");
    expect(loggerError.mock.calls.flat().join("\n")).toContain("quarantine write failed");
    vi.useRealTimers();
  });
});

describe("WeChat monitor getUpdates failures", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("backs off only after three consecutive getUpdates network failures", async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    getUpdates.mockRejectedValue(new Error("network down"));

    const monitor = monitorWeixinProvider(options(abortController.signal));
    await vi.advanceTimersByTimeAsync(0);
    expect(getUpdates).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(getUpdates).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(getUpdates).toHaveBeenCalledTimes(3);

    const errors = runtimeError.mock.calls.flat().join("\n");
    expect(errors).toContain("weixin getUpdates network error (3/3)");
    expect(errors).toContain("backing off 30s");

    abortController.abort();
    await vi.advanceTimersByTimeAsync(30_000);
    await monitor;
    vi.useRealTimers();
  });
});

describe("monitor sleep", () => {
  it("removes the abort listener after the timer completes normally", async () => {
    vi.useFakeTimers();
    let abortListener: EventListener | undefined;
    const signal = {
      aborted: false,
      addEventListener: vi.fn((_type: string, listener: EventListener) => {
        abortListener = listener;
      }),
      removeEventListener: vi.fn(),
    } as unknown as AbortSignal;

    const waiting = sleep(1_000, signal);
    await vi.advanceTimersByTimeAsync(1_000);
    await waiting;

    expect(signal.removeEventListener).toHaveBeenCalledWith("abort", abortListener);
    vi.useRealTimers();
  });
});

function message(messageId: number, peerId: string) {
  return {
    message_id: messageId,
    from_user_id: peerId,
    session_id: `session-${messageId}`,
    item_list: [{ msg_id: `item-${messageId}` }],
  };
}

function options(abortSignal: AbortSignal) {
  return {
    baseUrl: "http://wechat.test",
    cdnBaseUrl: "http://cdn.test",
    accountId: "account-test",
    config: {},
    channelRuntime: {} as never,
    abortSignal,
    runtime: {
      log: runtimeLog,
      error: runtimeError,
    },
  };
}
