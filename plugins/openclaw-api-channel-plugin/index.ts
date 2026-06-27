import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
import { defineChannelPluginEntry } from "openclaw/plugin-sdk/core";
import { buildJsonChannelConfigSchema } from "openclaw/plugin-sdk/channel-config-schema";

import { apiChannelPlugin } from "./src/channel.js";

export default defineChannelPluginEntry({
  id: "claw-manager-api",
  name: "Claw Manager API",
  description: "External API channel for Claw Manager",
  plugin: apiChannelPlugin,
  configSchema: buildJsonChannelConfigSchema({
    "$schema": "http://json-schema.org/draft-07/schema#",
    type: "object",
    properties: {
      enabled: { type: "boolean" },
    },
    additionalProperties: false,
  }),
  registerFull(api: OpenClawPluginApi) {
    api.logger?.info?.(
      `claw-manager-api: register channel mode=${String(api.registrationMode ?? "unknown")} ` +
        `hasGatewayMethod=${String(typeof api.registerGatewayMethod === "function")}`,
    );
  },
});
