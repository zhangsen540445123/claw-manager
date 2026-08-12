import type { ParserResult } from "../types.js";
export declare function parseWorkbook(filePath: string): Promise<ParserResult>;
export declare function parseCsv(filePath: string): Promise<ParserResult>;
