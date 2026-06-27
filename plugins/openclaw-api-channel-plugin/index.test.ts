import { describe, expect, it } from "vitest";

import plugin from "./index.js";

describe("claw-manager-api plugin entry", () => {
  it("exposes a top-level channel config schema like other OpenClaw channel plugins", () => {
    expect(plugin).toHaveProperty("configSchema");
  });

  it("registers the channel and logs the host registration mode", () => {
    const registeredChannels: unknown[] = [];
    const logs: string[] = [];
    const api = {
      registrationMode: "full",
      logger: { info: (message: string) => logs.push(message) },
      runtime: { channel: {} },
      registerChannel: (registration: unknown) => registeredChannels.push(registration),
      registerGatewayMethod: () => {},
    };

    plugin.register(api as never);

    expect(registeredChannels).toHaveLength(1);
    expect(logs[0]).toContain("claw-manager-api: register channel mode=full");
    expect(logs[0]).toContain("hasGatewayMethod=true");
  });
});
