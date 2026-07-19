import { describe, expect, it } from "vitest";

import { loggerAccountLabel } from "./logger.js";

describe("loggerAccountLabel", () => {
  it("does not expose the raw account id", () => {
    expect(loggerAccountLabel("bot-account-secret")).toBe("account");
    expect(loggerAccountLabel("  ")).toBe("");
  });
});
