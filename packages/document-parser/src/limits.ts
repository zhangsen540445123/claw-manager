import type { DocumentParseLimits } from "./types.js";

export const DEFAULT_DOCUMENT_PARSE_LIMITS: DocumentParseLimits = {
  maxFileBytes: 20 * 1024 * 1024,
  maxTextChars: 80_000,
  maxImages: 10,
  maxPdfPages: 10,
  maxImageEdgePixels: 1600,
};

export function mergeLimits(limits?: Partial<DocumentParseLimits>): DocumentParseLimits {
  return { ...DEFAULT_DOCUMENT_PARSE_LIMITS, ...(limits ?? {}) };
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${Math.round(bytes / 1024 / 1024)}MB`;
}
