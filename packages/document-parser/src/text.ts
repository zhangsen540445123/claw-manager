import { open } from "node:fs/promises";
import { formatBytes } from "./limits.js";
import type { DocumentParseLimits, ParserResult } from "./types.js";

export function truncateText(text: string, maxChars: number): { text: string; truncated: boolean } {
  if (text.length <= maxChars) return { text, truncated: false };
  return { text: text.slice(0, Math.max(0, maxChars)), truncated: true };
}

export async function parsePlainText(filePath: string, limits: DocumentParseLimits): Promise<ParserResult> {
  const handle = await open(filePath, "r");
  try {
    const buffer = Buffer.allocUnsafe(Math.max(0, limits.maxPlainTextBytes));
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    const stat = await handle.stat();
    const warnings: string[] = [];
    const limitsHit: string[] = [];
    if (stat.size > limits.maxPlainTextBytes) {
      warnings.push(`文本文件较大，仅读取前 ${formatBytes(limits.maxPlainTextBytes)}。`);
      limitsHit.push("maxPlainTextBytes");
    }
    return { text: buffer.subarray(0, bytesRead).toString("utf8"), warnings, limitsHit };
  } finally {
    await handle.close();
  }
}

export function decodeXmlText(input: string): string {
  return input
    .replaceAll(/<[^>]+>/g, " ")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll(/\s+/g, " ")
    .trim();
}
