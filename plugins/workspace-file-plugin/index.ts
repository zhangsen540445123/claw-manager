import { Type } from "@sinclair/typebox";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, open, readFile, readdir, realpath, rename, rm, lstat } from "node:fs/promises";
import type { OpenClawPluginApi, OpenClawPluginToolContext } from "openclaw/plugin-sdk/plugin-entry";
import path from "node:path";

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

type NativeReadEvent = {
  toolName: string;
  params: Record<string, unknown>;
};

type NativeReadContext = {
  agentId?: string;
};

class WorkspaceFileError extends Error {
  readonly workspaceFileError = true;
}

function fail(message: string): never {
  throw new WorkspaceFileError(message);
}

function safeFsMessage(error: unknown): string {
  switch ((error as NodeJS.ErrnoException).code) {
    case "ENOENT": return "workspace file not found";
    case "EISDIR": return "workspace path is a directory";
    case "ENOTDIR": return "workspace path is not a directory";
    case "EEXIST": return "workspace file already exists";
    case "EACCES":
    case "EPERM": return "workspace file access denied";
    default: return "workspace file operation failed";
  }
}

function normalizeRelativePath(input: string): string {
  if (typeof input !== "string" || input.includes("\0")) {
    return fail("workspace file path is invalid");
  }

  const normalized = input.replaceAll("\\", "/");
  if (!normalized || normalized === ".") return ".";
  if (normalized.startsWith("/") || normalized.startsWith("//") || /^[A-Za-z]:/.test(normalized)) {
    return fail("workspace file path must be relative");
  }

  const segments = normalized.split("/");
  if (segments.some((segment) => segment === "..")) {
    return fail("workspace file path escapes workspace");
  }

  const relative = segments.filter(Boolean).join(path.sep);
  return relative || ".";
}

function isInside(root: string, candidate: string): boolean {
  const relative = path.relative(root, candidate);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function existingAncestor(candidate: string): Promise<string> {
  let current = candidate;
  while (true) {
    try {
      return await realpath(current);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      const parent = path.dirname(current);
      if (parent === current) throw new Error("workspace file path cannot be resolved");
      current = parent;
    }
  }
}

async function allowedRoot(root: unknown): Promise<string | undefined> {
  if (typeof root !== "string" || !root.trim()) return undefined;
  try {
    const resolved = await realpath(root);
    return (await lstat(resolved)).isDirectory() ? resolved : undefined;
  } catch {
    return undefined;
  }
}

function nativeReadPath(params: Record<string, unknown>): string | undefined {
  for (const key of ["path", "file_path", "filePath"]) {
    const value = params[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return undefined;
}

export async function guardNativeRead(
  config: Record<string, any>,
  event: NativeReadEvent,
  ctx: NativeReadContext,
): Promise<{ block: true; blockReason: string } | undefined> {
  if (event.toolName !== "read" || !ctx.agentId?.startsWith("wechat_")) return undefined;

  const agents = config?.agents && typeof config.agents === "object" ? config.agents : {};
  const list = Array.isArray(agents.list) ? agents.list : [];
  const agent = list.find((item: unknown) =>
    Boolean(item) && typeof item === "object" && (item as Record<string, unknown>).id === ctx.agentId);
  const workspace = agent && typeof agent.workspace === "string" ? agent.workspace : undefined;
  const requestedPath = nativeReadPath(event.params);
  if (!workspace || !requestedPath || requestedPath.includes("\0")) {
    return { block: true, blockReason: "read path is outside the current Agent workspace" };
  }

  const roots = [
    await allowedRoot(workspace),
    await allowedRoot(
      typeof agents.defaults?.workspace === "string"
        ? path.join(agents.defaults.workspace, "skills")
        : undefined,
    ),
  ].filter((value): value is string => Boolean(value));
  const candidate = path.isAbsolute(requestedPath)
    ? path.resolve(requestedPath)
    : path.resolve(workspace, requestedPath);
  let target: string;
  try {
    target = await realpath(candidate).catch(() => existingAncestor(candidate));
  } catch {
    return { block: true, blockReason: "read path is outside the current Agent workspace" };
  }
  if (roots.some((root) => isInside(root, target))) return undefined;
  return { block: true, blockReason: "read path is outside the current Agent workspace" };
}

async function resolveWorkspacePath(workspaceDir: string, inputPath: string): Promise<{ root: string; absolute: string; relative: string }> {
  if (typeof workspaceDir !== "string" || !workspaceDir.trim()) {
    return fail("workspace directory is unavailable");
  }

  const relative = normalizeRelativePath(inputPath);
  const root = await realpath(workspaceDir).catch(() => fail("workspace directory is unavailable"));
  const rootStat = await lstat(root);
  if (!rootStat.isDirectory()) return fail("workspace directory is invalid");

  const absolute = path.resolve(workspaceDir, relative);
  const targetRealPath = await realpath(absolute).catch(async (error: unknown) => {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    return existingAncestor(path.dirname(absolute));
  });
  if (!isInside(root, targetRealPath)) return fail("workspace file path escapes workspace");
  return { root, absolute, relative: relative.split(path.sep).join("/") };
}

async function currentSha256(filePath: string): Promise<string | undefined> {
  try {
    const data = await readFile(filePath);
    return createHash("sha256").update(data).digest("hex");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

async function writeAtomically(filePath: string, content: string): Promise<number> {
  const temporaryPath = path.join(path.dirname(filePath), `.${path.basename(filePath)}.tmp-${randomUUID()}`);
  const handle = await open(temporaryPath, "wx", 0o600);
  try {
    await handle.writeFile(content, "utf8");
    await handle.sync();
  } catch (error) {
    await handle.close().catch(() => undefined);
    await rm(temporaryPath, { force: true }).catch(() => undefined);
    throw error;
  } finally {
    await handle.close().catch(() => undefined);
  }

  try {
    await rename(temporaryPath, filePath);
  } catch (error) {
    await rm(temporaryPath, { force: true }).catch(() => undefined);
    throw error;
  }
  return Buffer.byteLength(content, "utf8");
}

async function executeList(resolved: Awaited<ReturnType<typeof resolveWorkspacePath>>): Promise<WorkspaceFileResult> {
  const entries = await readdir(resolved.absolute, { withFileTypes: true });
  return {
    action: "list",
    path: resolved.relative,
    entries: entries.map((entry) => ({
      name: entry.name,
      path: path.posix.join(resolved.relative === "." ? "" : resolved.relative, entry.name),
      type: entry.isDirectory() ? "directory" : entry.isSymbolicLink() ? "symlink" : "file",
    })),
  };
}

async function executeWorkspaceFileInternal(workspaceDir: string, input: WorkspaceFileInput): Promise<WorkspaceFileResult> {
  if (!input || !["list", "read", "write", "mkdir", "delete"].includes(input.action)) {
    return fail("workspace file action is invalid");
  }

  const resolved = await resolveWorkspacePath(workspaceDir, input.path);
  if (input.action === "list") return executeList(resolved);

  if (input.action === "read") {
    const content = await readFile(resolved.absolute, "utf8");
    return {
      action: "read",
      path: resolved.relative,
      content,
      bytes: Buffer.byteLength(content, "utf8"),
      sha256: createHash("sha256").update(content, "utf8").digest("hex"),
    };
  }

  if (input.action === "write") {
    if (typeof input.content !== "string") return fail("workspace file content is required");
    const current = await currentSha256(resolved.absolute);
    if (input.expectedSha256 !== undefined && current !== input.expectedSha256) {
      return fail("workspace file changed since expectedSha256");
    }
    await mkdir(path.dirname(resolved.absolute), { recursive: true });
    const bytes = await writeAtomically(resolved.absolute, input.content);
    return {
      action: "write",
      path: resolved.relative,
      bytes,
      sha256: createHash("sha256").update(input.content, "utf8").digest("hex"),
    };
  }

  if (input.action === "mkdir") {
    await mkdir(resolved.absolute, { recursive: true });
    return { action: "mkdir", path: resolved.relative };
  }

  const targetRealPath = await realpath(resolved.absolute);
  if (targetRealPath === resolved.root) {
    return fail("workspace root cannot be deleted");
  }
  const targetStat = await lstat(resolved.absolute);
  if (targetStat.isDirectory() && !input.recursive) {
    return fail("deleting a directory requires recursive=true");
  }
  await rm(resolved.absolute, { recursive: input.recursive === true, force: false });
  return { action: "delete", path: resolved.relative };
}

export async function executeWorkspaceFile(workspaceDir: string, input: WorkspaceFileInput): Promise<WorkspaceFileResult> {
  try {
    return await executeWorkspaceFileInternal(workspaceDir, input);
  } catch (error) {
    if (error instanceof WorkspaceFileError) throw error;
    throw new WorkspaceFileError(safeFsMessage(error));
  }
}

const workspaceFileSchema = Type.Object({
  action: Type.Union([
    Type.Literal("list"),
    Type.Literal("read"),
    Type.Literal("write"),
    Type.Literal("mkdir"),
    Type.Literal("delete"),
  ]),
  path: Type.String(),
  content: Type.Optional(Type.String()),
  expectedSha256: Type.Optional(Type.String()),
  recursive: Type.Optional(Type.Boolean()),
}, { additionalProperties: false });

const plugin = {
  id: "workspace-file",
  name: "OpenClaw Workspace File",
  description: "Workspace-scoped file operations for the current OpenClaw agent.",
  register(api: OpenClawPluginApi) {
    const hookApi = api as OpenClawPluginApi & {
      on?: (event: string, handler: (event: NativeReadEvent, ctx: NativeReadContext) => unknown) => void;
    };
    hookApi.on?.("before_tool_call", (event, ctx) =>
      guardNativeRead(api.runtime.config.current() as Record<string, any>, event, ctx));
    api.registerTool((ctx: OpenClawPluginToolContext) => {
      if (!ctx.workspaceDir) return null;
      return {
        name: "workspace_file",
        label: "Workspace File",
        description: "List, read, write, create directories, or delete files inside the current agent workspace.",
        parameters: workspaceFileSchema,
        execute: async (_toolCallId: string, input: WorkspaceFileInput) => {
          const details = await executeWorkspaceFile(ctx.workspaceDir!, input);
          return { content: [{ type: "text" as const, text: JSON.stringify(details) }], details };
        },
      };
    }, { name: "workspace_file" });
  },
};

export default plugin;
