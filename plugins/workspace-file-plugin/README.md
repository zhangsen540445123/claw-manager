# @claw-manager/workspace-file-plugin

An OpenClaw plugin that exposes one typed tool, `workspace_file`, for file operations inside the active agent workspace.

## Tool contract

```text
workspace_file({
  action: "list" | "read" | "read_document" | "write" | "mkdir" | "delete",
  path: string,
  content?: string,
  expectedSha256?: string,
  recursive?: boolean
})
```

The workspace root is taken from the trusted OpenClaw tool factory context (`ctx.workspaceDir`). It is never accepted as a model argument. Paths must be relative to that root; absolute paths, drive paths, UNC paths, NUL bytes, parent traversal, and symlinks that resolve outside the workspace are rejected.

Writes create a temporary file next to the target and atomically replace the target. Directory deletion requires `recursive: true`, and the workspace root itself cannot be deleted.

For dynamic `wechat_*` Agents, the plugin also guards OpenClaw's native `read` tool. It permits the current Agent workspace and the instance shared `workspace/skills` directory, and blocks other host paths without exposing absolute paths in the error.


## Office/PDF document reading

`workspace_file({ action: "read_document", path })` parses office files that are already inside the active Agent workspace. Supported inputs include plain text, Markdown/JSON/XML/YAML/log files, CSV, Excel (`.xlsx` / `.xls`), Word (`.docx`), PowerPoint (`.pptx`) and PDF text.

Default safety limits are conservative:

- maximum file size: 20 MB;
- maximum extracted text: 80,000 characters;
- maximum Office embedded images: 10;
- maximum PDF text pages: 10.

When a limit is hit, the tool returns a clear warning in `warnings` and marks truncation/limit fields instead of silently dropping content. Extracted Office images are cached below `.openclaw-document-cache/<sha256>/` inside the same workspace, so one WeChat user/Agent cannot read another Agent's document cache.

PDF support currently extracts text. PDF page rendering/OCR is reported as a best-effort limitation so the Agent can explain the gap to the user.
