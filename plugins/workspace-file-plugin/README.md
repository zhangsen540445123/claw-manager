# @claw-manager/workspace-file-plugin

An OpenClaw plugin that exposes one typed tool, `workspace_file`, for file operations inside the active agent workspace.

## Tool contract

```text
workspace_file({
  action: "list" | "read" | "write" | "mkdir" | "delete",
  path: string,
  content?: string,
  expectedSha256?: string,
  recursive?: boolean
})
```

The workspace root is taken from the trusted OpenClaw tool factory context (`ctx.workspaceDir`). It is never accepted as a model argument. Paths must be relative to that root; absolute paths, drive paths, UNC paths, NUL bytes, parent traversal, and symlinks that resolve outside the workspace are rejected.

Writes create a temporary file next to the target and atomically replace the target. Directory deletion requires `recursive: true`, and the workspace root itself cannot be deleted.
