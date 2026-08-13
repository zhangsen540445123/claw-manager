import type { DocumentParseLimits, ParserResult } from "../types.js";
export declare function parseWorkbook(filePath: string, limits: DocumentParseLimits): Promise<ParserResult>;
export declare function parseCsv(filePath: string, limits: DocumentParseLimits): Promise<ParserResult>;
