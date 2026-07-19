import { mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import type { OpenClawConfig, PluginRuntime } from "openclaw/plugin-sdk/core";

type ConfigRecord = Record<string, any>;

export type WeixinConfigRuntime = {
  current?: () => ConfigRecord;
  mutateConfigFile?: (params: {
    base?: "runtime" | "source";
    afterWrite?: { mode: "auto" };
    mutate: (draft: ConfigRecord) => Promise<unknown> | unknown;
  }) => Promise<{ nextConfig?: ConfigRecord; result?: unknown }>;
};

export type WeixinDynamicAgentRouteParams = {
  cfg: OpenClawConfig | ConfigRecord;
  configRuntime?: WeixinConfigRuntime;
  channelRuntime: PluginRuntime["channel"];
  accountId: string;
  peerId: string;
  agentId: string;
  homeDir?: string;
};

const REQUIRED_DENIED_TOOLS = ["write", "edit", "apply_patch", "exec", "process"] as const;
const WEIXIN_CHANNEL_ID = "openclaw-weixin";

let mutationChain = Promise.resolve();

const DEFAULT_WORKSPACE_PRESET = {
  agentsMd: [
    "# Claw Manager WeChat Agent",
    "",
    "This workspace belongs to one OpenViking sender.",
    "Use sender-scoped OpenViking memory for user facts and preferences.",
    "You may edit files only inside this workspace through the workspace file tools.",
    "",
  ].join("\n"),
  soulMd: [
    "# SOUL.md - WeChat Agent",
    "",
    "Be warm, concise, and useful. Do not claim a tool operation succeeded when it failed.",
    "",
  ].join("\n"),
  identityMd: "# IDENTITY.md - WeChat Agent\n\nClaw Manager WeChat assistant.\n",
  toolsMd: [
    "# TOOLS.md - WeChat Agent",
    "",
    "Use the configured business, OpenViking, image, and Artifact tools as documented by their schemas.",
    "",
  ].join("\n"),
  heartbeatMd: "<!-- WeChat Agent heartbeat tasks are disabled by default. -->\n",
  userMd: [
    "# USER.md - WeChat User",
    "",
    "Do not store cross-user facts here. Use the current sender's OpenViking memory.",
    "",
  ].join("\n"),
};

export function validateWeixinUserAgentId(agentId: string): string {
  const normalized = agentId.trim();
  if (!/^user_[0-9a-f]{32}$/.test(normalized)) {
    throw new Error("agentId must match user_<32 lowercase hex>");
  }
  return normalized;
}

export async function ensureWeixinDynamicAgentBinding(params: {
  cfg: OpenClawConfig | ConfigRecord;
  configRuntime?: WeixinConfigRuntime;
  accountId: string;
  peerId: string;
  agentId: string;
  homeDir?: string;
}): Promise<ConfigRecord> {
  const current = params.configRuntime?.current?.() ?? params.cfg as ConfigRecord;
  const agentId = validateWeixinUserAgentId(params.agentId);
  const homeDir = params.homeDir ?? process.env.OPENCLAW_HOME?.trim() ?? os.homedir();
  const workspace = path.join(homeDir, ".openclaw", `workspace-${agentId}`);
  const agentDir = path.join(homeDir, ".openclaw", "agents", agentId, "agent");

  const currentAgents = isRecord(current.agents) && Array.isArray(current.agents.list)
    ? current.agents.list
    : [];
  const currentAgent = currentAgents.find((entry: unknown) => isRecord(entry) && entry.id === agentId);
  const currentBindings = Array.isArray(current.bindings) ? current.bindings : [];
  if (isRecord(currentAgent) &&
      hasRequiredToolPolicy(currentAgent) &&
      currentBindings.some((entry: unknown) => isWeixinBinding(entry, agentId, params.accountId, params.peerId))) {
    await ensureWeixinAgentWorkspace(workspace, { homeDir });
    return current;
  }

  const run = mutationChain.catch(() => undefined).then(async () => {
    const runtime = params.configRuntime;
    if (!runtime?.mutateConfigFile) {
      await ensureWeixinAgentWorkspace(workspace, { homeDir });
      return current;
    }

    const mutation = await runtime.mutateConfigFile({
      base: "runtime",
      afterWrite: { mode: "auto" },
      mutate: async (draft) => {
        const agents = isRecord(draft.agents) ? draft.agents : {};
        const list = Array.isArray(agents.list) ? [...agents.list] : [];
        const existing = list.find((entry) => isRecord(entry) && entry.id === agentId);
        await ensureWeixinAgentWorkspace(workspace, { homeDir });
        await mkdir(agentDir, { recursive: true });

        const agent: ConfigRecord = isRecord(existing) ? { ...existing } : { id: agentId };
        agent.id = agentId;
        agent.workspace = workspace;
        agent.agentDir = agentDir;
        const tools = isRecord(agent.tools) ? { ...agent.tools } : {};
        const denied = Array.isArray(tools.deny)
          ? tools.deny.filter((value: unknown): value is string => typeof value === "string")
          : [];
        tools.deny = [...new Set([...denied, ...REQUIRED_DENIED_TOOLS])];
        agent.tools = tools;

        const nextList = existing
          ? list.map((entry) => (isRecord(entry) && entry.id === agentId ? agent : entry))
          : [...list, agent];
        draft.agents = { ...agents, list: nextList };

        const bindings = Array.isArray(draft.bindings) ? [...draft.bindings] : [];
        const bindingExists = bindings.some((entry) => isWeixinBinding(entry, agentId, params.accountId, params.peerId));
        if (!bindingExists) {
          bindings.push({
            agentId,
            match: {
              channel: WEIXIN_CHANNEL_ID,
              accountId: params.accountId,
              peer: { kind: "direct", id: params.peerId },
            },
          });
        }
        draft.bindings = bindings;
        return { agentId, created: !existing, bound: !bindingExists };
      },
    });
    return mutation.nextConfig ?? runtime.current?.() ?? current;
  });
  mutationChain = run.then(() => undefined, () => undefined);
  return run;
}

export async function ensureWeixinDynamicAgentRoute(params: WeixinDynamicAgentRouteParams): Promise<{
  cfg: ConfigRecord;
  route: ReturnType<PluginRuntime["channel"]["routing"]["resolveAgentRoute"]>;
}> {
  const cfg = await ensureWeixinDynamicAgentBinding(params);
  const route = params.channelRuntime.routing.resolveAgentRoute({
    cfg,
    channel: WEIXIN_CHANNEL_ID,
    accountId: params.accountId,
    peer: { kind: "direct", id: params.peerId },
  });
  if (route.agentId !== params.agentId) {
    throw new Error("WeChat route resolved unexpected agent; refusing agent:main fallback");
  }
  return { cfg, route };
}

export async function ensureWeixinAgentWorkspace(
  workspace: string,
  options: { homeDir?: string } = {},
): Promise<void> {
  await mkdir(workspace, { recursive: true });
  const preset = await loadWorkspacePreset(options.homeDir ?? process.env.OPENCLAW_HOME?.trim() ?? os.homedir());
  const files: Record<string, string> = {
    "AGENTS.md": preset.agentsMd,
    "SOUL.md": preset.soulMd,
    "IDENTITY.md": preset.identityMd,
    "TOOLS.md": preset.toolsMd,
    "HEARTBEAT.md": preset.heartbeatMd,
    "USER.md": preset.userMd,
  };
  await Promise.all(Object.entries(files).map(async ([name, content]) => {
    try {
      await writeFile(path.join(workspace, name), content, { encoding: "utf8", flag: "wx" });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }
  }));
}

export function resetWeixinDynamicAgentMutationChainForTest(): void {
  mutationChain = Promise.resolve();
}

async function loadWorkspacePreset(homeDir: string): Promise<typeof DEFAULT_WORKSPACE_PRESET> {
  const file = path.join(homeDir, ".openclaw", "claw-manager", "workspace-preset.json");
  try {
    const parsed = JSON.parse(await readFile(file, "utf8")) as Record<string, unknown>;
    if (isValidPreset(parsed)) {
      return {
        agentsMd: parsed.agentsMd,
        soulMd: parsed.soulMd,
        identityMd: parsed.identityMd,
        toolsMd: parsed.toolsMd,
        heartbeatMd: parsed.heartbeatMd,
        userMd: parsed.userMd,
      };
    }
  } catch {
    // Missing or invalid presets must not block the first message.
  }
  return DEFAULT_WORKSPACE_PRESET;
}

function isValidPreset(value: Record<string, unknown>): value is typeof DEFAULT_WORKSPACE_PRESET & { version: number } {
  const version = value.version;
  return typeof version === "number" && Number.isInteger(version) && version >= 0 &&
    ["agentsMd", "soulMd", "identityMd", "toolsMd", "heartbeatMd", "userMd"]
      .every((key) => typeof value[key] === "string" && String(value[key]).length > 0);
}

function isWeixinBinding(value: unknown, agentId: string, accountId: string, peerId: string): boolean {
  if (!isRecord(value) || value.agentId !== agentId || !isRecord(value.match)) return false;
  const match = value.match;
  return match.channel === WEIXIN_CHANNEL_ID && match.accountId === accountId &&
    isRecord(match.peer) && match.peer.kind === "direct" && match.peer.id === peerId;
}

function hasRequiredToolPolicy(agent: ConfigRecord): boolean {
  const tools = isRecord(agent.tools) ? agent.tools : undefined;
  const denied = Array.isArray(tools?.deny) ? tools.deny : [];
  return REQUIRED_DENIED_TOOLS.every((tool) => denied.includes(tool));
}

function isRecord(value: unknown): value is ConfigRecord {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
