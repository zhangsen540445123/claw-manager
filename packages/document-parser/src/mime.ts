import path from "node:path";

const BY_EXT: Record<string, string> = {
  ".txt": "text/plain",
  ".md": "text/markdown",
  ".json": "application/json",
  ".csv": "text/csv",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ".xls": "application/vnd.ms-excel",
  ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  ".pdf": "application/pdf",
};

export function inferMime(filePath: string, filename?: string, mime?: string): string {
  const explicit = mime?.trim();
  if (explicit) return explicit;
  return BY_EXT[path.extname(filename || filePath).toLowerCase()] ?? "application/octet-stream";
}

export function isTextMime(mime: string, filename: string): boolean {
  const ext = path.extname(filename).toLowerCase();
  return mime.startsWith("text/") || [".txt", ".md", ".json", ".xml", ".log", ".yaml", ".yml"].includes(ext);
}

export function isDocx(mime: string, filename: string): boolean {
  return mime.includes("wordprocessingml.document") || path.extname(filename).toLowerCase() === ".docx";
}

export function isPptx(mime: string, filename: string): boolean {
  return mime.includes("presentationml.presentation") || path.extname(filename).toLowerCase() === ".pptx";
}

export function isSpreadsheet(mime: string, filename: string): boolean {
  const ext = path.extname(filename).toLowerCase();
  return mime.includes("spreadsheet") || mime.includes("ms-excel") || ext === ".xlsx" || ext === ".xls";
}

export function isCsv(mime: string, filename: string): boolean {
  return mime === "text/csv" || path.extname(filename).toLowerCase() === ".csv";
}

export function isPdf(mime: string, filename: string): boolean {
  return mime === "application/pdf" || path.extname(filename).toLowerCase() === ".pdf";
}

export function mimeFromImagePath(filePath: string): string {
  switch (path.extname(filePath).toLowerCase()) {
    case ".jpg":
    case ".jpeg": return "image/jpeg";
    case ".gif": return "image/gif";
    case ".webp": return "image/webp";
    case ".bmp": return "image/bmp";
    case ".svg": return "image/svg+xml";
    case ".png":
    default: return "image/png";
  }
}
