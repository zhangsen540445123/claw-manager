import { createHmac } from "node:crypto";

export type SenderIdentity = {
  senderId: string;
  senderHash: string;
  openVikingUserId: string;
};

export function resolveApiSenderIdentity(input: unknown): SenderIdentity | undefined {
  if (typeof input !== "string") {
    return undefined;
  }
  const senderId = input.trim();
  const match = /^api:([0-9a-f]{32})$/i.exec(senderId);
  if (!match) {
    return undefined;
  }
  const senderHash = match[1]!.toLowerCase();
  return { senderId, senderHash, openVikingUserId: `api_${senderHash}` };
}

export function resolveSenderIdentity(input: unknown, secret: string): SenderIdentity | undefined {
  if (typeof input !== "string") {
    return undefined;
  }
  const senderId = input.trim();
  const normalizedSecret = secret.trim();
  if (!senderId || !normalizedSecret) {
    return undefined;
  }
  const senderHash = createHmac("sha256", normalizedSecret)
    .update(senderId, "utf8")
    .digest("hex")
    .slice(0, 32);
  return { senderId, senderHash, openVikingUserId: `wx_${senderHash}` };
}
