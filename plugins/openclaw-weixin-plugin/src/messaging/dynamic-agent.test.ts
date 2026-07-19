import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ensureWeixinAgentWorkspace,
  ensureWeixinDynamicAgentBinding,
  ensureWeixinDynamicAgentRoute,
  validateWeixinUserAgentId,
  resetWeixinDynamicAgentMutationChainForTest,
  type WeixinConfigRuntime,
} from "./dynamic-agent.js";

type MutableConfig = Record<string, any>;

function createConfigRuntime(initial: MutableConfig = {}): {
  runtime: WeixinConfigRuntime;
  current: () => MutableConfig;
  mutationCount: () => number;
} {
  let current = structuredClone(initial);
  let mutations = 0;
  return {
    current: () => current,
    mutationCount: () => mutations,
    runtime: {
      current: () => current,
      mutateConfigFile: async (params) => {
        mutations += 1;
        const draft = structuredClone(current);
        const result = await params.mutate(draft);
        current = draft;
        return { nextConfig: current, result };
      },
    },
  };
}

afterEach(() => {
  resetWeixinDynamicAgentMutationChainForTest();
});

describe("Weixin dynamic agent", () => {
  it("accepts only persisted random user agent ids", () => {
    expect(validateWeixinUserAgentId("user_abcdef0123456789abcdef0123456789"))
      .toBe("user_abcdef0123456789abcdef0123456789");
    expect(() => validateWeixinUserAgentId("wechat_abcdef0123456789abcdef0123456789"))
      .toThrow("agentId");
  });

  it("accepts a persisted Agent and account-peer binding without mutating config", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    try {
      const agentId = "user_abcdef0123456789abcdef0123456789";
      const persisted = {
        agents: { list: [{ id: agentId, tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] } }] },
        bindings: [{ agentId, match: { channel: "openclaw-weixin", accountId: "bot-a", peer: { kind: "direct", id: "user@im.wechat" } } }],
      };
      const mutateConfigFile = vi.fn(() => { throw new Error("must not mutate during first message"); });
      const cfg = await ensureWeixinDynamicAgentBinding({
        cfg: persisted,
        configRuntime: { current: () => persisted, mutateConfigFile },
        accountId: "bot-a",
        peerId: "user@im.wechat",
        agentId,
        homeDir,
      });

      expect(cfg).toBe(persisted);
      expect(mutateConfigFile).not.toHaveBeenCalled();
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("rejects a missing account binding without attempting chat-time repair", async () => {
    const agentId = "user_abcdef0123456789abcdef0123456789";
    const persisted = {
      agents: { list: [{ id: agentId, tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] } }] },
      bindings: [],
    };
    const mutateConfigFile = vi.fn();

    await expect(ensureWeixinDynamicAgentBinding({
      cfg: persisted,
      configRuntime: { current: () => persisted, mutateConfigFile },
      accountId: "bot-a",
      peerId: "user@im.wechat",
      agentId,
    })).rejects.toThrow("WECHAT_AGENT_NOT_READY");
    expect(mutateConfigFile).not.toHaveBeenCalled();
  });

  it("never overwrites templates that the user already customized", async () => {
    const workspace = await mkdtemp(path.join(os.tmpdir(), "weixin-workspace-"));
    try {
      await ensureWeixinAgentWorkspace(workspace);
      await writeFile(path.join(workspace, "SOUL.md"), "# My own soul\n", "utf8");

      await ensureWeixinAgentWorkspace(workspace);

      await expect(readFile(path.join(workspace, "SOUL.md"), "utf8"))
        .resolves.toBe("# My own soul\n");
    } finally {
      await rm(workspace, { recursive: true, force: true });
    }
  });

  it("initializes the six files from the Claw Manager workspace preset", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    const workspace = path.join(homeDir, ".openclaw", "workspace-wechat-preset");
    try {
      const presetDir = path.join(homeDir, ".openclaw", "claw-manager");
      await import("node:fs/promises").then(({ mkdir }) => mkdir(presetDir, { recursive: true }));
      await writeFile(path.join(presetDir, "workspace-preset.json"), JSON.stringify({
        agentsMd: "# Preset agents\n",
        soulMd: "# Preset soul\n",
        identityMd: "# Preset identity\n",
        toolsMd: "# Preset tools\n",
        heartbeatMd: "# Preset heartbeat\n",
        userMd: "# Preset user\n",
        version: 7,
      }), "utf8");

      await ensureWeixinAgentWorkspace(workspace, { homeDir });

      await expect(readFile(path.join(workspace, "AGENTS.md"), "utf8")).resolves.toBe("# Preset agents\n");
      await expect(readFile(path.join(workspace, "SOUL.md"), "utf8")).resolves.toBe("# Preset soul\n");
      await expect(readFile(path.join(workspace, "IDENTITY.md"), "utf8")).resolves.toBe("# Preset identity\n");
      await expect(readFile(path.join(workspace, "TOOLS.md"), "utf8")).resolves.toBe("# Preset tools\n");
      await expect(readFile(path.join(workspace, "HEARTBEAT.md"), "utf8")).resolves.toBe("# Preset heartbeat\n");
      await expect(readFile(path.join(workspace, "USER.md"), "utf8")).resolves.toBe("# Preset user\n");
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("accepts the built-in seed snapshot version zero", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    const workspace = path.join(homeDir, ".openclaw", "workspace-wechat-zero");
    try {
      const presetDir = path.join(homeDir, ".openclaw", "claw-manager");
      await import("node:fs/promises").then(({ mkdir }) => mkdir(presetDir, { recursive: true }));
      await writeFile(path.join(presetDir, "workspace-preset.json"), JSON.stringify({
        agentsMd: "# Seed agents\n",
        soulMd: "# Seed soul\n",
        identityMd: "# Seed identity\n",
        toolsMd: "# Seed tools\n",
        heartbeatMd: "# Seed heartbeat\n",
        userMd: "# Seed user\n",
        version: 0,
      }), "utf8");

      await ensureWeixinAgentWorkspace(workspace, { homeDir });

      await expect(readFile(path.join(workspace, "AGENTS.md"), "utf8")).resolves.toBe("# Seed agents\n");
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("falls back to safe defaults when the workspace preset is invalid", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    const workspace = path.join(homeDir, ".openclaw", "workspace-wechat-default");
    try {
      const presetDir = path.join(homeDir, ".openclaw", "claw-manager");
      await import("node:fs/promises").then(({ mkdir }) => mkdir(presetDir, { recursive: true }));
      await writeFile(path.join(presetDir, "workspace-preset.json"), JSON.stringify({
        agentsMd: "# Unsafe partial preset\n",
        version: 7,
      }), "utf8");

      await ensureWeixinAgentWorkspace(workspace, { homeDir });

      const agents = await readFile(path.join(workspace, "AGENTS.md"), "utf8");
      expect(agents).toContain("Claw Manager WeChat Agent");
      expect(agents).not.toContain("Unsafe partial preset");
      for (const file of ["USER.md", "SOUL.md", "TOOLS.md", "IDENTITY.md", "HEARTBEAT.md"]) {
        await expect(readFile(path.join(workspace, file), "utf8")).resolves.not.toBe("");
      }
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("resolves the route against the already-persisted config", async () => {
    const agentId = "user_abcdef0123456789abcdef0123456789";
    const persisted = {
      agents: { list: [{ id: agentId, tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] } }] },
      bindings: [{ agentId, match: { channel: "openclaw-weixin", accountId: "bot-a", peer: { kind: "direct", id: "user@im.wechat" } } }],
    };
      const resolveAgentRoute = vi.fn(({ cfg }) => {
        const binding = cfg.bindings?.[0];
        return binding
          ? { agentId: binding.agentId, sessionKey: `agent:${binding.agentId}:wechat`, mainSessionKey: "agent:main:main" }
          : { agentId: "main", sessionKey: "agent:main:wechat", mainSessionKey: "agent:main:main", matchedBy: "default" };
      });

      const result = await ensureWeixinDynamicAgentRoute({
        cfg: persisted,
        channelRuntime: { routing: { resolveAgentRoute } } as any,
        accountId: "bot-a",
        peerId: "user@im.wechat",
        agentId,
      });

      expect(result.route.agentId).toBe(agentId);
      expect(resolveAgentRoute).toHaveBeenLastCalledWith(expect.objectContaining({ cfg: result.cfg }));
  });

  it("fails closed instead of routing a user to agent:main", async () => {
    await expect(ensureWeixinDynamicAgentRoute({
      cfg: {},
      channelRuntime: {
        routing: {
          resolveAgentRoute: vi.fn(() => ({
            agentId: "main",
            sessionKey: "agent:main:openclaw-weixin:bot-a:direct:peer-a",
            mainSessionKey: "agent:main:main",
            matchedBy: "default",
          })),
        },
      } as any,
      accountId: "bot-a",
      peerId: "peer-a",
      agentId: "user_abcdef0123456789abcdef0123456789",
    })).rejects.toThrow("WECHAT_AGENT_NOT_READY");
  });
});
