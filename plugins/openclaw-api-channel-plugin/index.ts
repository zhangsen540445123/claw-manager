import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
import { buildJsonChannelConfigSchema } from "openclaw/plugin-sdk/channel-config-schema";

import { apiChannelPlugin, handleApiAssistantAgentEvent, monitorApiQueue } from "./src/channel.js";
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
