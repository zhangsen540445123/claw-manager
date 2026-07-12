import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { callMiniappBridge } from "./index.js";

const env = {
  CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080/",
  OPENVIKING_BROKER_TOKEN: "broker-secret",
  OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
};

describe("miniapp bridge", () => {
  it("declares the registered tool in the OpenClaw manifest contract", () => {
    const manifest = JSON.parse(readFileSync(new URL("./openclaw.plugin.json", import.meta.url), "utf8"));
    expect(manifest.contracts?.tools).toContain("miniapp_api_call");
  });

  it("forwards only sender scope and approved business parameters", async () => {
    const fetcher = vi.fn(async (_url: string, init?: RequestInit) => new Response(JSON.stringify({ result: { code: 200 } }), { status: 200 }));
    await callMiniappBridge({ actionKey: "daily_checklist", parameters: { date: "2026-07-12" } }, { requesterSenderId: "wechat-user-1" }, env, fetcher as typeof fetch);
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toContain("/actions/daily_checklist");
    const body = JSON.parse(String(init?.body));
    expect(body.instanceId).toBe("inst-1");
    expect(body.requesterSenderId).toBe("wechat-user-1");
    expect(body.parameters).toEqual({ date: "2026-07-12" });
    expect(JSON.stringify(body)).not.toContain("cm_user_");
  });

  it("rejects an unknown action before calling the network", async () => {
    const fetcher = vi.fn();
    await expect(callMiniappBridge({ actionKey: "arbitrary_http", parameters: {} }, { requesterSenderId: "sender" }, env, fetcher as typeof fetch))
      .rejects.toThrow("unsupported miniapp actionKey");
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("rejects missing sender identity without fallback", async () => {
    await expect(callMiniappBridge({ actionKey: "goal_list" }, {}, env, vi.fn() as typeof fetch))
      .rejects.toThrow("identity unavailable");
  });
});
