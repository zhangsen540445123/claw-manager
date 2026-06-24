import { describe, expect, it } from "vitest";

import { MessageItemType, type WeixinMessage } from "../api/types.js";

import { weixinMessageToMsgContext } from "./inbound.js";

describe("weixinMessageToMsgContext", () => {
  it("propagates the Weixin sender id as runtime identity fields", () => {
    const ctx = weixinMessageToMsgContext(message("wx_sender_ABC"), "account_1");

    expect(ctx.SenderId).toBe("wx_sender_ABC");
    expect(ctx.senderId).toBe("wx_sender_ABC");
    expect(ctx.requesterSenderId).toBe("wx_sender_ABC");
  });

  it("does not invent a default sender identity when Weixin sender id is missing", () => {
    const ctx = weixinMessageToMsgContext(message(undefined), "account_1");

    expect(ctx.SenderId).toBe("");
    expect(ctx.senderId).toBe("");
    expect(ctx.requesterSenderId).toBe("");
  });
});

function message(fromUserId: string | undefined): WeixinMessage {
  return {
    from_user_id: fromUserId,
    create_time_ms: 1710000000000,
    item_list: [
      {
        type: MessageItemType.TEXT,
        text_item: {
          text: "hello",
        },
      },
    ],
  };
}
