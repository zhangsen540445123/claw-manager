import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
export type WorkspaceFileAction = "list" | "read" | "read_document" | "write" | "mkdir" | "delete";
export type WorkspaceFileInput = {
    action: WorkspaceFileAction;
    path: string;
    content?: string;
    expectedSha256?: string;
    recursive?: boolean;
    maxTextChars?: number;
    maxImages?: number;
    maxPdfPages?: number;
    includeImages?: boolean;
};
type WorkspaceFileResult = {
    action: WorkspaceFileAction;
    path: string;
    [key: string]: unknown;
};
type NativeReadEvent = {
    toolName: string;
    params: Record<string, unknown>;
};
type NativeReadContext = {
    agentId?: string;
};
export declare function guardNativeRead(config: Record<string, any>, event: NativeReadEvent, ctx: NativeReadContext): Promise<{
    block: true;
    blockReason: string;
} | undefined>;
export declare function executeWorkspaceFile(workspaceDir: string, input: WorkspaceFileInput): Promise<WorkspaceFileResult>;
declare const plugin: {
    id: string;
    name: string;
    description: string;
    register(api: OpenClawPluginApi): void;
};
export default plugin;
