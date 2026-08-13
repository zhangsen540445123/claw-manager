import { readFile } from "node:fs/promises";
import JSZip from "jszip";

import { extractOfficeImages, inspectZipEntries } from "../image.js";
import { decodeXmlText } from "../text.js";
import type { DocumentParseLimits, ParserResult } from "../types.js";

export async function parsePptx(filePath: string, outputDir: string, limits: DocumentParseLimits): Promise<ParserResult> {
  const zip = await JSZip.loadAsync(await readFile(filePath));
  const inspection = inspectZipEntries(zip, limits);
  const warnings = [...inspection.warnings];
  const limitsHit = [...inspection.limitsHit];
  const slideNames = inspection.safeForXml
    ? Object.keys(zip.files)
      .filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    : [];
  const parts: string[] = [];
  for (const name of slideNames) {
    const entry = zip.files[name]!;
    const xml = await entry.async("string");
    if (Buffer.byteLength(xml, "utf8") > limits.maxOfficeXmlBytes) {
      warnings.push(`幻灯片 ${name} XML 超过 ${limits.maxOfficeXmlBytes} 字节，已跳过。`);
      limitsHit.push("maxOfficeXmlBytes");
      continue;
    }
    const texts = Array.from(xml.matchAll(/<a:t[^>]*>([\s\S]*?)<\/a:t>/g)).map((match) => decodeXmlText(match[1] ?? ""));
    if (texts.length) parts.push(`## ${name}\n${texts.filter(Boolean).join("\n")}`);
  }
  if (!inspection.safeForXml) warnings.push("PPTX 内部结构过大，已跳过正文解析以避免内存过高。");

  if (!inspection.safeForMedia || limits.maxImages <= 0 || limits.maxOfficeMediaFiles <= 0) {
    if (!inspection.safeForMedia) warnings.push("PPTX 内部图片较大或较多，已跳过图片提取。");
    return { text: parts.join("\n\n"), images: [], imageCountExceeded: true, warnings: [...new Set(warnings)], limitsHit: [...new Set(limitsHit)] };
  }

  const extracted = await extractOfficeImages({ zip, mediaPrefix: "ppt/media/", outputDir, limits });
  return {
    text: parts.join("\n\n"),
    images: extracted.images,
    imageCountExceeded: extracted.imageCountExceeded,
    warnings: [...new Set([...warnings, ...extracted.warnings])],
    limitsHit: [...new Set([...limitsHit, ...extracted.limitsHit])],
  };
}
