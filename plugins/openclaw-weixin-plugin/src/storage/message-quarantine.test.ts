import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { appendMessageQuarantine, getMessageQuarantineFilePath } from "./message-quarantine.js";

describe("message quarantine", () => {
  let stateDir: string;
  const previousStateDir = process.env.OPENCLAW_STATE_DIR;

  beforeEach(() => {
    stateDir = fs.mkdtempSync(path.join(os.tmpdir(), "openclaw-weixin-quarantine-"));
    process.env.OPENCLAW_STATE_DIR = stateDir;
  });

  afterEach(() => {
    if (previousStateDir === undefined) {
      delete process.env.OPENCLAW_STATE_DIR;
    } else {
      process.env.OPENCLAW_STATE_DIR = previousStateDir;
    }
    fs.rmSync(stateDir, { recursive: true, force: true });
  });

  it("appends only hashed identifiers and the normalized error code", () => {
    const accountId = "account-sensitive";
    const message = {
      message_id: 123456,
      client_id: "client-sensitive",
      session_id: "session-sensitive",
      from_user_id: "peer-sensitive",
      context_token: "token-must-not-be-written",
      item_list: [{ msg_id: "item-sensitive", text_item: { text: "secret body" } }],
    };

    appendMessageQuarantine(accountId, message, "WECHAT_AGENT_NOT_READY");

    const filePath = getMessageQuarantineFilePath(accountId);
    const raw = fs.readFileSync(filePath, "utf8").trim();
    const record = JSON.parse(raw) as Record<string, unknown>;

    expect(record).toMatchObject({ errorCode: "WECHAT_AGENT_NOT_READY" });
    expect(record.timestamp).toEqual(expect.any(String));
    expect(record.messageIdHash).toMatch(/^[a-f0-9]{64}$/);
    expect(record.accountHash).toMatch(/^[a-f0-9]{64}$/);
    expect(record.peerHash).toMatch(/^[a-f0-9]{64}$/);
    expect(raw).not.toContain(accountId);
    expect(raw).not.toContain("peer-sensitive");
    expect(raw).not.toContain("client-sensitive");
    expect(raw).not.toContain("session-sensitive");
    expect(raw).not.toContain("item-sensitive");
    expect(raw).not.toContain("token-must-not-be-written");
    expect(raw).not.toContain("secret body");
  });

  it("appends one JSON line per quarantined message", () => {
    appendMessageQuarantine("account-a", { message_id: 1, from_user_id: "peer-a" }, "ERR_A");
    appendMessageQuarantine("account-a", { message_id: 2, from_user_id: "peer-b" }, "ERR_B");

    const lines = fs
      .readFileSync(getMessageQuarantineFilePath("account-a"), "utf8")
      .trim()
      .split(/\r?\n/);

    expect(lines).toHaveLength(2);
    expect(lines.map((line) => JSON.parse(line).errorCode)).toEqual(["ERR_A", "ERR_B"]);
  });
});
