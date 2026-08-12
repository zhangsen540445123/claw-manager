import type { DocumentParseLimits, ParserResult } from "../types.js";
export declare function parsePdf(filePath: string, _outputDir: string, limits: DocumentParseLimits): Promise<ParserResult>;
