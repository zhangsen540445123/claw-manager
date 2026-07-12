import { Type } from "@sinclair/typebox";
import { randomUUID } from "node:crypto";
import type { OpenClawPluginApi } from "openclaw/plugin-sdk/plugin-entry";

type ToolContext = { requesterSenderId?: string };
type BridgeInput = { actionKey: string; parameters?: Record<string, unknown> };

const ACTION_KEYS = [
  "daily_checklist", "daily_task_create", "daily_task_update", "daily_task_toggle", "daily_task_delete",
  "daily_task_yesterday_uncompleted_count", "goal_list", "goal_get", "goal_create", "goal_update", "goal_delete",
  "goal_toggle_completion", "goal_uncomplete", "goal_statistics", "goal_year_month_statistics", "goal_categories",
  "goal_category_list", "subtask_list", "subtask_create", "subtask_update", "subtask_delete", "subtask_toggle",
  "habit_checkin", "habit_cancel", "habit_status", "habit_records", "habit_count", "habit_batch",
  "html_create", "html_get", "html_list", "html_delete",
] as const;

export async function callMiniappBridge(
  input: BridgeInput,
  ctx: ToolContext,
  env: NodeJS.ProcessEnv = process.env,
  fetcher: typeof fetch = fetch,
): Promise<unknown> {
  const sender = ctx.requesterSenderId?.trim() ?? "";
  if (!sender) throw new Error("miniapp bridge identity unavailable");
  const baseUrl = (env.CLAW_MANAGER_INTERNAL_BASE_URL ?? "").replace(/\/+$/, "");
  const token = env.OPENVIKING_BROKER_TOKEN ?? "";
  const instanceId = env.OPENVIKING_OPENCLAW_INSTANCE_ID ?? "";
  if (!baseUrl || !token || !instanceId) throw new Error("miniapp bridge runtime configuration missing");
  if (!ACTION_KEYS.includes(input.actionKey as (typeof ACTION_KEYS)[number])) throw new Error("unsupported miniapp actionKey");
  const requestId = `mbreq_${randomUUID().replaceAll("-", "")}`;
  const response = await fetcher(`${baseUrl}/api/internal/miniapp-bridge/actions/${encodeURIComponent(input.actionKey)}`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify({ instanceId, requesterSenderId: sender, parameters: input.parameters ?? {}, requestId }),
  });
  const text = await response.text();
  let body: unknown;
  try { body = text ? JSON.parse(text) : {}; } catch { body = { message: text }; }
  if (!response.ok) throw new Error(`miniapp bridge request failed (${response.status}): ${text.slice(0, 500)}`);
  return body;
}

const plugin = {
  id: "miniapp-bridge",
  name: "Claw Manager Miniapp Bridge",
  description: "Sender-scoped tools for the bound miniapp Open API",
  register(api: OpenClawPluginApi) {
    api.registerTool(
      (ctx: ToolContext) => ({
        name: "miniapp_api_call",
        label: "Miniapp API Call",
        description: "Call an approved goal-management miniapp action for the current sender. Never ask for or pass openid or credentials.",
        parameters: Type.Object({
          actionKey: Type.Union(ACTION_KEYS.map((value) => Type.Literal(value))),
          parameters: Type.Optional(Type.Record(Type.String(), Type.Unknown())),
        }),
        execute: async (_toolCallId: string, params: BridgeInput) => {
          const result = await callMiniappBridge(params, ctx);
          return {
            content: [{ type: "text" as const, text: JSON.stringify(result) }],
            details: result,
          };
        },
      }),
      { name: "miniapp_api_call" },
    );
    api.logger?.info?.("miniapp-bridge: sender-scoped tool registered");
  },
};

export default plugin;
