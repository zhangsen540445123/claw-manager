import { describe, expect, it } from "vitest";

import { resolveSenderIdentity } from "../../identity.js";

describe("resolveSenderIdentity", () => {
  it("derives a stable user id from the trimmed sender id", () => {
    const first = resolveSenderIdentity("  wxid_Alpha  ", "secret");
    const second = resolveSenderIdentity("wxid_Alpha", "secret");

    expect(first).toEqual(second);
    expect(first?.senderId).toBe("wxid_Alpha");
    expect(first?.senderHash).toMatch(/^[0-9a-f]{32}$/);
    expect(first?.openVikingUserId).toBe(`wx_${first?.senderHash}`);
  });

  it("does not merge sender ids that differ only by case", () => {
    const upper = resolveSenderIdentity("wxid_Alpha", "secret");
    const lower = resolveSenderIdentity("wxid_alpha", "secret");

    expect(upper?.openVikingUserId).not.toBe(lower?.openVikingUserId);
  });

  it("returns undefined for missing sender identity inputs", () => {
    expect(resolveSenderIdentity(undefined, "secret")).toBeUndefined();
    expect(resolveSenderIdentity(123, "secret")).toBeUndefined();
    expect(resolveSenderIdentity("   ", "secret")).toBeUndefined();
  });

  it("returns undefined when the hash secret is missing", () => {
    expect(resolveSenderIdentity("wxid_Alpha", "")).toBeUndefined();
    expect(resolveSenderIdentity("wxid_Alpha", "   ")).toBeUndefined();
  });
});
