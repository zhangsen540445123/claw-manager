import { buildChannelConfigSchema } from "openclaw/plugin-sdk/channel-config-schema";
import { weixinPlugin } from "./src/channel.js";
import { assertHostCompatibility } from "./src/compat.js";
import { WeixinConfigSchema } from "./src/config/config-schema.js";
import { setWeixinConfigRuntime } from "./src/config-runtime.js";
import { createHeartbeatMessageSendingHook } from "./src/messaging/heartbeat-isolation.js";
export default {
    id: "openclaw-weixin",
    name: "Weixin",
    description: "Weixin channel (getUpdates long-poll + sendMessage)",
    configSchema: buildChannelConfigSchema(WeixinConfigSchema),
    register(api) {
        // Fail-fast: reject incompatible host versions before any side-effects.
        assertHostCompatibility(api.runtime?.version);
        setWeixinConfigRuntime(api.runtime?.config);
        api.on("message_sending", createHeartbeatMessageSendingHook({
            channelId: "openclaw-weixin",
            log: (message) => api.logger.info(message),
        }));
        api.registerChannel({ plugin: weixinPlugin });
    },
};
//# sourceMappingURL=index.js.map