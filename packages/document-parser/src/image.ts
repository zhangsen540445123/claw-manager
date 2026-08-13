import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import type JSZip from "jszip";

import { formatBytes } from "./limits.js";
import { mimeFromImagePath } from "./mime.js";
import type { DocumentParseLimits, ParsedDocumentImage } from "./types.js";

type ZipEntryWithUnsafeMetadata = JSZip.JSZipObject & {
  _data?: {
    compressedSize?: number;
    uncompressedSize?: number;
  };
};

function zipEntryCompressedSize(entry: JSZip.JSZipObject): number | undefined {
  const value = (entry as ZipEntryWithUnsafeMetadata)._data?.compressedSize;
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function zipEntryUncompressedSize(entry: JSZip.JSZipObject): number | undefined {
  const value = (entry as ZipEntryWithUnsafeMetadata)._data?.uncompressedSize;
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export function inspectZipEntries(zip: JSZip, limits: DocumentParseLimits): { warnings: string[]; limitsHit: string[]; safeForMedia: boolean; safeForXml: boolean } {
  const warnings: string[] = [];
  const limitsHit: string[] = [];
  const entries = Object.values(zip.files).filter((entry) => !entry.dir);
  let totalUncompressed = 0;
  let safeForMedia = true;
  let safeForXml = true;

  if (entries.length > limits.maxZipEntries) {
    limitsHit.push("maxZipEntries");
    warnings.push(`Office 文档内部文件数量超过 ${limits.maxZipEntries} 个，已跳过深度解析以避免内存过高。`);
    safeForMedia = false;
    safeForXml = false;
  }

  for (const entry of entries) {
    const compressed = zipEntryCompressedSize(entry);
    const uncompressed = zipEntryUncompressedSize(entry);
    if (typeof compressed === "number" && compressed > limits.maxZipEntryCompressedBytes) {
      limitsHit.push("maxZipEntryCompressedBytes");
      warnings.push(`Office 文档内部文件 ${entry.name} 压缩后超过 ${formatBytes(limits.maxZipEntryCompressedBytes)}，已跳过部分内容。`);
      safeForMedia = false;
    }
    if (typeof uncompressed === "number") {
      totalUncompressed += uncompressed;
      if (uncompressed > limits.maxZipEntryUncompressedBytes) {
        limitsHit.push("maxZipEntryUncompressedBytes");
        warnings.push(`Office 文档内部文件 ${entry.name} 解压后超过 ${formatBytes(limits.maxZipEntryUncompressedBytes)}，已跳过部分内容。`);
        safeForMedia = false;
        if (entry.name.endsWith(".xml")) safeForXml = false;
      }
      if (entry.name.endsWith(".xml") && uncompressed > limits.maxOfficeXmlBytes) {
        limitsHit.push("maxOfficeXmlBytes");
        warnings.push(`Office 文档 XML 内容超过 ${formatBytes(limits.maxOfficeXmlBytes)}，已跳过部分文字。`);
        safeForXml = false;
      }
    }
  }

  if (totalUncompressed > limits.maxZipTotalUncompressedBytes) {
    limitsHit.push("maxZipTotalUncompressedBytes");
    warnings.push(`Office 文档解压后总大小超过 ${formatBytes(limits.maxZipTotalUncompressedBytes)}，已跳过图片或部分内容。`);
    safeForMedia = false;
  }

  return { warnings: [...new Set(warnings)], limitsHit: [...new Set(limitsHit)], safeForMedia, safeForXml };
}

export async function extractOfficeImages(params: {
  zip: JSZip;
  mediaPrefix: string;
  outputDir: string;
  limits: DocumentParseLimits;
  source?: ParsedDocumentImage["source"];
}): Promise<{ images: ParsedDocumentImage[]; imageCountExceeded: boolean; warnings: string[]; limitsHit: string[] }> {
  const imagesDir = path.join(params.outputDir, "images");
  const mediaFiles = Object.keys(params.zip.files)
    .filter((name) => name.startsWith(params.mediaPrefix) && !params.zip.files[name]?.dir)
    .sort();
  const selected = mediaFiles.slice(0, Math.min(params.limits.maxImages, params.limits.maxOfficeMediaFiles));
  const images: ParsedDocumentImage[] = [];
  const warnings: string[] = [];
  const limitsHit: string[] = [];
  let imageCountExceeded = mediaFiles.length > selected.length;

  if (imageCountExceeded) {
    limitsHit.push(mediaFiles.length > params.limits.maxOfficeMediaFiles ? "maxOfficeMediaFiles" : "maxImages");
  }

  if (selected.length > 0) await mkdir(imagesDir, { recursive: true });
  for (const zipPath of selected) {
    const entry = params.zip.files[zipPath];
    if (!entry) continue;
    const compressed = zipEntryCompressedSize(entry);
    const uncompressed = zipEntryUncompressedSize(entry);
    if (typeof compressed === "number" && compressed > params.limits.maxOfficeMediaCompressedBytes) {
      warnings.push(`图片 ${path.basename(zipPath)} 压缩后超过 ${formatBytes(params.limits.maxOfficeMediaCompressedBytes)}，已跳过。`);
      limitsHit.push("maxOfficeMediaCompressedBytes");
      imageCountExceeded = true;
      continue;
    }
    if (typeof uncompressed === "number" && uncompressed > params.limits.maxOfficeMediaUncompressedBytes) {
      warnings.push(`图片 ${path.basename(zipPath)} 解压后超过 ${formatBytes(params.limits.maxOfficeMediaUncompressedBytes)}，已跳过。`);
      limitsHit.push("maxOfficeMediaUncompressedBytes");
      imageCountExceeded = true;
      continue;
    }

    const buffer = await entry.async("nodebuffer");
    if (buffer.byteLength > params.limits.maxOfficeMediaUncompressedBytes) {
      warnings.push(`图片 ${path.basename(zipPath)} 超过 ${formatBytes(params.limits.maxOfficeMediaUncompressedBytes)}，已跳过。`);
      limitsHit.push("maxOfficeMediaUncompressedBytes");
      imageCountExceeded = true;
      continue;
    }
    const ext = path.extname(zipPath) || ".bin";
    const filename = `${String(images.length + 1).padStart(3, "0")}${ext}`;
    const outPath = path.join(imagesDir, filename);
    await writeFile(outPath, buffer);
    images.push({
      path: outPath,
      mime: mimeFromImagePath(outPath),
      label: filename,
      source: params.source ?? "office_embedded_image",
    });
  }
  return { images, imageCountExceeded, warnings, limitsHit: [...new Set(limitsHit)] };
}
