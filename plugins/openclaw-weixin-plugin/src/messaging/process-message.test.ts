import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import JSZip from "jszip";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  attachParsedWeixinDocumentToContext,
  attachSenderRuntimeIdentity,
  modelSupportsImagesFromConfig,
  reportWechatTrace,
  requestsImageGeneration,
  resolveRequiredUserAgentIdentity,
} from "./process-message.js";


const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "weixin-process-doc-"));
  tempDirs.push(dir);
  return dir;
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

async function writeMinimalDocx(filePath: string, text: string, imageBytes?: Buffer): Promise<void> {
  const zip = new JSZip();
  zip.file("[Content_Types].xml", `<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`);
  zip.file("_rels/.rels", `<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`);
  zip.file("word/document.xml", `<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:body></w:document>`);
  if (imageBytes) zip.file("word/media/image1.png", imageBytes);
  await writeFile(filePath, await zip.generateAsync({ type: "nodebuffer" }));
}

describe("WeChat trace reporting", () => {
  it("marks image requests without retaining the message text", () => {
    expect(requestsImageGeneration("生成一张目标九宫格海报")).toBe(true);
    expect(requestsImageGeneration("帮我查询今日待办")).toBe(false);
  });
  it("reports media delivery failures without user identity data", async () => {
    const fetcher = vi.fn(async () => new Response("{\"accepted\":true}", { status: 200 }));
    await reportWechatTrace({
      traceId: "cmtrace_wechat123",
      stage: "wechat.media.send.failed",
      status: "failed",
      requestId: "run-1",
      elapsedMs: 35,
      env: {
        CLAW_MANAGER_INTERNAL_BASE_URL: "http://claw-manager-api:8080",
        OPENVIKING_BROKER_TOKEN: "broker-secret",
        OPENVIKING_OPENCLAW_INSTANCE_ID: "inst-1",
      },
      fetcher: fetcher as typeof fetch,
    });

    const [, init] = fetcher.mock.calls[0]! as unknown as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toMatchObject({
      component: "wechat-plugin", stage: "wechat.media.send.failed", channel: "wechat", elapsedMs: 35,
    });
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(String(init.body)).not.toContain("openid");
  });
});

describe("attachSenderRuntimeIdentity", () => {
  it("adds sender identity fields to the finalized dispatch context", () => {
    const finalized = { Body: "hello" };

    const ctx = attachSenderRuntimeIdentity(finalized, "wx_sender_ABC");

    expect(ctx).toBe(finalized);
    expect(ctx.SenderId).toBe("wx_sender_ABC");
    expect(ctx.senderId).toBe("wx_sender_ABC");
    expect(ctx.requesterSenderId).toBe("wx_sender_ABC");
  });

  it("keeps identity fields empty when the inbound Weixin sender is empty", () => {
    const ctx = attachSenderRuntimeIdentity({ Body: "hello" }, "");

    expect(ctx.SenderId).toBe("");
    expect(ctx.senderId).toBe("");
    expect(ctx.requesterSenderId).toBe("");
  });
});

describe("resolveRequiredUserAgentIdentity", () => {
  it("propagates resolver failures instead of treating the message as handled", async () => {
    const errLog = vi.fn();
    const traceReporter = vi.fn(() => new Promise<void>(() => {}));

    await expect(resolveRequiredUserAgentIdentity("wechat-user-secret", {
      traceId: "cmtrace_test",
      requestId: "run-test",
      errLog,
      traceReporter,
      resolver: vi.fn(async () => {
        throw new Error("temporary backend failure");
      }),
    })).rejects.toThrow("user Agent identity resolution failed");

    expect(errLog).toHaveBeenCalledOnce();
    expect(traceReporter).toHaveBeenCalledOnce();
    expect(errLog.mock.calls[0]?.[0]).not.toContain("wechat-user-secret");
  });
});


describe("Weixin document context attachment", () => {
  it("archives a received document into the routed agent workspace", async () => {
    const root = await tempDir();
    const workspace = path.join(root, "workspace-user_agent");
    const downloaded = path.join(root, "downloaded.txt");
    await writeFile(downloaded, "这是一份微信文档内容", "utf8");
    const ctx = { Body: "请总结", To: "wechat-user-secret", MediaPath: downloaded, MediaType: "text/plain" };

    await attachParsedWeixinDocumentToContext({
      ctx,
      routedConfig: { agents: { list: [{ id: "user_abc", workspace }] } },
      agentId: "user_abc",
      downloadedFilePath: downloaded,
      filename: "report.txt",
      mime: "text/plain",
      accountId: "account-secret",
      peerId: "wechat-user-secret",
      messageSid: "msg-001",
      limits: { maxFileBytes: 1024, maxTextChars: 80_000, maxImages: 10, maxPdfPages: 10, maxImageEdgePixels: 1600 },
    });

    expect(ctx.Body).toContain("【收到微信文件】");
    expect(ctx.Body).toContain(".openclaw-inbox/weixin/");
    expect(ctx.Body).toContain("这是一份微信文档内容");
    expect(ctx.Body).not.toContain("account-secret");
    expect(ctx.Body).not.toContain("wechat-user-secret");
    expect(ctx.MediaPath).toBeUndefined();
    await expect(readFile(path.join(workspace, ".openclaw-inbox/weixin", new Date().toISOString().slice(0, 10).replaceAll("-", ""), "msg-001", "original", "report.txt"), "utf8")).resolves.toBe("这是一份微信文档内容");
  });

  it("passes embedded Office images to image-capable models", async () => {
    const root = await tempDir();
    const workspace = path.join(root, "workspace-user_agent");
    const downloaded = path.join(root, "downloaded.docx");
    await writeMinimalDocx(downloaded, "带图片的 DOCX", Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    const ctx = { Body: "", To: "wechat-user-secret", MediaPath: downloaded, MediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" };

    await attachParsedWeixinDocumentToContext({
      ctx,
      routedConfig: {
        agents: { defaults: { model: { primary: "provider/model-vision" } }, list: [{ id: "user_abc", workspace }] },
        models: { providers: { provider: { models: [{ id: "model-vision", input: ["text", "image"] }] } } },
      },
      agentId: "user_abc",
      downloadedFilePath: downloaded,
      filename: "demo.docx",
      mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      accountId: "account-secret",
      peerId: "wechat-user-secret",
      messageSid: "msg-002",
      limits: { maxFileBytes: 1024 * 1024, maxTextChars: 80_000, maxImages: 10, maxPdfPages: 10, maxImageEdgePixels: 1600 },
    });

    expect(ctx.Body).toContain("带图片的 DOCX");
    expect(ctx.MediaPaths).toHaveLength(1);
    expect(ctx.MediaTypes).toEqual(["image/png"]);
    expect(ctx.MediaPath).toBe(ctx.MediaPaths?.[0]);
  });

  it("warns and omits embedded images when the selected model is text-only", async () => {
    const root = await tempDir();
    const workspace = path.join(root, "workspace-user_agent");
    const downloaded = path.join(root, "downloaded.docx");
    await writeMinimalDocx(downloaded, "图片说明文字", Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    const ctx = { Body: "", To: "wechat-user-secret", MediaPath: downloaded, MediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" };

    await attachParsedWeixinDocumentToContext({
      ctx,
      routedConfig: {
        agents: { defaults: { model: { primary: "provider/model-text" } }, list: [{ id: "user_abc", workspace }] },
        models: { providers: { provider: { models: [{ id: "model-text", input: ["text"] }] } } },
      },
      agentId: "user_abc",
      downloadedFilePath: downloaded,
      filename: "demo.docx",
      mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      accountId: "account-secret",
      peerId: "wechat-user-secret",
      messageSid: "msg-003",
      limits: { maxFileBytes: 1024 * 1024, maxTextChars: 80_000, maxImages: 10, maxPdfPages: 10, maxImageEdgePixels: 1600 },
    });

    expect(ctx.Body).toContain("当前模型不支持图片理解");
    expect(ctx.MediaPaths).toBeUndefined();
    expect(ctx.MediaPath).toBeUndefined();
  });

  it("keeps a user-facing warning when the routed agent workspace is unavailable", async () => {
    const root = await tempDir();
    const downloaded = path.join(root, "downloaded.txt");
    await writeFile(downloaded, "content", "utf8");
    const ctx = { Body: "请处理", To: "wechat-user-secret", MediaPath: downloaded, MediaType: "text/plain" };

    await attachParsedWeixinDocumentToContext({
      ctx,
      routedConfig: { agents: { list: [] } },
      agentId: "user_missing",
      downloadedFilePath: downloaded,
      filename: "missing.txt",
      mime: "text/plain",
      accountId: "account-secret",
      peerId: "wechat-user-secret",
      messageSid: "msg-004",
    });

    expect(ctx.Body).toContain("当前 Agent 工作区不可用");
    expect(ctx.Body).toContain("missing.txt");
    expect(ctx.MediaPath).toBeUndefined();
  });

  it("detects whether the configured primary model accepts images", () => {
    expect(modelSupportsImagesFromConfig({
      agents: { defaults: { model: { primary: "p/m1" } } },
      models: { providers: { p: { models: [{ id: "m1", input: ["text", "image"] }] } } },
    })).toBe(true);
    expect(modelSupportsImagesFromConfig({
      agents: { defaults: { model: { primary: "p/m2" } } },
      models: { providers: { p: { models: [{ id: "m2", input: ["text"] }] } } },
    })).toBe(false);
  });
});
