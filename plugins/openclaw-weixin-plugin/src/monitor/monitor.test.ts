import { beforeEach, describe, expect, it, vi } from "vitest";

const { getUpdates, processOneMessage, saveGetUpdatesBuf } = vi.hoisted(() => ({
  getUpdates: vi.fn(),
  processOneMessage: vi.fn(),
  saveGetUpdatesBuf: vi.fn(),
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
vi.mock("../storage/sync-buf.js", () => ({
  getSyncBufFilePath: () => "sync.buf",
  loadGetUpdatesBuf: () => "cursor-old",
  saveGetUpdatesBuf,
}));
vi.mock("../util/logger.js", () => {
  const log = { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() };
  return { logger: { ...log, withAccount: () => log } };
});

import { monitorWeixinProvider, sleep } from "./monitor.js";

describe("WeChat monitor sync cursor", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does not acknowledge the next cursor when message processing fails", async () => {
    const abortController = new AbortController();
    getUpdates.mockResolvedValueOnce({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [{ from_user_id: "wechat-user", item_list: [] }],
    });
    processOneMessage.mockImplementationOnce(async () => {
      abortController.abort();
      throw new Error("retryable processing failure");
    });

    await monitorWeixinProvider(options(abortController.signal));

    expect(saveGetUpdatesBuf).not.toHaveBeenCalled();
  });

  it("acknowledges the next cursor only after all messages are processed", async () => {
    const abortController = new AbortController();
    getUpdates.mockResolvedValueOnce({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [{ from_user_id: "wechat-user", item_list: [] }],
    });
    processOneMessage.mockImplementationOnce(async () => {
      abortController.abort();
    });

    await monitorWeixinProvider(options(abortController.signal));

    expect(saveGetUpdatesBuf).toHaveBeenCalledOnce();
    expect(saveGetUpdatesBuf).toHaveBeenCalledWith("sync.buf", "cursor-next");
  });

  it("backs off after three consecutive message processing failures", async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    getUpdates.mockResolvedValue({
      ret: 0,
      get_updates_buf: "cursor-next",
      msgs: [{ from_user_id: "wechat-user", item_list: [] }],
    });
    processOneMessage.mockRejectedValue(new Error("retryable processing failure"));

    const monitor = monitorWeixinProvider(options(abortController.signal));
    await vi.advanceTimersByTimeAsync(0);
    expect(processOneMessage).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(processOneMessage).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(processOneMessage).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(processOneMessage).toHaveBeenCalledTimes(3);

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

function options(abortSignal: AbortSignal) {
  return {
    baseUrl: "http://wechat.test",
    cdnBaseUrl: "http://cdn.test",
    accountId: "account-test",
    config: {},
    channelRuntime: {} as never,
    abortSignal,
  };
}
