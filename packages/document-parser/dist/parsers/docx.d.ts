import type { DocumentParseLimits, ParserResult } from "../types.js";
export declare function parseDocx(filePath: string, outputDir: string, limits: DocumentParseLimits): Promise<ParserResult>;
