import { mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
const REQUIRED_DENIED_TOOLS = ["write", "edit", "apply_patch", "exec", "process"];
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
export function resolveWeixinDynamicAgentId(senderHash) {
    const normalized = senderHash.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "");
    if (!normalized)
        throw new Error("senderHash is required");
    return `wechat_${normalized}`;
}
export async function ensureWeixinDynamicAgentBinding(params) {
    const current = params.configRuntime?.current?.() ?? params.cfg;
    const agentId = resolveWeixinDynamicAgentId(params.senderHash);
    const homeDir = params.homeDir ?? process.env.OPENCLAW_HOME?.trim() ?? os.homedir();
    const workspace = path.join(homeDir, ".openclaw", `workspace-wechat-${agentId.slice("wechat_".length)}`);
    const agentDir = path.join(homeDir, ".openclaw", "agents", agentId, "agent");
    const currentAgents = isRecord(current.agents) && Array.isArray(current.agents.list)
        ? current.agents.list
        : [];
    const currentAgent = currentAgents.find((entry) => isRecord(entry) && entry.id === agentId);
    const currentBindings = Array.isArray(current.bindings) ? current.bindings : [];
    if (isRecord(currentAgent) &&
        hasRequiredToolPolicy(currentAgent) &&
        currentBindings.some((entry) => isWeixinBinding(entry, agentId, params.accountId, params.peerId))) {
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
                const agent = isRecord(existing) ? { ...existing } : { id: agentId };
                agent.id = agentId;
                agent.workspace = workspace;
                agent.agentDir = agentDir;
                const tools = isRecord(agent.tools) ? { ...agent.tools } : {};
                const denied = Array.isArray(tools.deny)
                    ? tools.deny.filter((value) => typeof value === "string")
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
export async function ensureWeixinDynamicAgentRoute(params) {
    const cfg = await ensureWeixinDynamicAgentBinding(params);
    const route = params.channelRuntime.routing.resolveAgentRoute({
        cfg,
        channel: WEIXIN_CHANNEL_ID,
        accountId: params.accountId,
        peer: { kind: "direct", id: params.peerId },
    });
    return { cfg, route };
}
export async function ensureWeixinAgentWorkspace(workspace, options = {}) {
    await mkdir(workspace, { recursive: true });
    const preset = await loadWorkspacePreset(options.homeDir ?? process.env.OPENCLAW_HOME?.trim() ?? os.homedir());
    const files = {
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
        }
        catch (error) {
            if (error.code !== "EEXIST")
                throw error;
        }
    }));
}
export function resetWeixinDynamicAgentMutationChainForTest() {
    mutationChain = Promise.resolve();
}
async function loadWorkspacePreset(homeDir) {
    const file = path.join(homeDir, ".openclaw", "claw-manager", "workspace-preset.json");
    try {
        const parsed = JSON.parse(await readFile(file, "utf8"));
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
    }
    catch {
        // Missing or invalid presets must not block the first message.
    }
    return DEFAULT_WORKSPACE_PRESET;
}
function isValidPreset(value) {
    const version = value.version;
    return typeof version === "number" && Number.isInteger(version) && version > 0 &&
        ["agentsMd", "soulMd", "identityMd", "toolsMd", "heartbeatMd", "userMd"]
            .every((key) => typeof value[key] === "string" && String(value[key]).length > 0);
}
function isWeixinBinding(value, agentId, accountId, peerId) {
    if (!isRecord(value) || value.agentId !== agentId || !isRecord(value.match))
        return false;
    const match = value.match;
    return match.channel === WEIXIN_CHANNEL_ID && match.accountId === accountId &&
        isRecord(match.peer) && match.peer.kind === "direct" && match.peer.id === peerId;
}
function hasRequiredToolPolicy(agent) {
    const tools = isRecord(agent.tools) ? agent.tools : undefined;
    const denied = Array.isArray(tools?.deny) ? tools.deny : [];
    return REQUIRED_DENIED_TOOLS.every((tool) => denied.includes(tool));
}
function isRecord(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
//# sourceMappingURL=dynamic-agent.js.map