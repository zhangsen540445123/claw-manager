import { beforeEach, describe, expect, it } from "vitest";

import plugin, {
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
    expect(subscriptions).toHaveLength(0);
    expect(logs.some((line) => line.includes("claw-manager-api: register channel mode=full"))).toBe(true);
    expect(logs.some((line) => line.includes("hasGatewayMethod=true"))).toBe(true);
  });

  it("starts the resident queue monitor when the channel plugin is fully registered", async () => {
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
    expect(starts).toHaveLength(1);

    const responses: unknown[] = [];

    handler?.({ respond: (success, data) => responses.push({ success, data }) });
    handler?.({ respond: (success, data) => responses.push({ success, data }) });

    expect(starts).toHaveLength(1);
    expect(responses).toEqual([
      { success: true, data: { started: true, alreadyRunning: true } },
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
