import { OpenVikingClient } from "../client.js";
import type { HttpTransport } from "../adapters/http-transport.js";

type Logger = {
  info: (message: string) => void;
};

type ClientRuntimeConfig = {
  baseUrl: string;
  apiKey: string;
  peer_role: "none" | "assistant" | "person";
  peer_prefix: string;
  timeoutMs: number;
  accountId?: string;
  userId?: string;
  identityHashSecret?: string;
  clawManagerInternalBaseUrl?: string;
  openVikingBrokerToken?: string;
  logFindRequests: boolean;
};

type ClientSenderIdentity = {
  senderId?: string;
  senderHash?: string;
  openVikingUserId: string;
};

function trimString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function isExplicitOpenVikingUserId(value: string): boolean {
  return /^(?:wx|api)_[0-9a-f]{32}$/.test(value);
}

function resolveClientSenderIdentity(input: unknown): ClientSenderIdentity | undefined {
  if (input && typeof input === "object") {
    const record = input as Record<string, unknown>;
    const explicitUserId = trimString(record.openVikingUserId) || trimString(record.openvikingUserId);
    if (explicitUserId) {
      return {
        senderId: trimString(record.senderId) || undefined,
        senderHash: trimString(record.senderHash) || undefined,
        openVikingUserId: explicitUserId,
      };
    }
  }

  const stringInput = trimString(input);
  if (isExplicitOpenVikingUserId(stringInput)) {
    return {
      openVikingUserId: stringInput,
    };
  }
  return undefined;
}

export function createOpenVikingClientRuntime(options: {
  cfg: ClientRuntimeConfig;
  rawPeerPrefix: unknown;
  logger: Logger;
  transport?: HttpTransport;
}) {
  const { cfg, logger } = options;

  if (cfg.logFindRequests) {
    logger.info(
      "openviking: routing debug logging enabled (config logFindRequests, or env OPENVIKING_LOG_ROUTING=1 / OPENVIKING_DEBUG=1)",
    );
  }

  const verboseRoutingInfo = (message: string) => {
    if (cfg.logFindRequests) {
      logger.info(message);
    }
  };

  verboseRoutingInfo(
    `openviking: loaded plugin config peer_role="${cfg.peer_role}" peer_prefix="${cfg.peer_prefix}" ` +
      `(raw peer_prefix=${JSON.stringify(options.rawPeerPrefix ?? "(missing)")}; ` +
      `${
        cfg.peer_prefix
          ? 'non-empty → assistant peer_id is <peer_prefix>_<ctx.agentId> when peer_role="assistant", or <peer_prefix>_main when ctx.agentId is unknown'
          : 'empty → assistant peer_id follows OpenClaw ctx.agentId when peer_role="assistant", or "main" when ctx.agentId is unknown'
      })`,
  );

  const routingDebugLog = cfg.logFindRequests
    ? (msg: string) => {
        logger.info(msg);
      }
    : undefined;

  const clientPromise = Promise.resolve(
    new OpenVikingClient(
      cfg.baseUrl,
      cfg.apiKey,
      cfg.peer_prefix,
      cfg.timeoutMs,
      cfg.accountId,
      cfg.userId,
      routingDebugLog,
      { transport: options.transport },
    ),
  );

  const getClient = (): Promise<OpenVikingClient> => clientPromise;
  const trimmedIdentitySecret = cfg.identityHashSecret?.trim() ?? "";
  const brokerBaseUrl = cfg.clawManagerInternalBaseUrl?.trim().replace(/\/+$/, "") ?? "";
  const brokerToken = cfg.openVikingBrokerToken?.trim() ?? "";
  const clientByUserId = new Map<string, OpenVikingClient>();

  async function resolveBrokerUserKey(identity: ClientSenderIdentity): Promise<string> {
    if (!brokerBaseUrl || !brokerToken) {
      return "";
    }
    const headers = new Headers();
    headers.set("Authorization", `Bearer ${brokerToken}`);
    headers.set("Content-Type", "application/json");
    const response = await (options.transport ?? fetch)(
      `${brokerBaseUrl}/api/internal/openviking/users/resolve`,
      {
        method: "POST",
        headers,
        body: JSON.stringify({
          ...(identity.senderId ? { senderId: identity.senderId } : {}),
          openvikingUserId: identity.openVikingUserId,
        }),
      },
    );
    const payload = (await response.json().catch(() => ({}))) as {
      result?: { user?: { userKey?: string }; userKey?: string };
      user?: { userKey?: string };
      userKey?: string;
      error?: string | { message?: string };
    };
    if (!response.ok) {
      const errorMessage =
        typeof payload.error === "string"
          ? payload.error
          : payload.error?.message || `HTTP ${response.status}`;
      throw new Error(`OpenViking user key broker request failed: ${errorMessage}`);
    }
    const userKey = payload.user?.userKey || payload.result?.user?.userKey || payload.userKey || payload.result?.userKey || "";
    if (!userKey.trim()) {
      throw new Error("OpenViking user key broker did not return userKey");
    }
    return userKey;
  }

  const getClientForSender = trimmedIdentitySecret
    ? async (senderId: unknown): Promise<OpenVikingClient | undefined> => {
        const identity = resolveClientSenderIdentity(senderId);
        if (!identity) {
          return undefined;
        }
        const cached = clientByUserId.get(identity.openVikingUserId);
        if (cached) {
          return cached;
        }
        const baseClient = await clientPromise;
        if (brokerBaseUrl && brokerToken) {
          const userKey = await resolveBrokerUserKey(identity);
          const scoped = baseClient.withApiKey(userKey);
          clientByUserId.set(identity.openVikingUserId, scoped);
          return scoped;
        }
        return baseClient.withUser(identity.openVikingUserId);
      }
    : undefined;

  return {
    getClient,
    getClientForSender,
    verboseRoutingInfo,
  };
}
