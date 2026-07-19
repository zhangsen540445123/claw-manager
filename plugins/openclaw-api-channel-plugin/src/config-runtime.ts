import type { OpenClawConfig } from "openclaw/plugin-sdk/core";

export type ApiConfigRuntime = {
  current?: () => OpenClawConfig | Record<string, unknown>;
  mutateConfigFile?: (params: Record<string, unknown>) => Promise<{ result?: unknown } | unknown>;
};

let configRuntime: ApiConfigRuntime | undefined;

export function setApiConfigRuntime(next: ApiConfigRuntime | undefined): void {
  configRuntime = next;
}

export function getApiConfigRuntime(): ApiConfigRuntime | undefined {
  return configRuntime;
}

export function resetApiConfigRuntimeForTest(): void {
  configRuntime = undefined;
}
