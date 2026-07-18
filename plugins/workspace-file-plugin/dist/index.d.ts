import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";
export type WorkspaceFileAction = "list" | "read" | "write" | "mkdir" | "delete";
export type WorkspaceFileInput = {
    action: WorkspaceFileAction;
    path: string;
    content?: string;
    expectedSha256?: string;
    recursive?: boolean;
};
type WorkspaceFileResult = {
    action: WorkspaceFileAction;
    path: string;
    [key: string]: unknown;
};
export declare function executeWorkspaceFile(workspaceDir: string, input: WorkspaceFileInput): Promise<WorkspaceFileResult>;
declare const plugin: {
    id: string;
    name: string;
    description: string;
    register(api: OpenClawPluginApi): void;
};
export default plugin;
