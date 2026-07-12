import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
type ToolContext = {
    requesterSenderId?: string;
};
type BridgeInput = {
    actionKey: string;
    parameters?: Record<string, unknown>;
};
export declare function callMiniappBridge(input: BridgeInput, ctx: ToolContext, env?: NodeJS.ProcessEnv, fetcher?: typeof fetch): Promise<unknown>;
declare const plugin: {
    id: string;
    name: string;
    description: string;
    register(api: OpenClawPluginApi): void;
};
export default plugin;
