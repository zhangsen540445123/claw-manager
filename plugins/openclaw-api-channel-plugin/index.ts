import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
import { buildJsonChannelConfigSchema } from "openclaw/plugin-sdk/channel-config-schema";

import { apiChannelPlugin, ensureApiUserAgentBinding, handleApiAssistantAgentEvent, monitorApiQueue } from "./src/channel.js";
import type { ApiAssistantAgentEvent, ApiGatewayStartContext } from "./src/channel.js";

type ApiLogSink = {
  info?: (message: string) => void;
  warn?: (message: string) => void;
};

type InternalAgentEventModule = {
  onAgentEvent?: (listener: (event: unknown) => void) => (() => void) | void;
  m?: (listener: (event: unknown) => void) => (() => void) | void;
};

type InternalAgentEventImporter = (specifier: string) => Promise<InternalAgentEventModule>;
type GatewayMethodApi = OpenClawPluginApi & {
  registerGatewayMethod?: (
    name: string,
    handler: (input: {
      params?: unknown;
      respond: (success: boolean, data: unknown) => void;
    }) => void | Promise<void>,
  ) => void;
};
type ApiQueueMonitorStarter = (ctx: ApiGatewayStartContext) => Promise<void>;

let internalAgentEventBridgeStop: (() => void) | undefined;
let apiQueueMonitorAbort: AbortController | undefined;
let apiQueueMonitorPromise: Promise<void> | undefined;
let apiQueueMonitorStarter: ApiQueueMonitorStarter = monitorApiQueue;
const requireFromHere = createRequire(import.meta.url);
export const API_CHANNEL_START_RPC = "claw-manager-api.start";
export const API_ENSURE_USER_AGENT_RPC = "claw-manager-api.ensure-user-agent";
export const API_ENSURE_API_BINDING_RPC = "claw-manager-api.ensure-api-binding";

export function resetOpenClawInternalAgentEventBridgeForTest(): void {
  internalAgentEventBridgeStop?.();
  internalAgentEventBridgeStop = undefined;
}

export function resetApiGatewayStartForTest(starter: ApiQueueMonitorStarter = monitorApiQueue): void {
  apiQueueMonitorAbort?.abort();
  apiQueueMonitorAbort = undefined;
  apiQueueMonitorPromise = undefined;
  apiQueueMonitorStarter = starter;
}

export function registerApiGatewayStartMethod(api: GatewayMethodApi): void {
  if (typeof api.registerGatewayMethod !== "function") {
    return;
  }
  api.registerGatewayMethod(API_CHANNEL_START_RPC, async ({ respond }) => {
    try {
      const result = startApiQueueMonitor(api);
      respond(true, result);
    } catch (error) {
      respond(false, errorMessage(error));
    }
  });
}

export function registerApiProvisioningMethods(api: GatewayMethodApi): void {
  if (typeof api.registerGatewayMethod !== "function") return;
  const configRuntime = api.runtime?.config as unknown as ApiGatewayStartContext["configRuntime"];
  api.registerGatewayMethod(API_ENSURE_USER_AGENT_RPC, async ({ params, respond }) => {
    await handleProvisioningRpc({ api, configRuntime, params, respond, kind: "wechat" });
  });
  api.registerGatewayMethod(API_ENSURE_API_BINDING_RPC, async ({ params, respond }) => {
    await handleProvisioningRpc({ api, configRuntime, params, respond, kind: "api" });
  });
}

async function handleProvisioningRpc(input: {
  api: GatewayMethodApi;
  configRuntime?: ApiGatewayStartContext["configRuntime"];
  params?: unknown;
  respond: (success: boolean, data: unknown) => void;
  kind: "wechat" | "api";
}): Promise<void> {
  if (!input.configRuntime?.current || !input.configRuntime?.mutateConfigFile) {
    input.respond(false, { code: "CONFIG_RUNTIME_UNAVAILABLE", message: "OpenClaw config runtime is unavailable" });
    return;
  }
  const params = input.params && typeof input.params === "object"
    ? input.params as Record<string, unknown>
    : {};
  try {
    const agentId = String(params.agentId ?? "").trim();
    const openVikingUserId = String(params.openVikingUserId ?? "").trim();
    const before = input.configRuntime.current();
    const beforeAgents = Array.isArray((before.agents as any)?.list) ? (before.agents as any).list : [];
    const beforeBindings = Array.isArray(before.bindings) ? before.bindings : [];
    const expectedBinding = input.kind === "wechat"
      ? requestedBinding("openclaw-weixin", String(params.wechatAccountId ?? "").trim(), String(params.wechatPeerId ?? "").trim())
      : requestedBinding("claw-manager-api", "global", String(params.apiPeerId ?? "").trim());
    const agentExisted = beforeAgents.some((entry: any) => entry?.id === agentId);
    const bindingExisted = beforeBindings.some((entry: unknown) => matchesBinding(entry, agentId, expectedBinding));
    const cfg = await ensureApiUserAgentBinding({
      cfg: before,
      configRuntime: input.configRuntime,
      agentId,
      openVikingUserId,
      ...(input.kind === "wechat" ? {
        wechatAccountId: String(params.wechatAccountId ?? "").trim(),
        wechatPeerId: String(params.wechatPeerId ?? "").trim(),
      } : {
        apiPeerId: String(params.apiPeerId ?? "").trim(),
      }),
      log: input.api.logger,
    });
    const current = input.configRuntime.current();
    const currentAgents = Array.isArray((current.agents as any)?.list) ? (current.agents as any).list : [];
    const currentBindings = Array.isArray(current.bindings) ? current.bindings : [];
    const currentAgent = currentAgents.find((entry: any) => entry?.id === agentId);
    if (!isProvisionedAgent(currentAgent, agentId) ||
        !currentBindings.some((entry: unknown) => matchesBinding(entry, agentId, expectedBinding))) {
      throw new Error("CONFIG_RUNTIME_NOT_APPLIED");
    }
    input.respond(true, {
      agentId,
      created: !agentExisted,
      ...(input.kind === "wechat" ? { wechatBindingCreated: !bindingExisted } : { apiBindingCreated: !bindingExisted }),
      persisted: cfg === current || isProvisionedAgent(currentAgent, agentId),
      runtimeApplied: true,
    });
  } catch (error) {
    input.respond(false, { code: errorMessage(error), message: errorMessage(error) });
  }
}

type ProvisioningBinding = {
  channel: string;
  accountId: string;
  peer: { kind: "direct"; id: string };
};

function requestedBinding(channel: string, accountId: string, peerId: string): ProvisioningBinding {
  return { channel, accountId, peer: { kind: "direct", id: peerId } };
}

function matchesBinding(value: unknown, agentId: string, expected: ProvisioningBinding): boolean {
  if (!value || typeof value !== "object") return false;
  const binding = value as Record<string, any>;
  const match = binding.match;
  return binding.agentId === agentId && match?.channel === expected.channel &&
    match?.accountId === expected.accountId && match?.peer?.kind === "direct" &&
    match.peer.id === expected.peer.id;
}

function isProvisionedAgent(value: unknown, agentId: string): boolean {
  if (!value || typeof value !== "object") return false;
  const agent = value as Record<string, any>;
  const denied = Array.isArray(agent.tools?.deny) ? agent.tools.deny : [];
  const home = String(process.env.OPENCLAW_HOME ?? "").trim() || os.homedir();
  const expectedWorkspace = path.join(home, ".openclaw", `workspace-${agentId}`);
  const expectedAgentDir = path.join(home, ".openclaw", "agents", agentId, "agent");
  return agent.id === agentId && agent.workspace === expectedWorkspace &&
    agent.agentDir === expectedAgentDir &&
    ["write", "edit", "apply_patch", "exec", "process"].every((tool) => denied.includes(tool));
}

function startApiQueueMonitor(api: OpenClawPluginApi): { started: true; alreadyRunning: boolean } {
  if (apiQueueMonitorPromise && !apiQueueMonitorAbort?.signal.aborted) {
    return { started: true, alreadyRunning: true };
  }
  const cfg = api.config;
  const channelRuntime = api.runtime?.channel;
  if (!cfg) {
    throw new Error("api.config missing");
  }
  if (!channelRuntime) {
    throw new Error("api.runtime.channel missing");
  }
  const abort = new AbortController();
  apiQueueMonitorAbort = abort;
  const monitorRun = apiQueueMonitorStarter({
    cfg,
    channelRuntime,
    configRuntime: api.runtime?.config as unknown as ApiGatewayStartContext["configRuntime"],
    abortSignal: abort.signal,
    log: api.logger,
  });
  const running = Promise.resolve(monitorRun)
    .catch((error) => {
      api.logger?.error?.(`claw-manager-api: queue monitor failed: ${errorMessage(error)}`);
    })
    .finally(() => {
      if (apiQueueMonitorPromise === running) {
        apiQueueMonitorPromise = undefined;
        apiQueueMonitorAbort = undefined;
      }
    });
  apiQueueMonitorPromise = running;
  return { started: true, alreadyRunning: false };
}

export async function installOpenClawInternalAgentEventBridge(
  api: { logger?: ApiLogSink },
  importer: InternalAgentEventImporter = (specifier) => import(specifier) as Promise<InternalAgentEventModule>,
  candidateUrls = resolveOpenClawAgentEventModuleUrls(api),
): Promise<boolean> {
  if (internalAgentEventBridgeStop) {
    return true;
  }
  let lastError = "";
  for (const agentEventsUrl of candidateUrls) {
    try {
      const mod = await importer(agentEventsUrl);
      const onAgentEvent = typeof mod.onAgentEvent === "function"
        ? mod.onAgentEvent
        : (typeof mod.m === "function" ? mod.m : undefined);
      if (!onAgentEvent) {
        lastError = `onAgentEvent missing in ${agentEventsUrl}`;
        continue;
      }
      const stop = onAgentEvent((event) => {
        void handleApiAssistantAgentEvent(event as ApiAssistantAgentEvent).catch((error) => {
          api.logger?.warn?.(`claw-manager-api: internal agent-event handling failed: ${errorMessage(error)}`);
        });
      });
      internalAgentEventBridgeStop = typeof stop === "function" ? stop : () => undefined;
      api.logger?.info?.(`claw-manager-api: internal agent-event bridge registered (${agentEventsUrl})`);
      return true;
    } catch (error) {
      lastError = errorMessage(error);
    }
  }
  api.logger?.warn?.(
    `claw-manager-api: internal agent-event bridge unavailable: ${lastError || "no candidate modules"}`,
  );
  return false;
}

function resolveOpenClawAgentEventModuleUrls(api: { logger?: ApiLogSink }): string[] {
  let coreUrl: string;
  try {
    coreUrl = pathToFileURL(requireFromHere.resolve("openclaw/plugin-sdk/core")).href;
  } catch (error) {
    api.logger?.warn?.(`claw-manager-api: internal agent-event bridge unavailable: ${errorMessage(error)}`);
    return [];
  }
  const urls = [new URL("../infra/agent-events.js", coreUrl).href];
  try {
    const distDir = path.resolve(path.dirname(fileURLToPath(coreUrl)), "..");
    for (const file of fs.readdirSync(distDir)) {
      if (/^agent-events-[A-Za-z0-9_-]+\.js$/.test(file)) {
        urls.push(pathToFileURL(path.join(distDir, file)).href);
      }
    }
  } catch {
    // Older OpenClaw layouts do not include bundled root-level chunks.
  }
  return [...new Set(urls)];
}

const pluginEntry = {
  id: "claw-manager-api",
  name: "Claw Manager API",
  description: "External API channel for Claw Manager",
  configSchema: buildJsonChannelConfigSchema({
    "$schema": "http://json-schema.org/draft-07/schema#",
    type: "object",
    properties: {
      enabled: { type: "boolean" },
    },
    additionalProperties: false,
  }),
  register(api: OpenClawPluginApi) {
    api.registerChannel({ plugin: apiChannelPlugin });
    void installOpenClawInternalAgentEventBridge(api);
    registerApiGatewayStartMethod(api as GatewayMethodApi);
    registerApiProvisioningMethods(api as GatewayMethodApi);
    api.logger?.info?.(
      `claw-manager-api: register channel mode=${String(api.registrationMode ?? "unknown")} ` +
      `hasGatewayMethod=${String(typeof api.registerGatewayMethod === "function")}`,
    );
  },
};

export default pluginEntry;

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return String(error);
}
