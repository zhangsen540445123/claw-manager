export type UserAgentIdentity = {
  agentId: string;
  openVikingUserId: string;
  created: boolean;
};

const USER_AGENT_ID_PATTERN = /^user_[0-9a-f]{32}$/;
const OPENVIKING_USER_ID_PATTERN = /^wx_[0-9a-f]{32}$/;

export async function resolveUserAgentIdentity(
  wechatUserId: string,
  options: { env?: NodeJS.ProcessEnv; fetcher?: typeof fetch; timeoutMs?: number } = {},
): Promise<UserAgentIdentity> {
  const env = options.env ?? process.env;
  const baseUrl = trim(env.CLAW_MANAGER_INTERNAL_BASE_URL).replace(/\/+$/, "");
  const token = trim(env.OPENVIKING_BROKER_TOKEN);
  const instanceId = trim(env.OPENVIKING_OPENCLAW_INSTANCE_ID);
  const normalizedWechatUserId = trim(wechatUserId);
  if (!baseUrl || !token || !instanceId || !normalizedWechatUserId) {
    throw new Error("identity resolver configuration is incomplete");
  }

  const timeoutSignal = AbortSignal.timeout(options.timeoutMs ?? 10_000);
  let response: Response;
  try {
    response = await (options.fetcher ?? fetch)(`${baseUrl}/api/internal/user-agents/resolve`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ instanceId, wechatUserId: normalizedWechatUserId }),
      signal: timeoutSignal,
    });
  } catch (error) {
    if (timeoutSignal.aborted) {
      throw new Error("identity resolver timed out");
    }
    throw error;
  }
  if (!response.ok) {
    throw new Error(`identity resolver rejected request with HTTP ${response.status}`);
  }

  const payload = await response.json() as Partial<UserAgentIdentity>;
  const agentId = trim(payload.agentId);
  const openVikingUserId = trim(payload.openVikingUserId);
  if (!USER_AGENT_ID_PATTERN.test(agentId) || !OPENVIKING_USER_ID_PATTERN.test(openVikingUserId)) {
    throw new Error("invalid identity resolver response");
  }
  return { agentId, openVikingUserId, created: payload.created === true };
}

function trim(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
