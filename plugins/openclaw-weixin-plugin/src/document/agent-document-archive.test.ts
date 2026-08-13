import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { archiveAndParseWeixinDocument, formatParsedDocumentForInboundBody } from "./agent-document-archive.js";

const tempDirs: string[] = [];
async function tempDir(): Promise<string> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "weixin-doc-archive-"));
  tempDirs.push(dir);
  return dir;
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));


});

describe("Weixin agent document archive", () => {
  it("archives inbound files into the routed agent workspace and returns visual inputs", async () => {
    const root = await tempDir();
    const workspace = path.join(root, "workspace-user");
    const downloaded = path.join(root, "downloaded.txt");
    await writeFile(downloaded, "abcdef", "utf8");

    const archived = await archiveAndParseWeixinDocument({
      workspace,
      downloadedFilePath: downloaded,
      filename: "report.txt",
      mime: "text/plain",
      accountId: "account-secret",
      peerId: "peer-secret",
      messageSid: "sid-123",
      day: "20260813",
      limits: { maxTextChars: 4 },
    });

    expect(archived.originalRelativePath).toBe(".openclaw-inbox/weixin/20260813/sid-123/original/report.txt");
    await expect(readFile(path.join(workspace, archived.originalRelativePath), "utf8")).resolves.toBe("abcdef");
    await expect(readFile(path.join(workspace, archived.metadataRelativePath), "utf8")).resolves.toContain("accountIdHash");
    const prompt = formatParsedDocumentForInboundBody("", archived);
    expect(prompt).toContain("【收到微信文件】");
    expect(prompt).toContain("已保存到你的工作区");
    expect(prompt).toContain("文档文字较长");
    expect(prompt).toContain("abcd");
    expect(prompt).not.toContain("account-secret");
    expect(prompt).not.toContain("peer-secret");
  });

  it("keeps the original message body before document text", async () => {
    const root = await tempDir();
    const downloaded = path.join(root, "report.txt");
    await writeFile(downloaded, "content", "utf8");
    const archived = await archiveAndParseWeixinDocument({
      workspace: path.join(root, "workspace-user"),
      downloadedFilePath: downloaded,
      filename: "report.txt",
      mime: "text/plain",
      accountId: "a",
      peerId: "p",
      messageSid: "sid-456",
      day: "20260813",
    });

    expect(formatParsedDocumentForInboundBody("请总结", archived)).toContain("【用户消息】\n请总结\n\n【收到微信文件】");
  });

  it("stores metadata and gives a Chinese prompt when parsing fails", async () => {
    const root = await tempDir();
    const downloaded = path.join(root, "bad.bin");
    await writeFile(downloaded, "bad", "utf8");
    const archived = await archiveAndParseWeixinDocument({
      workspace: path.join(root, "workspace-user"),
      downloadedFilePath: downloaded,
      filename: "bad.bin",
      mime: "application/octet-stream",
      accountId: "a",
      peerId: "p",
      messageSid: "sid-failed",
      day: "20260813",
    });

    expect(archived.parsed.status).toBe("unsupported");
    const metadata = JSON.parse(await readFile(path.join(archived.workspace, archived.metadataRelativePath), "utf8"));
    expect(metadata.status).toBe("unsupported");
    expect(metadata.limitsHit).toContain("unsupportedMime");
    await expect(readFile(path.join(archived.workspace, ".openclaw-inbox/weixin/20260813/sid-failed/parsed/document.txt"), "utf8")).resolves.toBe("");
    expect(formatParsedDocumentForInboundBody("", archived)).toContain("收到一个文档，但解析失败。文件已保存到工作区");
  });

});
