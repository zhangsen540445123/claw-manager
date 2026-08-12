import * as XLSX from "xlsx";
import type { ParserResult } from "../types.js";

export async function parseWorkbook(filePath: string): Promise<ParserResult> {
  const workbook = XLSX.readFile(filePath, { cellDates: true });
  const parts: string[] = [];
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    if (!sheet) continue;
    const csv = XLSX.utils.sheet_to_csv(sheet, { FS: "\t" });
    parts.push(`## ${sheetName}\n${csv}`);
  }
  return { text: parts.join("\n\n") };
}

export async function parseCsv(filePath: string): Promise<ParserResult> {
  const workbook = XLSX.readFile(filePath, { type: "file", raw: false });
  const first = workbook.SheetNames[0];
  if (!first || !workbook.Sheets[first]) return { text: "" };
  return { text: XLSX.utils.sheet_to_csv(workbook.Sheets[first], { FS: "\t" }) };
}
