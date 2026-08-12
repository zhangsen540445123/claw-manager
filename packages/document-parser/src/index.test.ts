import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import JSZip from "jszip";
import { describe, expect, it, afterEach } from "vitest";

import { parseDocument, DEFAULT_DOCUMENT_PARSE_LIMITS } from "./index.js";

const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "document-parser-"));
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
  const data = await zip.generateAsync({ type: "nodebuffer" });
  await writeFile(filePath, data);
}

async function writeMinimalPptx(filePath: string, text: string, imageBytes?: Buffer): Promise<void> {
  const zip = new JSZip();
  zip.file("ppt/slides/slide1.xml", `<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>${text}</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>`);
  if (imageBytes) zip.file("ppt/media/image1.png", imageBytes);
  const data = await zip.generateAsync({ type: "nodebuffer" });
  await writeFile(filePath, data);
}

describe("parseDocument", () => {
  it("reads text files and reports truncation", async () => {
    const dir = await tempDir();
    const file = path.join(dir, "long.txt");
    await writeFile(file, "abcdef", "utf8");

    const parsed = await parseDocument({ filePath: file, outputDir: path.join(dir, "out"), limits: { maxTextChars: 3 } });

    expect(parsed.kind).toBe("text");
    expect(parsed.text).toBe("abc");
    expect(parsed.textTruncated).toBe(true);
    expect(parsed.warnings).toContain("文档文字较长，已截取前 3 字符。");
  });

  it("extracts docx text and embedded images", async () => {
    const dir = await tempDir();
    const file = path.join(dir, "demo.docx");
    await writeMinimalDocx(file, "Hello DOCX", Buffer.from([0x89, 0x50, 0x4e, 0x47]));

    const parsed = await parseDocument({ filePath: file, outputDir: path.join(dir, "out") });

    expect(parsed.kind).toBe("text_with_images");
    expect(parsed.text).toContain("Hello DOCX");
    expect(parsed.images).toHaveLength(1);
    await expect(readFile(parsed.images[0]!.path)).resolves.toEqual(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  });

  it("extracts pptx slide text and embedded images", async () => {
    const dir = await tempDir();
    const file = path.join(dir, "demo.pptx");
    await writeMinimalPptx(file, "Slide text", Buffer.from([1, 2, 3]));

    const parsed = await parseDocument({ filePath: file, outputDir: path.join(dir, "out") });

    expect(parsed.text).toContain("Slide text");
    expect(parsed.images).toHaveLength(1);
  });

  it("reads csv files", async () => {
    const dir = await tempDir();
    const file = path.join(dir, "table.csv");
    await writeFile(file, "name,age\nAlice,18\n", "utf8");

    const parsed = await parseDocument({ filePath: file, outputDir: path.join(dir, "out") });

    expect(parsed.text).toContain("name\tage");
    expect(parsed.text).toContain("Alice\t18");
  });

  it("refuses oversized files with a user-facing warning", async () => {
    const dir = await tempDir();
    const file = path.join(dir, "big.txt");
    await writeFile(file, "123456", "utf8");

    const parsed = await parseDocument({ filePath: file, outputDir: path.join(dir, "out"), limits: { maxFileBytes: 5 } });

    expect(parsed.kind).toBe("unsupported");
    expect(parsed.text).toBe("");
    expect(parsed.limits.fileSizeExceeded).toBe(true);
    expect(parsed.warnings).toContain("文件超过 5B，未解析。");
  });

  it("has safe conservative defaults", () => {
    expect(DEFAULT_DOCUMENT_PARSE_LIMITS.maxFileBytes).toBe(20 * 1024 * 1024);
    expect(DEFAULT_DOCUMENT_PARSE_LIMITS.maxTextChars).toBe(80_000);
    expect(DEFAULT_DOCUMENT_PARSE_LIMITS.maxImages).toBe(10);
    expect(DEFAULT_DOCUMENT_PARSE_LIMITS.maxPdfPages).toBe(10);
  });
});
