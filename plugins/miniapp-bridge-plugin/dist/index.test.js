import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import plugin, { callArtifactBridge, callDomainBridge, callImageGeneration, mapDomainOperation } from "./index.js";
const env = {
    CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080/",
    OPENVIKING_BROKER_TOKEN: "broker-secret",
    OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
};
function fetchCalls(fetcher) {
    return fetcher.mock.calls;
}
const expectedTools = [
    "miniapp_daily_task",
    "miniapp_goal",
    "miniapp_subtask",
    "miniapp_habit_checkin",
    "miniapp_html_content",
    "miniapp_artifact",
    "image_generate",
];
const operationMappings = [
    ["daily_task", "get_checklist", "daily_checklist"],
    ["daily_task", "create", "daily_task_create"],
    ["daily_task", "update", "daily_task_update"],
    ["daily_task", "toggle", "daily_task_toggle"],
    ["daily_task", "delete", "daily_task_delete"],
    ["daily_task", "yesterday_uncompleted_count", "daily_task_yesterday_uncompleted_count"],
    ["goal", "list", "goal_list"],
    ["goal", "get", "goal_get"],
    ["goal", "update", "goal_update"],
    ["goal", "delete", "goal_delete"],
    ["goal", "toggle_completion", "goal_toggle_completion"],
    ["goal", "uncomplete", "goal_uncomplete"],
    ["goal", "statistics", "goal_statistics"],
    ["goal", "year_month_statistics", "goal_year_month_statistics"],
    ["goal", "categories", "goal_categories"],
    ["goal", "category_list", "goal_category_list"],
    ["subtask", "list", "subtask_list"],
    ["subtask", "create", "subtask_create"],
    ["subtask", "update", "subtask_update"],
    ["subtask", "toggle", "subtask_toggle"],
    ["subtask", "delete", "subtask_delete"],
    ["habit_checkin", "status", "habit_status"],
    ["habit_checkin", "records", "habit_records"],
    ["habit_checkin", "count", "habit_count"],
    ["habit_checkin", "checkin", "habit_checkin"],
    ["habit_checkin", "cancel", "habit_cancel"],
    ["habit_checkin", "batch", "habit_batch"],
    ["html_content", "create", "html_create"],
    ["html_content", "get", "html_get"],
    ["html_content", "list", "html_list"],
    ["html_content", "delete", "html_delete"],
];
describe("miniapp bridge", () => {
    it("declares exactly seven typed tools in the OpenClaw manifest", () => {
        const manifest = JSON.parse(readFileSync(new URL("./openclaw.plugin.json", import.meta.url), "utf8"));
        expect(manifest.contracts?.tools).toEqual(expectedTools);
        expect(manifest.contracts?.tools).not.toContain("miniapp_api_call");
    });
    it("registers exactly the seven typed tools and exposes no identity parameters", () => {
        const registered = [];
        const api = {
            registerTool: (factory, options) => registered.push({ factory, name: options.name }),
            logger: { info: vi.fn() },
        };
        plugin.register(api);
        expect(registered.map(item => item.name)).toEqual(expectedTools);
        const schemas = registered.map(item => JSON.stringify(item.factory({ requesterSenderId: "sender" }).parameters)).join("\n");
        expect(schemas).not.toContain("openid");
        expect(schemas).not.toContain("Authorization");
        expect(schemas).not.toContain("userKey");
    });
    it("calls image generation without exposing identity parameters", async () => {
        const fetcher = vi.fn(async () => new Response(JSON.stringify({ localPath: "/workspace/media/generated/img.png" }), { status: 200 }));
        const result = await callImageGeneration({ prompt: "极简红色圆形" }, { requesterSenderId: "sender" }, env, fetcher);
        const [, init] = fetchCalls(fetcher).find(call => String(call[0]).includes("/image-generation"));
        expect(result).toMatchObject({ localPath: "/workspace/media/generated/img.png" });
        expect(String(init.body)).not.toContain("openid");
        expect(String(init.body)).not.toContain("cm_user");
    });
    it("exposes every goal create variant at the top schema level", () => {
        const registered = [];
        plugin.register({
            registerTool: (factory, options) => registered.push({ factory, name: options.name }),
            logger: { info: vi.fn() },
        });
        const goal = registered.find(item => item.name === "miniapp_goal").factory({ requesterSenderId: "sender" }).parameters;
        expect(goal.anyOf.every(branch => branch.anyOf === undefined)).toBe(true);
        expect(goal.anyOf.filter(branch => branch.properties?.operation?.const === "create")).toHaveLength(8);
    });
    it.each(operationMappings)("maps %s.%s to %s", (domain, operation, actionKey) => {
        expect(mapDomainOperation(domain, { operation })).toEqual({ actionKey, parameters: {} });
    });
    it("forwards only sender scope and approved business parameters", async () => {
        const fetcher = vi.fn(async () => new Response(JSON.stringify({ result: { code: 200 } }), { status: 200 }));
        await callDomainBridge("daily_task", { operation: "get_checklist", date: "2026-07-12" }, { requesterSenderId: "wechat-user-1" }, env, fetcher);
        const [url, init] = fetchCalls(fetcher).find(call => String(call[0]).includes("/actions/"));
        expect(url).toContain("/actions/daily_checklist");
        const body = JSON.parse(String(init?.body));
        expect(body.instanceId).toBe("inst-1");
        expect(body.requesterSenderId).toBe("wechat-user-1");
        expect(body.parameters).toEqual({ date: "2026-07-12" });
        expect(JSON.stringify(body)).not.toContain("cm_user_");
    });
    it("reports bridge tool lifecycle without blocking the business call", async () => {
        const fetcher = vi.fn(async (url) => {
            if (String(url).includes("/integration-traces/events"))
                return new Response("{\"accepted\":true}", { status: 200 });
            return new Response("{\"result\":{\"code\":200}}", { status: 200 });
        });
        await callDomainBridge("daily_task", { operation: "get_checklist" }, { requesterSenderId: "wechat-user-1" }, env, fetcher);
        const events = fetchCalls(fetcher)
            .filter(call => String(call[0]).includes("/integration-traces/events"))
            .map(call => JSON.parse(String(call[1].body)));
        expect(events.map(event => event.stage)).toEqual(["bridge.tool.started", "bridge.tool.completed"]);
        expect(events.every(event => event.toolName === "miniapp_daily_task")).toBe(true);
    });
    it("keeps goal category for Claw Manager normalization", () => {
        expect(mapDomainOperation("goal", {
            operation: "create",
            title: "学习英语",
            goalType: "YEAR",
            goalYear: 2026,
            goalCategory: "PROJECT",
            category: "study",
        })).toEqual({
            actionKey: "goal_create",
            parameters: {
                title: "学习英语",
                goalType: "YEAR",
                goalYear: 2026,
                goalCategory: "PROJECT",
                category: "study",
            },
        });
    });
    it("rejects a monthly goal without goalMonth", () => {
        expect(() => mapDomainOperation("goal", {
            operation: "create", title: "七月阅读", goalType: "MONTH", goalYear: 2026,
            goalCategory: "PROJECT", category: "study",
        })).toThrow("goalMonth");
    });
    it("rejects incomplete habit schedules", () => {
        expect(() => mapDomainOperation("goal", {
            operation: "create", title: "每日跑步", goalType: "YEAR", goalYear: 2026,
            goalCategory: "HABIT", category: "health", habitStartDate: "2026-07-12",
            habitTargetDays: 30, habitTargetCount: 1, habitSuffix: "次",
            habitFrequencyType: "DAILY",
        })).toThrow("habitDailyWeekDays");
    });
    it("rejects unknown operations before calling the network", async () => {
        const fetcher = vi.fn();
        await expect(callDomainBridge("goal", { operation: "arbitrary_http" }, { requesterSenderId: "sender" }, env, fetcher)).rejects.toThrow("unsupported miniapp operation");
        expect(fetcher).not.toHaveBeenCalled();
    });
    it("rejects missing sender identity without fallback", async () => {
        await expect(callDomainBridge("goal", { operation: "list" }, {}, env, vi.fn()))
            .rejects.toThrow("identity unavailable");
    });
    it("publishes html as a trusted artifact without exposing identity parameters", async () => {
        const fetcher = vi.fn(async () => new Response(JSON.stringify({
            artifact: { id: "artifact-1", type: "html_report", title: "周报", miniappPath: "/pages/html-viewer/index?contentKey=x" },
        }), { status: 200, headers: { "content-type": "application/json" } }));
        const result = await callArtifactBridge({ operation: "publish_html", htmlContent: "<html>原文</html>", title: "周报" }, { requesterSenderId: "wechat-user-1" }, env, fetcher);
        const [url, init] = fetchCalls(fetcher).find(call => String(call[0]).includes("/artifacts/html"));
        expect(url).toContain("/artifacts/html");
        expect(JSON.parse(String(init.body))).toMatchObject({
            instanceId: "inst-1", requesterSenderId: "wechat-user-1", htmlContent: "<html>原文</html>", title: "周报",
        });
        expect(result).toMatchObject({ artifact: { type: "html_report" } });
    });
    it("rejects image paths outside configured media roots before network access", async () => {
        const fetcher = vi.fn(async () => new Response("{\"accepted\":true}", { status: 200 }));
        await expect(callArtifactBridge({ operation: "publish_image", localPath: "C:/Windows/System32/logo.png" }, { requesterSenderId: "wechat-user-1" }, { ...env, OPENCLAW_ARTIFACT_DIRS: "D:/allowed/media" }, fetcher)).rejects.toThrow("allowed media directories");
        expect(fetchCalls(fetcher).some(call => String(call[0]).includes("/artifacts/images"))).toBe(false);
    });
});
