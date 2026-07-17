import { Type } from "@sinclair/typebox";
import { createHmac, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import os from "node:os";
async function resolveCmTraceId(ctx, env) {
    const secret = env.OPENVIKING_IDENTITY_HASH_SECRET?.trim() ?? "";
    const sessionKey = ctx.sessionKey?.trim() ?? "";
    if (secret && sessionKey) {
        const key = createHmac("sha256", secret).update(sessionKey, "utf8").digest("hex").slice(0, 32);
        const stateDir = env.OPENCLAW_STATE_DIR || env.CLAWDBOT_STATE_DIR || path.join(os.homedir(), ".openclaw");
        try {
            const file = JSON.parse(await readFile(path.join(stateDir, "openviking", "sender-handoff.json"), "utf8"));
            const traceId = file.entries?.[key]?.cmTraceId?.trim() ?? "";
            if (traceId)
                return traceId;
        }
        catch { /* correlation is best effort */ }
    }
    return `cmtrace_orphan_${randomUUID().replaceAll("-", "")}`;
}
async function reportBridgeTool(params) {
    try {
        await params.fetcher(`${params.baseUrl}/api/internal/integration-traces/events`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                authorization: `Bearer ${params.token}`,
                "X-CM-Trace-Id": params.traceId,
            },
            body: JSON.stringify({
                traceId: params.traceId,
                parentRequestId: params.requestId,
                component: "miniapp-bridge",
                stage: `bridge.tool.${params.status}`,
                status: params.status,
                channel: params.sender.startsWith("miniapp:") ? "api" : "wechat",
                instanceId: params.instanceId,
                toolName: params.toolName,
                requestId: params.requestId,
                errorCode: params.status === "failed" ? (params.errorCode ?? "BRIDGE_TOOL_FAILED") : "",
                errorMessage: params.status === "failed" ? sanitizeTraceError(params.errorMessage) : "",
                details: {},
            }),
        });
    }
    catch {
        console.warn(`miniapp bridge trace report failed traceId=${params.traceId} stage=bridge.tool.${params.status}`);
    }
}
const DATE = Type.String({ pattern: "^\\d{4}-\\d{2}-\\d{2}$", description: "Date in yyyy-MM-dd format" });
const DATE_TIME = Type.String({ pattern: "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$", description: "Date-time in yyyy-MM-dd HH:mm:ss format" });
const POSITIVE_ID = Type.Integer({ minimum: 1 });
const YEAR = Type.Integer({ minimum: 2000, maximum: 9999 });
const MONTH = Type.Integer({ minimum: 1, maximum: 12 });
const CATEGORY = Type.Union([
    Type.Literal("study"), Type.Literal("experience"), Type.Literal("relax"),
    Type.Literal("family"), Type.Literal("core"), Type.Literal("work"),
    Type.Literal("social"), Type.Literal("finance"), Type.Literal("health"),
]);
const GOAL_TYPE = Type.Union([Type.Literal("YEAR"), Type.Literal("MONTH")]);
const GOAL_CATEGORY = Type.Union([Type.Literal("PROJECT"), Type.Literal("HABIT")]);
const STATUS = Type.Union([Type.Literal("ACTIVE"), Type.Literal("COMPLETED"), Type.Literal("PAUSED"), Type.Literal("CANCELLED")]);
const PRIORITY = Type.Union([Type.Literal("HIGH"), Type.Literal("MEDIUM"), Type.Literal("LOW")]);
function strictObject(properties) {
    return Type.Object(properties, { additionalProperties: false });
}
const dailyTaskSchema = Type.Union([
    strictObject({ operation: Type.Literal("get_checklist"), date: Type.Optional(DATE) }),
    strictObject({ operation: Type.Literal("create"), title: Type.String({ minLength: 1, maxLength: 200 }), date: Type.Optional(DATE), goalId: Type.Optional(POSITIVE_ID) }),
    strictObject({ operation: Type.Literal("update"), taskId: POSITIVE_ID, title: Type.String({ minLength: 1, maxLength: 200 }), goalId: Type.Optional(POSITIVE_ID) }),
    strictObject({ operation: Type.Literal("toggle"), taskId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("delete"), taskId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("yesterday_uncompleted_count") }),
]);
const goalCreateBase = {
    title: Type.String({ minLength: 1, maxLength: 100 }),
    goalYear: YEAR,
    category: CATEGORY,
    description: Type.Optional(Type.String({ maxLength: 500 })),
    isFrogGoal: Type.Optional(Type.Union([Type.Literal(0), Type.Literal(1)])),
    startTime: Type.Optional(DATE_TIME),
    endTime: Type.Optional(DATE_TIME),
    icon: Type.Optional(Type.String({ maxLength: 100 })),
};
const habitFields = {
    habitStartDate: DATE,
    habitTargetDays: Type.Integer({ minimum: -1 }),
    habitTargetCount: Type.Integer({ minimum: 1 }),
    habitSuffix: Type.String({ minLength: 1, maxLength: 100 }),
    habitPrefix: Type.Optional(Type.String({ maxLength: 100 })),
    habitEncourageText: Type.Optional(Type.String({ maxLength: 500 })),
};
const goalCreateVariants = [
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, goalType: Type.Literal("YEAR"), goalCategory: Type.Literal("PROJECT") }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, goalType: Type.Literal("MONTH"), goalMonth: MONTH, goalCategory: Type.Literal("PROJECT") }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("YEAR"), goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("DAILY"), habitDailyWeekDays: Type.Array(Type.Integer({ minimum: 0, maximum: 6 }), { minItems: 1, uniqueItems: true }) }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("MONTH"), goalMonth: MONTH, goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("DAILY"), habitDailyWeekDays: Type.Array(Type.Integer({ minimum: 0, maximum: 6 }), { minItems: 1, uniqueItems: true }) }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("YEAR"), goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("WEEKLY"), habitWeeklyDays: Type.Integer({ minimum: 1, maximum: 7 }) }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("MONTH"), goalMonth: MONTH, goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("WEEKLY"), habitWeeklyDays: Type.Integer({ minimum: 1, maximum: 7 }) }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("YEAR"), goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("PERIOD"), habitIntervalDays: Type.Integer({ minimum: 1 }) }),
    strictObject({ operation: Type.Literal("create"), ...goalCreateBase, ...habitFields, goalType: Type.Literal("MONTH"), goalMonth: MONTH, goalCategory: Type.Literal("HABIT"), habitFrequencyType: Type.Literal("PERIOD"), habitIntervalDays: Type.Integer({ minimum: 1 }) }),
];
const goalUpdateFields = {
    goalId: POSITIVE_ID,
    title: Type.Optional(Type.String({ minLength: 1, maxLength: 100 })),
    goalContent: Type.Optional(Type.String({ maxLength: 500 })),
    description: Type.Optional(Type.String({ maxLength: 500 })),
    status: Type.Optional(STATUS), priority: Type.Optional(PRIORITY),
    deadline: Type.Optional(DATE_TIME), progress: Type.Optional(Type.Integer({ minimum: 0, maximum: 100 })),
    isFrogGoal: Type.Optional(Type.Union([Type.Literal(0), Type.Literal(1)])),
    startTime: Type.Optional(DATE_TIME), endTime: Type.Optional(DATE_TIME),
    icon: Type.Optional(Type.String({ maxLength: 100 })), goalCategory: Type.Optional(GOAL_CATEGORY),
    habitPrefix: Type.Optional(Type.String({ maxLength: 100 })), habitTargetCount: Type.Optional(Type.Integer({ minimum: 1 })),
    habitSuffix: Type.Optional(Type.String({ maxLength: 100 })), completionSummary: Type.Optional(Type.String()),
    clearHabitConfig: Type.Optional(Type.Boolean()), habitFrequencyType: Type.Optional(Type.Union([Type.Literal("DAILY"), Type.Literal("WEEKLY"), Type.Literal("PERIOD")])),
    habitDailyWeekDays: Type.Optional(Type.Array(Type.Integer({ minimum: 0, maximum: 6 }), { uniqueItems: true })),
    habitWeeklyDays: Type.Optional(Type.Integer({ minimum: 1, maximum: 7 })), habitEncourageText: Type.Optional(Type.String()),
    habitLivesRemaining: Type.Optional(Type.Integer({ minimum: 0 })), habitTargetDays: Type.Optional(Type.Integer({ minimum: -1 })),
    habitStartDate: Type.Optional(DATE), habitIntervalDays: Type.Optional(Type.Integer({ minimum: 1 })),
};
const completionFields = { completedMonth: Type.Optional(MONTH), completionSummary: Type.Optional(Type.String()), completionImages: Type.Optional(Type.String()) };
const goalSchema = Type.Union([
    strictObject({ operation: Type.Literal("list"), year: Type.Optional(YEAR), month: Type.Optional(MONTH), goalType: Type.Optional(GOAL_TYPE), status: Type.Optional(STATUS), category: Type.Optional(CATEGORY), completed: Type.Optional(Type.Union([Type.Literal(0), Type.Literal(1)])), keyword: Type.Optional(Type.String()) }),
    strictObject({ operation: Type.Literal("get"), goalId: POSITIVE_ID }),
    ...goalCreateVariants,
    strictObject({ operation: Type.Literal("update"), ...goalUpdateFields }),
    strictObject({ operation: Type.Literal("delete"), goalId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("toggle_completion"), goalId: POSITIVE_ID, ...completionFields }),
    strictObject({ operation: Type.Literal("uncomplete"), goalId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("statistics") }),
    strictObject({ operation: Type.Literal("year_month_statistics"), year: Type.Optional(YEAR) }),
    strictObject({ operation: Type.Literal("categories"), year: Type.Optional(YEAR), month: Type.Optional(MONTH), goalType: Type.Optional(GOAL_TYPE) }),
    strictObject({ operation: Type.Literal("category_list"), category: CATEGORY, year: Type.Optional(YEAR), month: Type.Optional(MONTH) }),
]);
const subtaskFields = { taskName: Type.String({ minLength: 1, maxLength: 200 }), startTime: Type.Optional(DATE_TIME), endTime: Type.Optional(DATE_TIME), sortOrder: Type.Optional(Type.Integer({ minimum: 0 })) };
const subtaskSchema = Type.Union([
    strictObject({ operation: Type.Literal("list"), goalId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("create"), goalId: POSITIVE_ID, ...subtaskFields }),
    strictObject({ operation: Type.Literal("update"), goalId: POSITIVE_ID, subTaskId: POSITIVE_ID, ...subtaskFields }),
    strictObject({ operation: Type.Literal("toggle"), goalId: POSITIVE_ID, subTaskId: POSITIVE_ID, ...completionFields }),
    strictObject({ operation: Type.Literal("delete"), goalId: POSITIVE_ID, subTaskId: POSITIVE_ID }),
]);
const habitCheckinSchema = Type.Union([
    strictObject({ operation: Type.Literal("status"), goalId: POSITIVE_ID, date: Type.Optional(DATE) }),
    strictObject({ operation: Type.Literal("records"), goalId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("count"), goalId: POSITIVE_ID }),
    strictObject({ operation: Type.Literal("checkin"), goalId: POSITIVE_ID, date: Type.Optional(DATE) }),
    strictObject({ operation: Type.Literal("cancel"), goalId: POSITIVE_ID, date: Type.Optional(DATE) }),
    strictObject({ operation: Type.Literal("batch"), goalId: POSITIVE_ID, count: Type.Integer({ minimum: 1 }) }),
]);
const htmlContentSchema = Type.Union([
    strictObject({ operation: Type.Literal("create"), htmlContent: Type.String({ minLength: 1 }), title: Type.Optional(Type.String()), contentKey: Type.Optional(Type.String({ minLength: 1 })) }),
    strictObject({ operation: Type.Literal("get"), contentKey: Type.String({ minLength: 1 }) }),
    strictObject({ operation: Type.Literal("list") }),
    strictObject({ operation: Type.Literal("delete"), contentKey: Type.String({ minLength: 1 }) }),
]);
const artifactSchema = Type.Union([
    strictObject({ operation: Type.Literal("publish_image"), generatedImageId: Type.String({ pattern: "^img_[a-f0-9]{32}$" }), title: Type.Optional(Type.String({ maxLength: 200 })), description: Type.Optional(Type.String({ maxLength: 1000 })) }),
    strictObject({ operation: Type.Literal("publish_html"), htmlContent: Type.String({ minLength: 1 }), title: Type.Optional(Type.String({ maxLength: 200 })), contentKey: Type.Optional(Type.String({ minLength: 1, maxLength: 200 })) }),
]);
const imageGenerationSchema = strictObject({
    prompt: Type.String({ minLength: 1, maxLength: 4000 }),
    size: Type.Optional(Type.String({ maxLength: 32 })),
    quality: Type.Optional(Type.Union([Type.Literal("auto"), Type.Literal("low"), Type.Literal("medium"), Type.Literal("high")])),
});
const ACTIONS = {
    daily_task: { get_checklist: "daily_checklist", create: "daily_task_create", update: "daily_task_update", toggle: "daily_task_toggle", delete: "daily_task_delete", yesterday_uncompleted_count: "daily_task_yesterday_uncompleted_count" },
    goal: { list: "goal_list", get: "goal_get", create: "goal_create", update: "goal_update", delete: "goal_delete", toggle_completion: "goal_toggle_completion", uncomplete: "goal_uncomplete", statistics: "goal_statistics", year_month_statistics: "goal_year_month_statistics", categories: "goal_categories", category_list: "goal_category_list" },
    subtask: { list: "subtask_list", create: "subtask_create", update: "subtask_update", toggle: "subtask_toggle", delete: "subtask_delete" },
    habit_checkin: { status: "habit_status", records: "habit_records", count: "habit_count", checkin: "habit_checkin", cancel: "habit_cancel", batch: "habit_batch" },
    html_content: { create: "html_create", get: "html_get", list: "html_list", delete: "html_delete" },
};
const CATEGORY_NAMES = { study: "学习·成长", experience: "体验·突破", relax: "休闲·放松", family: "家庭·生活", core: "核心词", work: "工作·事业", social: "人际·社群", finance: "财务·理财", health: "健康·身体" };
export function mapDomainOperation(domain, input) {
    const actionKey = ACTIONS[domain]?.[input.operation];
    if (!actionKey)
        throw new Error("unsupported miniapp operation");
    const parameters = Object.fromEntries(Object.entries(input).filter(([key, value]) => key !== "operation" && value !== undefined));
    if (domain === "goal" && input.operation === "create")
        validateGoalCreate(parameters);
    return { actionKey, parameters };
}
function validateGoalCreate(parameters) {
    for (const key of ["title", "goalType", "goalYear", "goalCategory", "category"])
        requireValue(parameters, key);
    if (parameters.goalType === "MONTH")
        requireValue(parameters, "goalMonth");
    if (parameters.goalType === "YEAR" && parameters.goalMonth !== undefined)
        throw new Error("YEAR goal must not include goalMonth");
    const category = String(parameters.category);
    if (!CATEGORY_NAMES[category])
        throw new Error("invalid category");
    if (parameters.goalCategory === "HABIT") {
        for (const key of ["habitStartDate", "habitTargetDays", "habitTargetCount", "habitSuffix", "habitFrequencyType"])
            requireValue(parameters, key);
        if (parameters.habitFrequencyType === "DAILY")
            requireValue(parameters, "habitDailyWeekDays");
        if (parameters.habitFrequencyType === "WEEKLY")
            requireValue(parameters, "habitWeeklyDays");
        if (parameters.habitFrequencyType === "PERIOD")
            requireValue(parameters, "habitIntervalDays");
    }
}
function requireValue(parameters, key) {
    const value = parameters[key];
    if (value === undefined || value === null || (typeof value === "string" && !value.trim()) || (Array.isArray(value) && value.length === 0)) {
        throw new Error(`missing required parameter: ${key}`);
    }
}
export async function callDomainBridge(domain, input, ctx, env = process.env, fetcher = fetch) {
    const sender = ctx.requesterSenderId?.trim() ?? "";
    if (!sender)
        throw new Error("miniapp bridge identity unavailable");
    const baseUrl = (env.CLAW_MANAGER_INTERNAL_BASE_URL ?? "").replace(/\/+$/, "");
    const token = env.OPENVIKING_BROKER_TOKEN ?? "";
    const instanceId = env.OPENVIKING_OPENCLAW_INSTANCE_ID ?? "";
    if (!baseUrl || !token || !instanceId)
        throw new Error("miniapp bridge runtime configuration missing");
    const mapped = mapDomainOperation(domain, input);
    const requestId = `mbreq_${randomUUID().replaceAll("-", "")}`;
    const cmTraceId = await resolveCmTraceId(ctx, env);
    const trace = { baseUrl, token, instanceId, traceId: cmTraceId, requestId, sender, toolName: `miniapp_${domain}`, fetcher };
    await reportBridgeTool({ ...trace, status: "started" });
    try {
        const response = await fetcher(`${baseUrl}/api/internal/miniapp-bridge/actions/${encodeURIComponent(mapped.actionKey)}`, {
            method: "POST",
            headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "X-CM-Trace-Id": cmTraceId },
            body: JSON.stringify({ instanceId, requesterSenderId: sender, parameters: mapped.parameters, requestId, cmTraceId }),
        });
        const text = await response.text();
        let body;
        try {
            body = text ? JSON.parse(text) : {};
        }
        catch {
            body = { message: text };
        }
        if (!response.ok)
            throw new Error(`miniapp bridge request failed (${response.status}): ${text.slice(0, 500)}`);
        await reportBridgeTool({ ...trace, status: "completed" });
        return body;
    }
    catch (error) {
        await reportBridgeTool({ ...trace, status: "failed", errorMessage: errorMessage(error) });
        throw error;
    }
}
export async function callArtifactBridge(input, ctx, env = process.env, fetcher = fetch) {
    const sender = ctx.requesterSenderId?.trim() ?? "";
    if (!sender)
        throw new Error("miniapp bridge identity unavailable");
    const baseUrl = (env.CLAW_MANAGER_INTERNAL_BASE_URL ?? "").replace(/\/+$/, "");
    const token = env.OPENVIKING_BROKER_TOKEN ?? "";
    const instanceId = env.OPENVIKING_OPENCLAW_INSTANCE_ID ?? "";
    if (!baseUrl || !token || !instanceId)
        throw new Error("miniapp bridge runtime configuration missing");
    const requestId = `mbreq_${randomUUID().replaceAll("-", "")}`;
    const cmTraceId = await resolveCmTraceId(ctx, env);
    const trace = { baseUrl, token, instanceId, traceId: cmTraceId, requestId, sender, toolName: "miniapp_artifact", fetcher };
    await reportBridgeTool({ ...trace, status: "started" });
    try {
        let response;
        if (input.operation === "publish_html") {
            response = await fetcher(`${baseUrl}/api/internal/miniapp-bridge/artifacts/html`, {
                method: "POST",
                headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "X-CM-Trace-Id": cmTraceId },
                body: JSON.stringify({ instanceId, requesterSenderId: sender, requestId, cmTraceId, title: input.title, contentKey: input.contentKey, htmlContent: input.htmlContent }),
            });
        }
        else if (input.operation === "publish_image") {
            response = await fetcher(`${baseUrl}/api/internal/miniapp-bridge/artifacts/generated-images`, {
                method: "POST",
                headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "X-CM-Trace-Id": cmTraceId },
                body: JSON.stringify({ instanceId, requesterSenderId: sender, requestId, cmTraceId,
                    generatedImageId: input.generatedImageId, title: input.title, description: input.description }),
            });
        }
        else {
            throw new Error("unsupported miniapp artifact operation");
        }
        const text = await response.text();
        let body;
        try {
            body = text ? JSON.parse(text) : {};
        }
        catch {
            body = { message: text };
        }
        if (!response.ok)
            throw new Error(`miniapp artifact request failed (${response.status}): ${text.slice(0, 500)}`);
        await reportBridgeTool({ ...trace, status: "completed" });
        return body;
    }
    catch (error) {
        await reportBridgeTool({ ...trace, status: "failed", errorMessage: errorMessage(error) });
        throw error;
    }
}
export async function callImageGeneration(input, ctx, env = process.env, fetcher = fetch) {
    const sender = ctx.requesterSenderId?.trim() ?? "";
    if (!sender)
        throw new Error("miniapp bridge identity unavailable");
    const baseUrl = (env.CLAW_MANAGER_INTERNAL_BASE_URL ?? "").replace(/\/+$/, "");
    const token = env.OPENVIKING_BROKER_TOKEN ?? "";
    const instanceId = env.OPENVIKING_OPENCLAW_INSTANCE_ID ?? "";
    if (!baseUrl || !token || !instanceId)
        throw new Error("miniapp bridge runtime configuration missing");
    const requestId = `mbreq_${randomUUID().replaceAll("-", "")}`;
    const cmTraceId = await resolveCmTraceId(ctx, env);
    const trace = { baseUrl, token, instanceId, traceId: cmTraceId, requestId, sender, toolName: "image_generate", fetcher };
    await reportBridgeTool({ ...trace, status: "started" });
    try {
        const response = await fetcher(`${baseUrl}/api/internal/miniapp-bridge/image-generation`, {
            method: "POST",
            headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "X-CM-Trace-Id": cmTraceId },
            body: JSON.stringify({ instanceId, requesterSenderId: sender, requestId, cmTraceId, prompt: input.prompt, size: input.size, quality: input.quality }),
        });
        const text = await response.text();
        if (!response.ok)
            throw new Error(`image generation request failed (${response.status}): ${text.slice(0, 300)}`);
        let body;
        try {
            body = text ? JSON.parse(text) : {};
        }
        catch {
            throw new Error("image generation service returned invalid JSON");
        }
        await reportBridgeTool({ ...trace, status: "completed" });
        return body;
    }
    catch (error) {
        await reportBridgeTool({ ...trace, status: "failed", errorMessage: errorMessage(error) });
        throw error;
    }
}
function sanitizeTraceError(value) {
    return (value ?? "")
        .replace(/Bearer\s+\S+/gi, "Bearer ***")
        .replace(/cm_user_[A-Za-z0-9_-]+/g, "cm_user_***")
        .replace(/sk-[A-Za-z0-9_-]{8,}/g, "sk-***")
        .slice(0, 500);
}
function errorMessage(error) {
    return error instanceof Error ? error.message : String(error);
}
function registerTool(api, domain, name, label, description, parameters) {
    api.registerTool((ctx) => ({
        name, label, description, parameters,
        execute: async (_toolCallId, input) => {
            const result = await callDomainBridge(domain, input, ctx);
            return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
        },
    }), { name });
}
const plugin = {
    id: "miniapp-bridge",
    name: "Claw Manager Miniapp Bridge",
    description: "Sender-scoped typed tools for the bound Time Manager Open API",
    register(api) {
        registerTool(api, "daily_task", "miniapp_daily_task", "Miniapp Daily Task", "Query and manage the current sender's daily checklist and tasks.", dailyTaskSchema);
        registerTool(api, "goal", "miniapp_goal", "Miniapp Goal", "Query and manage the current sender's yearly and monthly project or habit goals.", goalSchema);
        registerTool(api, "subtask", "miniapp_subtask", "Miniapp Subtask", "Query and manage subtasks belonging to the current sender's goals.", subtaskSchema);
        registerTool(api, "habit_checkin", "miniapp_habit_checkin", "Miniapp Habit Check-in", "Query and manage habit check-ins for the current sender.", habitCheckinSchema);
        registerTool(api, "html_content", "miniapp_html_content", "Miniapp HTML Content", "Store and retrieve displayable HTML content for the current sender.", htmlContentSchema);
        api.registerTool((ctx) => ({
            name: "miniapp_artifact", label: "Miniapp Artifact", description: "Publish generated image or HTML content for the current sender and return trusted miniapp navigation metadata.", parameters: artifactSchema,
            execute: async (_toolCallId, input) => {
                const result = await callArtifactBridge(input, ctx);
                return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
            },
        }), { name: "miniapp_artifact" });
        api.registerTool((ctx) => ({
            name: "image_generate", label: "Image Generate", description: "Generate an image from text for the current sender. Publish it with miniapp_artifact.publish_image afterward.", parameters: imageGenerationSchema,
            execute: async (_toolCallId, input) => {
                const result = await callImageGeneration(input, ctx);
                return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
            },
        }), { name: "image_generate" });
        api.logger?.info?.("miniapp-bridge: seven sender-scoped typed tools registered");
    },
};
export default plugin;
