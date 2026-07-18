import type { WeixinConfigRuntime } from "./messaging/dynamic-agent.js";

let configRuntime: WeixinConfigRuntime | undefined;

export function setWeixinConfigRuntime(next: WeixinConfigRuntime | undefined): void {
  configRuntime = next;
}

export function getWeixinConfigRuntime(): WeixinConfigRuntime | undefined {
  return configRuntime;
}
