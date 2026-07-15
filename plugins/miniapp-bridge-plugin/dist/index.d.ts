import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
type ToolContext = {
    requesterSenderId?: string;
};
type DomainInput = {
    operation: string;
    [key: string]: unknown;
};
type BridgeInput = {
    actionKey: string;
    parameters: Record<string, unknown>;
};
type ArtifactInput = {
    operation: "publish_image" | "publish_html";
    localPath?: string;
    htmlContent?: string;
    title?: string;
    description?: string;
    contentKey?: string;
};
export declare function mapDomainOperation(domain: string, input: DomainInput): BridgeInput;
export declare function callDomainBridge(domain: string, input: DomainInput, ctx: ToolContext, env?: NodeJS.ProcessEnv, fetcher?: typeof fetch): Promise<unknown>;
export declare function callArtifactBridge(input: ArtifactInput, ctx: ToolContext, env?: NodeJS.ProcessEnv, fetcher?: typeof fetch): Promise<unknown>;
declare const plugin: {
    id: string;
    name: string;
    description: string;
    register(api: OpenClawPluginApi): void;
};
export default plugin;
