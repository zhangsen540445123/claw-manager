import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ensureWeixinAgentWorkspace,
  ensureWeixinDynamicAgentBinding,
  ensureWeixinDynamicAgentRoute,
  resolveWeixinDynamicAgentId,
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
  it("uses the OpenViking sender hash as a stable agent id", () => {
    expect(resolveWeixinDynamicAgentId("ABCDEF0123456789abcdef0123456789"))
      .toBe("wechat_abcdef0123456789abcdef0123456789");
  });

  it("creates one agent, its six templates, tool policy, and an account-peer binding", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    try {
      const state = createConfigRuntime();
      const cfg = await ensureWeixinDynamicAgentBinding({
        cfg: {},
        configRuntime: state.runtime,
        accountId: "bot-a",
        peerId: "user@im.wechat",
        senderHash: "abcdef0123456789abcdef0123456789",
        homeDir,
      });

      const agentId = "wechat_abcdef0123456789abcdef0123456789";
      const workspace = path.join(homeDir, ".openclaw", "workspace-wechat-abcdef0123456789abcdef0123456789");
      expect(cfg.agents.list).toEqual([expect.objectContaining({
        id: agentId,
        workspace: path.join(homeDir, ".openclaw", "workspace-wechat-abcdef0123456789abcdef0123456789"),
        agentDir: path.join(homeDir, ".openclaw", "agents", agentId, "agent"),
        tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] },
      })]);
      expect(cfg.bindings).toEqual([{
        agentId,
        match: {
          channel: "openclaw-weixin",
          accountId: "bot-a",
          peer: { kind: "direct", id: "user@im.wechat" },
        },
      }]);
      for (const file of ["AGENTS.md", "USER.md", "SOUL.md", "TOOLS.md", "IDENTITY.md", "HEARTBEAT.md"]) {
        await expect(readFile(path.join(workspace, file), "utf8")).resolves.not.toBe("");
      }
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("reuses one agent across accounts while adding a binding for each account and peer", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    try {
      const state = createConfigRuntime();
      const base = { cfg: {}, configRuntime: state.runtime, peerId: "user@im.wechat",
        senderHash: "abcdef0123456789abcdef0123456789", homeDir };

      await ensureWeixinDynamicAgentBinding({ ...base, accountId: "bot-a" });
      await ensureWeixinDynamicAgentBinding({ ...base, accountId: "bot-a" });
      const cfg = await ensureWeixinDynamicAgentBinding({ ...base, accountId: "bot-b" });

      expect(cfg.agents.list).toHaveLength(1);
      expect(cfg.bindings).toHaveLength(2);
      expect(cfg.bindings.map((entry: any) => entry.match.accountId)).toEqual(["bot-a", "bot-b"]);
      expect(state.mutationCount()).toBe(2);
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
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

  it("serializes concurrent config mutations so no agent or binding is lost", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    try {
      let current: MutableConfig = {};
      let activeMutations = 0;
      let maxActiveMutations = 0;
      const runtime: WeixinConfigRuntime = {
        current: () => current,
        mutateConfigFile: async (params) => {
          activeMutations += 1;
          maxActiveMutations = Math.max(maxActiveMutations, activeMutations);
          const draft = structuredClone(current);
          await new Promise((resolve) => setTimeout(resolve, 20));
          const result = await params.mutate(draft);
          current = draft;
          activeMutations -= 1;
          return { nextConfig: current, result };
        },
      };

      await Promise.all([
        ensureWeixinDynamicAgentBinding({ cfg: {}, configRuntime: runtime, accountId: "bot-a",
          peerId: "user-a@im.wechat", senderHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", homeDir }),
        ensureWeixinDynamicAgentBinding({ cfg: {}, configRuntime: runtime, accountId: "bot-b",
          peerId: "user-b@im.wechat", senderHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", homeDir }),
      ]);

      expect(maxActiveMutations).toBe(1);
      expect(current.agents.list).toHaveLength(2);
      expect(current.bindings).toHaveLength(2);
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });

  it("re-resolves the route against the config returned by mutateConfigFile", async () => {
    const homeDir = await mkdtemp(path.join(os.tmpdir(), "weixin-agent-"));
    try {
      const state = createConfigRuntime();
      const resolveAgentRoute = vi.fn(({ cfg }) => {
        const binding = cfg.bindings?.[0];
        return binding
          ? { agentId: binding.agentId, sessionKey: `agent:${binding.agentId}:wechat`, mainSessionKey: "agent:main:main" }
          : { agentId: "main", sessionKey: "agent:main:wechat", mainSessionKey: "agent:main:main", matchedBy: "default" };
      });

      const result = await ensureWeixinDynamicAgentRoute({
        cfg: {},
        configRuntime: state.runtime,
        channelRuntime: { routing: { resolveAgentRoute } } as any,
        accountId: "bot-a",
        peerId: "user@im.wechat",
        senderHash: "abcdef0123456789abcdef0123456789",
        homeDir,
      });

      expect(result.route.agentId).toBe("wechat_abcdef0123456789abcdef0123456789");
      expect(resolveAgentRoute).toHaveBeenLastCalledWith(expect.objectContaining({ cfg: result.cfg }));
    } finally {
      await rm(homeDir, { recursive: true, force: true });
    }
  });
});
