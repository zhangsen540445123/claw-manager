import { describe, expect, it } from "vitest";

import { redactIdentity } from "./redact.js";

describe("redactIdentity", () => {
  it("never returns the raw channel identity", () => {
    expect(redactIdentity("wechat-user-secret")).toBe("present");
    expect(redactIdentity("  ")).toBe("missing");
  });
});
