import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import plugin, {
  API_ENSURE_API_BINDING_RPC,
  API_ENSURE_USER_AGENT_RPC,
  API_DELETE_USER_AGENT_RPC,
  API_CHANNEL_START_RPC,
  installOpenClawInternalAgentEventBridge,
  resetApiGatewayStartForTest,
  resetOpenClawInternalAgentEventBridgeForTest,
} from "./index.js";

describe("claw-manager-api plugin entry", () => {
  beforeEach(() => {
    resetOpenClawInternalAgentEventBridgeForTest();
    resetApiGatewayStartForTest();
  });

  it("exposes a top-level channel config schema like other OpenClaw channel plugins", () => {
    expect(plugin).toHaveProperty("configSchema");
  });

  it("registers the channel and logs the host registration mode", () => {
    const registeredChannels: unknown[] = [];
    const gatewayMethods: Array<{ name: string; handler: unknown }> = [];
    const subscriptions: unknown[] = [];
    const logs: string[] = [];
    const api = {
      registrationMode: "full",
      logger: { info: (message: string) => logs.push(message) },
      config: { session: {} },
      agent: {
        events: {
          registerAgentEventSubscription: (subscription: unknown) => subscriptions.push(subscription),
        },
      },
      runtime: { channel: {} },
      registerChannel: (registration: unknown) => registeredChannels.push(registration),
      registerGatewayMethod: (name: string, handler: unknown) => gatewayMethods.push({ name, handler }),
    };

    plugin.register(api as never);

    expect(registeredChannels).toHaveLength(1);
    expect(gatewayMethods.map((method) => method.name)).toContain(API_CHANNEL_START_RPC);
    expect(gatewayMethods.map((method) => method.name)).toContain(API_ENSURE_USER_AGENT_RPC);
    expect(gatewayMethods.map((method) => method.name)).toContain(API_ENSURE_API_BINDING_RPC);
    expect(gatewayMethods.map((method) => method.name)).toContain(API_DELETE_USER_AGENT_RPC);
    expect(subscriptions).toHaveLength(0);
    expect(logs.some((line) => line.includes("claw-manager-api: register channel mode=full"))).toBe(true);
    expect(logs.some((line) => line.includes("hasGatewayMethod=true"))).toBe(true);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("fails strict provisioning when the host config runtime is unavailable", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: { agents: { list: [] }, bindings: [] },
      runtime: { channel: {} },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];
    await gatewayMethods.get(API_ENSURE_USER_AGENT_RPC)?.({
      params: {
        agentId: "user_0123456789abcdef0123456789abcdef",
        openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
        wechatAccountId: "bot-a",
        wechatPeerId: "peer-a",
      },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    expect(responses).toEqual([{ success: false, data: expect.objectContaining({ code: "CONFIG_RUNTIME_UNAVAILABLE" }) }]);
  });

  it("persists and verifies user Agent and API bindings through strict gateway RPCs", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    let currentCfg: any = { agents: { list: [] }, bindings: [] };
    const mutateConfigFile = async ({ mutate }: any) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result, nextConfig: currentCfg };
    };
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg,
      runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];
    const agentId = "user_0123456789abcdef0123456789abcdef";
    await gatewayMethods.get(API_ENSURE_USER_AGENT_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", wechatAccountId: "bot-a", wechatPeerId: "peer-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    await gatewayMethods.get(API_ENSURE_API_BINDING_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", apiPeerId: "api:sender-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    await gatewayMethods.get(API_ENSURE_USER_AGENT_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", wechatAccountId: "bot-a", wechatPeerId: "peer-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    await gatewayMethods.get(API_ENSURE_API_BINDING_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", apiPeerId: "api:sender-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    expect(responses).toEqual([
      { success: true, data: expect.objectContaining({ agentId, persisted: true, runtimeApplied: true, wechatBindingCreated: true }) },
      { success: true, data: expect.objectContaining({ agentId, persisted: true, runtimeApplied: true, apiBindingCreated: true }) },
      { success: true, data: expect.objectContaining({ agentId, persisted: true, runtimeApplied: true, created: false, wechatBindingCreated: false }) },
      { success: true, data: expect.objectContaining({ agentId, persisted: true, runtimeApplied: true, created: false, apiBindingCreated: false }) },
    ]);
    expect(currentCfg.agents.list).toHaveLength(1);
    expect(currentCfg.bindings).toHaveLength(2);
  });

  it("deletes a user Agent through the strict gateway RPC", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const agentId = "user_0123456789abcdef0123456789abcdef";
    let currentCfg: any = {
      agents: { list: [{ id: agentId }] },
      bindings: [{ agentId, match: { channel: "openclaw-weixin", accountId: "bot-a", peer: { kind: "direct", id: "peer-a" } } }],
    };
    const mutateConfigFile = async ({ mutate }: any) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result, nextConfig: currentCfg };
    };
    const api = {
      registrationMode: "full", logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg, runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];
    await gatewayMethods.get(API_DELETE_USER_AGENT_RPC)?.({
      params: { agentId, wechatAccountIds: ["bot-a"], wechatPeerIds: ["peer-a"] },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });
    expect(responses).toEqual([{ success: true, data: expect.objectContaining({ persisted: true, agentRemoved: true }) }]);
    expect(currentCfg.agents.list).toEqual([]);
    expect(currentCfg.bindings).toEqual([]);
  });

  it("keeps the protected current Agent when deleting an old duplicate route through RPC", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const currentAgentId = "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const oldAgentId = "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const currentBinding = {
      agentId: currentAgentId,
      match: { channel: "openclaw-weixin", accountId: "current-account", peer: { kind: "direct", id: "wechat-peer" } },
    };
    const oldBinding = {
      agentId: oldAgentId,
      match: { channel: "openclaw-weixin", accountId: "old-account", peer: { kind: "direct", id: "wechat-peer" } },
    };
    let currentCfg: any = {
      agents: { list: [{ id: currentAgentId }, { id: oldAgentId }] },
      bindings: [currentBinding, oldBinding],
    };
    const mutateConfigFile = async ({ mutate }: any) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result, nextConfig: currentCfg };
    };
    const api = {
      registrationMode: "full", logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg, runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];

    await gatewayMethods.get(API_DELETE_USER_AGENT_RPC)?.({
      params: {
        agentId: oldAgentId,
        wechatAccountIds: ["old-account"],
        wechatPeerIds: ["wechat-peer"],
        protectedAgentIds: [currentAgentId],
      },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });

    expect(responses).toEqual([{ success: true, data: expect.objectContaining({ persisted: true, agentRemoved: true }) }]);
    expect(currentCfg.agents.list).toEqual([{ id: currentAgentId }]);
    expect(currentCfg.bindings).toEqual([currentBinding]);
  });

  it("verifies persisted Agent paths relative to OPENCLAW_HOME", async () => {
    vi.stubEnv("OPENCLAW_HOME", path.join(os.tmpdir(), "claw-manager-custom-home"));
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    let currentCfg: any = { agents: { list: [] }, bindings: [] };
    const mutateConfigFile = async ({ mutate }: any) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      return { result, nextConfig: currentCfg };
    };
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg,
      runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];

    await gatewayMethods.get(API_ENSURE_USER_AGENT_RPC)?.({
      params: {
        agentId: "user_0123456789abcdef0123456789abcdef",
        openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
        wechatAccountId: "bot-a",
        wechatPeerId: "peer-a",
      },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });

    expect(responses).toEqual([{ success: true, data: expect.objectContaining({ persisted: true, runtimeApplied: true }) }]);
  });

  it("rejects RPC success when the requested binding was not applied by runtime", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const agentId = "user_0123456789abcdef0123456789abcdef";
    const workspace = path.join(os.homedir(), ".openclaw", `workspace-${agentId}`);
    const currentCfg: any = {
      agents: { list: [{
        id: agentId,
        workspace,
        agentDir: path.join(os.homedir(), ".openclaw", "agents", agentId, "agent"),
        tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] },
      }] },
      bindings: [{ agentId, match: { channel: "other", accountId: "other", peer: { kind: "direct", id: "other" } } }],
    };
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg,
      runtime: {
        channel: {},
        config: {
          current: () => currentCfg,
          mutateConfigFile: async () => ({ result: {}, nextConfig: currentCfg }),
        },
      },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];

    await gatewayMethods.get(API_ENSURE_API_BINDING_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", apiPeerId: "api:sender-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });

    expect(responses).toEqual([
      { success: false, data: expect.objectContaining({ code: "CONFIG_RUNTIME_NOT_APPLIED" }) },
    ]);
  });

  it("does not let ensure-api-binding create or repair a missing Agent", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const currentCfg: any = { agents: { list: [] }, bindings: [] };
    let mutationCount = 0;
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg,
      runtime: {
        channel: {},
        config: {
          current: () => currentCfg,
          mutateConfigFile: async () => {
            mutationCount += 1;
            return { result: {}, nextConfig: currentCfg };
          },
        },
      },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];

    await gatewayMethods.get(API_ENSURE_API_BINDING_RPC)?.({
      params: {
        agentId: "user_0123456789abcdef0123456789abcdef",
        openVikingUserId: "wx_0123456789abcdef0123456789abcdef",
        apiPeerId: "api:sender-a",
      },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });

    expect(responses).toEqual([
      { success: false, data: expect.objectContaining({ code: "API_BINDING_NOT_READY" }) },
    ]);
    expect(mutationCount).toBe(0);
    expect(currentCfg).toEqual({ agents: { list: [] }, bindings: [] });
  });

  it("adds only the API binding without touching Agent workspace files", async () => {
    const customHome = fs.mkdtempSync(path.join(os.tmpdir(), "claw-manager-api-binding-"));
    vi.stubEnv("OPENCLAW_HOME", customHome);
    try {
      const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
      const agentId = "user_0123456789abcdef0123456789abcdef";
      const workspace = path.join(customHome, ".openclaw", `workspace-${agentId}`);
      const agentDir = path.join(customHome, ".openclaw", "agents", agentId, "agent");
      let currentCfg: any = {
        agents: { list: [{
          id: agentId,
          workspace,
          agentDir,
          tools: { deny: ["write", "edit", "apply_patch", "exec", "process"] },
        }] },
        bindings: [],
      };
      const mutateConfigFile = async ({ mutate }: any) => {
        const draft = structuredClone(currentCfg);
        const result = await mutate(draft);
        currentCfg = draft;
        return { result, nextConfig: currentCfg };
      };
      const api = {
        registrationMode: "full",
        logger: { info: () => {}, warn: () => {}, error: () => {} },
        config: currentCfg,
        runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
        registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
        registerChannel: () => {},
      };
      plugin.register(api as never);
      const responses: unknown[] = [];

      await gatewayMethods.get(API_ENSURE_API_BINDING_RPC)?.({
        params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", apiPeerId: "api:sender-a" },
        respond: (success: boolean, data: unknown) => responses.push({ success, data }),
      });

      expect(responses).toEqual([{ success: true, data: expect.objectContaining({ apiBindingCreated: true }) }]);
      expect(fs.existsSync(workspace)).toBe(false);
      expect(fs.existsSync(agentDir)).toBe(false);
    } finally {
      fs.rmSync(customHome, { recursive: true, force: true });
    }
  });

  it("rejects ensure-user-agent when current config points at another workspace", async () => {
    const gatewayMethods = new Map<string, (input: any) => Promise<void> | void>();
    const agentId = "user_0123456789abcdef0123456789abcdef";
    const denied = ["write", "edit", "apply_patch", "exec", "process"];
    let currentCfg: any = { agents: { list: [] }, bindings: [] };
    const mutateConfigFile = async ({ mutate }: any) => {
      const draft = structuredClone(currentCfg);
      const result = await mutate(draft);
      currentCfg = draft;
      currentCfg.agents.list[0] = {
        ...currentCfg.agents.list[0],
        workspace: path.join(os.homedir(), ".openclaw", "workspace-user_other"),
        agentDir: path.join(os.homedir(), ".openclaw", "agents", "user_other", "agent"),
        tools: { deny: denied },
      };
      return { result, nextConfig: currentCfg };
    };
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: currentCfg,
      runtime: { channel: {}, config: { current: () => currentCfg, mutateConfigFile } },
      registerGatewayMethod: (name: string, handler: (input: any) => Promise<void> | void) => gatewayMethods.set(name, handler),
      registerChannel: () => {},
    };
    plugin.register(api as never);
    const responses: unknown[] = [];

    await gatewayMethods.get(API_ENSURE_USER_AGENT_RPC)?.({
      params: { agentId, openVikingUserId: "wx_0123456789abcdef0123456789abcdef", wechatAccountId: "bot-a", wechatPeerId: "peer-a" },
      respond: (success: boolean, data: unknown) => responses.push({ success, data }),
    });

    expect(responses).toEqual([
      { success: false, data: expect.objectContaining({ code: "CONFIG_RUNTIME_NOT_APPLIED" }) },
    ]);
  });

  it("does not auto-start the resident queue monitor during plugin registration", async () => {
    const gatewayMethods = new Map<string, (input: { respond: (success: boolean, data: unknown) => void }) => void>();
    const starts: unknown[] = [];
    resetApiGatewayStartForTest((ctx) => {
      starts.push(ctx);
      return new Promise(() => undefined);
    });
    const api = {
      registrationMode: "full",
      logger: { info: () => {}, warn: () => {}, error: () => {} },
      config: { session: {} },
      runtime: { channel: {} },
      registerGatewayMethod: (name: string, handler: (input: { respond: (success: boolean, data: unknown) => void }) => void) => {
        gatewayMethods.set(name, handler);
      },
      registerChannel: () => {},
    };

    plugin.register(api as never);
    const handler = gatewayMethods.get(API_CHANNEL_START_RPC);
    expect(handler).toBeTypeOf("function");
    expect(starts).toHaveLength(0);

    const responses: unknown[] = [];

    handler?.({ respond: (success, data) => responses.push({ success, data }) });
    handler?.({ respond: (success, data) => responses.push({ success, data }) });

    expect(starts).toHaveLength(1);
    expect(responses).toEqual([
      { success: true, data: { started: true, alreadyRunning: false } },
      { success: true, data: { started: true, alreadyRunning: true } },
    ]);
  });

  it("installs an internal OpenClaw agent event bridge when the host exposes it", async () => {
    let registeredListener: ((event: unknown) => void) | undefined;
    let importedSpecifier = "";
    const logs: string[] = [];
    const api = {
      logger: {
        info: (message: string) => logs.push(message),
        warn: (message: string) => logs.push(message),
      },
    };

    const installed = await installOpenClawInternalAgentEventBridge(api as never, async (specifier) => {
      importedSpecifier = specifier;
      return {
        onAgentEvent: (listener: (event: unknown) => void) => {
          registeredListener = listener;
          return () => undefined;
        },
      };
    });

    expect(installed, logs.join("\n")).toBe(true);
    expect(importedSpecifier).toMatch(/agent-events\.js$/);
    expect(registeredListener).toBeTypeOf("function");
    expect(logs.some((line) => line.includes("internal agent-event bridge registered"))).toBe(true);
  });

  it("accepts the bundled OpenClaw minified onAgentEvent export", async () => {
    let registeredListener: ((event: unknown) => void) | undefined;
    const logs: string[] = [];
    const api = {
      logger: {
        info: (message: string) => logs.push(message),
        warn: (message: string) => logs.push(message),
      },
    };

    const installed = await installOpenClawInternalAgentEventBridge(api as never, async () => ({
      m: (listener: (event: unknown) => void) => {
        registeredListener = listener;
        return () => undefined;
      },
    }));

    expect(installed, logs.join("\n")).toBe(true);
    expect(registeredListener).toBeTypeOf("function");
  });

  it("tries bundled agent-event module candidates after the canonical module is unavailable", async () => {
    let registeredListener: ((event: unknown) => void) | undefined;
    const attempts: string[] = [];
    const logs: string[] = [];
    const api = {
      logger: {
        info: (message: string) => logs.push(message),
        warn: (message: string) => logs.push(message),
      },
    };

    const installed = await installOpenClawInternalAgentEventBridge(
      api as never,
      async (specifier) => {
        attempts.push(specifier);
        if (specifier.endsWith("/infra/agent-events.js")) {
          throw new Error("missing canonical module");
        }
        return {
          m: (listener: (event: unknown) => void) => {
            registeredListener = listener;
            return () => undefined;
          },
        };
      },
      [
        "file:///openclaw/dist/infra/agent-events.js",
        "file:///openclaw/dist/agent-events-C3fRM85v.js",
      ],
    );

    expect(installed, logs.join("\n")).toBe(true);
    expect(attempts).toEqual([
      "file:///openclaw/dist/infra/agent-events.js",
      "file:///openclaw/dist/agent-events-C3fRM85v.js",
    ]);
    expect(registeredListener).toBeTypeOf("function");
  });
});
