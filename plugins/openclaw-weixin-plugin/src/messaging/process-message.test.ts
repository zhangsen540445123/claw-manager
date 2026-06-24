import { describe, expect, it } from "vitest";

import { attachSenderRuntimeIdentity } from "./process-message.js";

describe("attachSenderRuntimeIdentity", () => {
  it("adds sender identity fields to the finalized dispatch context", () => {
    const finalized = { Body: "hello" };

    const ctx = attachSenderRuntimeIdentity(finalized, "wx_sender_ABC");

    expect(ctx).toBe(finalized);
    expect(ctx.SenderId).toBe("wx_sender_ABC");
    expect(ctx.senderId).toBe("wx_sender_ABC");
    expect(ctx.requesterSenderId).toBe("wx_sender_ABC");
  });

  it("keeps identity fields empty when the inbound Weixin sender is empty", () => {
    const ctx = attachSenderRuntimeIdentity({ Body: "hello" }, "");

    expect(ctx.SenderId).toBe("");
    expect(ctx.senderId).toBe("");
    expect(ctx.requesterSenderId).toBe("");
  });
});
