import { mkdtemp, readFile, rm, symlink, writeFile, readdir } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import plugin, { executeWorkspaceFile } from "./index.js";
const workspaces = [];
async function createWorkspace() {
    const workspace = await mkdtemp(path.join(os.tmpdir(), "workspace-file-plugin-"));
    workspaces.push(workspace);
    return workspace;
}
afterEach(async () => {
    await Promise.all(workspaces.splice(0).map((workspace) => rm(workspace, { recursive: true, force: true })));
});
describe("workspace-file plugin contract", () => {
    it("registers workspace_file as an official tool factory", () => {
        const registerTool = vi.fn();
        plugin.register({ registerTool, logger: { info: vi.fn() } });
        expect(registerTool).toHaveBeenCalledTimes(1);
        const [factory, options] = registerTool.mock.calls[0];
        expect(options).toEqual({ name: "workspace_file" });
        expect(factory({ workspaceDir: "C:/workspace" })).toMatchObject({ name: "workspace_file" });
        expect(factory({})).toBeNull();
    });
});
describe("executeWorkspaceFile", () => {
    it("writes text atomically inside the current workspace", async () => {
        const workspace = await createWorkspace();
        const result = await executeWorkspaceFile(workspace, {
            action: "write",
            path: "notes/today.md",
            content: "hello",
        });
        expect(result).toMatchObject({ action: "write", path: "notes/today.md", bytes: 5 });
        await expect(readFile(path.join(workspace, "notes", "today.md"), "utf8")).resolves.toBe("hello");
    });
    it("lists and reads files using workspace-relative paths", async () => {
        const workspace = await createWorkspace();
        await writeFile(path.join(workspace, "README.md"), "read me");
        await expect(executeWorkspaceFile(workspace, { action: "list", path: "." })).resolves.toMatchObject({
            action: "list",
            path: ".",
            entries: [{ name: "README.md", path: "README.md", type: "file" }],
        });
        await expect(executeWorkspaceFile(workspace, { action: "read", path: "README.md" })).resolves.toMatchObject({
            action: "read",
            path: "README.md",
            content: "read me",
            bytes: 7,
        });
    });
    it("creates directories and deletes files or explicitly recursive directories", async () => {
        const workspace = await createWorkspace();
        await executeWorkspaceFile(workspace, { action: "mkdir", path: "notes/archive" });
        await executeWorkspaceFile(workspace, { action: "write", path: "notes/archive/item.md", content: "item" });
        await executeWorkspaceFile(workspace, { action: "delete", path: "notes/archive/item.md" });
        await expect(readdir(path.join(workspace, "notes", "archive"))).resolves.toEqual([]);
        await expect(executeWorkspaceFile(workspace, { action: "delete", path: "notes" })).rejects.toThrow("recursive=true");
        await executeWorkspaceFile(workspace, { action: "delete", path: "notes", recursive: true });
        await expect(readdir(workspace)).resolves.toEqual([]);
    });
    it("never deletes the workspace root", async () => {
        const workspace = await createWorkspace();
        await expect(executeWorkspaceFile(workspace, { action: "delete", path: ".", recursive: true })).rejects.toThrow("root");
    });
    it.each([
        ["/tmp/outside", "relative"],
        ["C:/outside.txt", "relative"],
        ["C:outside.txt", "relative"],
        ["\\\\server\\share\\outside.txt", "relative"],
        ["nested/../outside.txt", "escapes"],
        ["nested\\..\\outside.txt", "escapes"],
        ["bad\0name", "invalid"],
    ])("rejects unsafe path %j", async (unsafePath, reason) => {
        const workspace = await createWorkspace();
        await expect(executeWorkspaceFile(workspace, { action: "read", path: unsafePath })).rejects.toThrow(reason);
    });
    it("rejects optimistic writes when the file changed", async () => {
        const workspace = await createWorkspace();
        const first = await executeWorkspaceFile(workspace, { action: "write", path: "note.md", content: "one" });
        await expect(executeWorkspaceFile(workspace, {
            action: "write",
            path: "note.md",
            content: "two",
            expectedSha256: "0".repeat(64),
        })).rejects.toThrow("expectedSha256");
        expect(first).toMatchObject({ sha256: expect.any(String) });
        await expect(readFile(path.join(workspace, "note.md"), "utf8")).resolves.toBe("one");
    });
    it("rejects symlinks that resolve outside the workspace", async () => {
        const workspace = await createWorkspace();
        const outside = await createWorkspace();
        await writeFile(path.join(outside, "secret.txt"), "secret");
        const link = path.join(workspace, "outside");
        try {
            await symlink(outside, link, "junction");
        }
        catch {
            return;
        }
        await expect(executeWorkspaceFile(workspace, { action: "read", path: "outside/secret.txt" })).rejects.toThrow("escapes");
        await expect(executeWorkspaceFile(workspace, { action: "write", path: "outside/new.txt", content: "blocked" })).rejects.toThrow("escapes");
    });
    it("does not leave temporary files after an atomic write", async () => {
        const workspace = await createWorkspace();
        await executeWorkspaceFile(workspace, { action: "write", path: "atomic.txt", content: "content" });
        const entries = await readdir(workspace);
        expect(entries).toEqual(["atomic.txt"]);
    });
    it("does not expose host paths in filesystem errors", async () => {
        const workspace = await createWorkspace();
        const error = await executeWorkspaceFile(workspace, { action: "read", path: "missing.txt" }).catch((value) => value);
        expect(error).toBeInstanceOf(Error);
        expect(error.message).toBe("workspace file not found");
        expect(error.message).not.toContain(workspace);
    });
});
