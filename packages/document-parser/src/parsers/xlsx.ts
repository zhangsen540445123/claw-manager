import * as XLSX from "xlsx";
import { open } from "node:fs/promises";
import { formatBytes } from "../limits.js";
import type { DocumentParseLimits, ParserResult } from "../types.js";

function decodeCell(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

function sheetRange(sheet: XLSX.WorkSheet): XLSX.Range | undefined {
  const ref = sheet["!ref"];
  if (typeof ref !== "string" || !ref.trim()) return undefined;
  try {
    return XLSX.utils.decode_range(ref);
  } catch {
    return undefined;
  }
}

export async function parseWorkbook(filePath: string, limits: DocumentParseLimits): Promise<ParserResult> {
  const workbook = XLSX.readFile(filePath, { cellDates: true, dense: false });
  const parts: string[] = [];
  const warnings: string[] = [];
  const limitsHit: string[] = [];
  const sheetNames = workbook.SheetNames.slice(0, limits.maxWorkbookSheets);
  if (workbook.SheetNames.length > sheetNames.length) {
    warnings.push(`工作表较多，仅读取前 ${limits.maxWorkbookSheets} 个。`);
    limitsHit.push("maxWorkbookSheets");
  }

  let totalCells = 0;
  for (const sheetName of sheetNames) {
    const sheet = workbook.Sheets[sheetName];
    if (!sheet) continue;
    const range = sheetRange(sheet);
    if (!range) {
      parts.push(`## ${sheetName}\n`);
      continue;
    }
    const maxRow = Math.min(range.e.r, range.s.r + Math.max(0, limits.maxWorkbookRowsPerSheet - 1));
    if (range.e.r > maxRow) {
      warnings.push(`工作表 ${sheetName} 行数较多，仅读取前 ${limits.maxWorkbookRowsPerSheet} 行。`);
      limitsHit.push("maxWorkbookRowsPerSheet");
    }
    const rows: string[] = [];
    for (let r = range.s.r; r <= maxRow; r += 1) {
      const values: string[] = [];
      for (let c = range.s.c; c <= range.e.c; c += 1) {
        if (totalCells >= limits.maxWorkbookCells) break;
        const address = XLSX.utils.encode_cell({ r, c });
        values.push(decodeCell((sheet[address] as XLSX.CellObject | undefined)?.v));
        totalCells += 1;
      }
      rows.push(values.join("\t"));
      if (totalCells >= limits.maxWorkbookCells) {
        warnings.push(`表格单元格较多，仅读取前 ${limits.maxWorkbookCells} 个单元格。`);
        limitsHit.push("maxWorkbookCells");
        break;
      }
    }
    parts.push(`## ${sheetName}\n${rows.join("\n")}`);
    if (totalCells >= limits.maxWorkbookCells) break;
  }
  return { text: parts.join("\n\n"), warnings: [...new Set(warnings)], limitsHit: [...new Set(limitsHit)] };
}

async function readPrefix(filePath: string, maxBytes: number): Promise<{ buffer: Buffer; truncated: boolean }> {
  const handle = await open(filePath, "r");
  try {
    const buffer = Buffer.allocUnsafe(Math.max(0, maxBytes));
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    const stat = await handle.stat();
    return { buffer: buffer.subarray(0, bytesRead), truncated: stat.size > maxBytes };
  } finally {
    await handle.close();
  }
}

export async function parseCsv(filePath: string, limits: DocumentParseLimits): Promise<ParserResult> {
  const { buffer, truncated } = await readPrefix(filePath, limits.maxCsvBytes);
  const workbook = XLSX.read(buffer, { type: "buffer", raw: false, dense: false });
  const first = workbook.SheetNames[0];
  const warnings: string[] = [];
  const limitsHit: string[] = [];
  if (truncated) {
    warnings.push(`CSV 文件较大，仅读取前 ${formatBytes(limits.maxCsvBytes)}。`);
    limitsHit.push("maxCsvBytes");
  }
  if (!first || !workbook.Sheets[first]) return { text: "", warnings, limitsHit };
  const parsed = await parseWorkbookFromLoaded(workbook, limits);
  return { ...parsed, warnings: [...new Set([...warnings, ...(parsed.warnings ?? [])])], limitsHit: [...new Set([...limitsHit, ...(parsed.limitsHit ?? [])])] };
}

async function parseWorkbookFromLoaded(workbook: XLSX.WorkBook, limits: DocumentParseLimits): Promise<ParserResult> {
  const parts: string[] = [];
  const warnings: string[] = [];
  const limitsHit: string[] = [];
  const sheetNames = workbook.SheetNames.slice(0, 1);
  let totalCells = 0;
  for (const sheetName of sheetNames) {
    const sheet = workbook.Sheets[sheetName];
    const range = sheet ? sheetRange(sheet) : undefined;
    if (!range) continue;
    const maxRow = Math.min(range.e.r, range.s.r + Math.max(0, limits.maxWorkbookRowsPerSheet - 1));
    if (range.e.r > maxRow) {
      warnings.push(`CSV 行数较多，仅读取前 ${limits.maxWorkbookRowsPerSheet} 行。`);
      limitsHit.push("maxWorkbookRowsPerSheet");
    }
    const rows: string[] = [];
    for (let r = range.s.r; r <= maxRow; r += 1) {
      const values: string[] = [];
      for (let c = range.s.c; c <= range.e.c; c += 1) {
        if (totalCells >= limits.maxWorkbookCells) break;
        values.push(decodeCell((sheet![XLSX.utils.encode_cell({ r, c })] as XLSX.CellObject | undefined)?.v));
        totalCells += 1;
      }
      rows.push(values.join("\t"));
      if (totalCells >= limits.maxWorkbookCells) {
        warnings.push(`CSV 单元格较多，仅读取前 ${limits.maxWorkbookCells} 个单元格。`);
        limitsHit.push("maxWorkbookCells");
        break;
      }
    }
    parts.push(rows.join("\n"));
  }
  return { text: parts.join("\n\n"), warnings, limitsHit };
}
