import { createHash } from "node:crypto";
import { copyFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { parseDocument, type DocumentParseLimits, type ParsedDocument } from "@claw-manager/document-parser";

export type ArchivedWeixinDocument = {
  workspace: string;
  originalRelativePath: string;
  metadataRelativePath: string;
  parsed: ParsedDocument;
  mediaPaths: string[];
  mediaTypes: string[];
};

export type ArchiveAndParseWeixinDocumentInput = {
  workspace: string;
  downloadedFilePath: string;
  filename?: string;
  mime?: string;
  accountId: string;
  peerId: string;
  messageSid: string;
  day?: string;
  limits?: Partial<DocumentParseLimits>;
  modelSupportsImages?: boolean;
};

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function sanitizeFilename(name?: string): string {
  const base = path.basename((name ?? "file.bin").replaceAll("\\", "/")).replace(/[\u0000-\u001f]/g, "").trim();
  return base || "file.bin";
}

function sanitizeSegment(value: string): string {
  return value.replace(/[^A-Za-z0-9_.-]/g, "_").slice(0, 120) || "message";
}

function todayCompact(): string {
  return new Date().toISOString().slice(0, 10).replaceAll("-", "");
}

function toRelative(workspace: string, target: string): string {
  return path.relative(workspace, target).split(path.sep).join("/");
}

export async function archiveAndParseWeixinDocument(input: ArchiveAndParseWeixinDocumentInput): Promise<ArchivedWeixinDocument> {
  const safeName = sanitizeFilename(input.filename);
  const messageDir = path.join(
    input.workspace,
    ".openclaw-inbox",
    "weixin",
    input.day ?? todayCompact(),
    sanitizeSegment(input.messageSid),
  );
  const originalDir = path.join(messageDir, "original");
  const parsedDir = path.join(messageDir, "parsed");
  await mkdir(originalDir, { recursive: true });
  await mkdir(parsedDir, { recursive: true });

  const originalPath = path.join(originalDir, safeName);
  await copyFile(input.downloadedFilePath, originalPath);
  const parsed = await parseDocument({
    filePath: originalPath,
    filename: safeName,
    mime: input.mime,
    outputDir: parsedDir,
    limits: input.limits,
  });
  const warnings = [...parsed.warnings];
  if (parsed.images.length > 0 && input.modelSupportsImages === false) {
    warnings.push("当前模型不支持图片理解，已仅使用文字内容。");
  }
  const normalizedParsed: ParsedDocument = warnings.length === parsed.warnings.length
    ? parsed
    : { ...parsed, warnings };
  await writeFile(path.join(parsedDir, "document.txt"), normalizedParsed.text, "utf8");
  const mediaPaths = input.modelSupportsImages === false ? [] : normalizedParsed.images.map((image) => image.path);
  const mediaTypes = input.modelSupportsImages === false ? [] : normalizedParsed.images.map((image) => image.mime);
  const metadata = {
    source: "openclaw-weixin",
    accountIdHash: sha256(input.accountId).slice(0, 16),
    peerIdHash: sha256(input.peerId).slice(0, 16),
    messageSid: input.messageSid,
    filename: safeName,
    mime: normalizedParsed.mime,
    sizeBytes: normalizedParsed.sizeBytes,
    textChars: normalizedParsed.textChars,
    textTruncated: normalizedParsed.textTruncated,
    imageCount: normalizedParsed.images.length,
    warnings: normalizedParsed.warnings,
  };
  await writeFile(path.join(parsedDir, "metadata.json"), JSON.stringify(metadata, null, 2), "utf8");
  return {
    workspace: input.workspace,
    originalRelativePath: toRelative(input.workspace, originalPath),
    metadataRelativePath: toRelative(input.workspace, path.join(parsedDir, "metadata.json")),
    parsed: normalizedParsed,
    mediaPaths,
    mediaTypes,
  };
}

export function formatParsedDocumentForInboundBody(originalBody: string, archived: ArchivedWeixinDocument): string {
  const parsed = archived.parsed;
  const status = parsed.kind === "unsupported" ? "失败" : parsed.warnings.length ? "部分成功" : "成功";
  const sections: string[] = [];
  const trimmedOriginal = originalBody.trim();
  if (trimmedOriginal) sections.push(`【用户消息】\n${trimmedOriginal}`);
  sections.push([
    "【收到微信文件】",
    `文件名：${parsed.filename}`,
    "已保存到你的工作区：",
    archived.originalRelativePath,
    "",
    "【文件处理提示】",
    `- 状态：${status}`,
    `- 已提取文字：${parsed.textChars} 字`,
    `- 已提取图片：${parsed.images.length} 张`,
    ...parsed.warnings.map((warning) => `- ${warning}`),
    "",
    "【文档文字】",
    parsed.text || "（未提取到可读文字）",
  ].join("\n"));
  return sections.join("\n\n");
}

export function findAgentWorkspaceFromConfig(cfg: Record<string, any>, agentId?: string): string | undefined {
  if (!agentId) return undefined;
  const agents = cfg.agents && typeof cfg.agents === "object" ? cfg.agents : undefined;
  const list = Array.isArray(agents?.list) ? agents.list : [];
  const agent = list.find((entry: unknown) => Boolean(entry) && typeof entry === "object" && (entry as Record<string, unknown>).id === agentId) as Record<string, unknown> | undefined;
  return typeof agent?.workspace === "string" && agent.workspace.trim() ? agent.workspace : undefined;
}
