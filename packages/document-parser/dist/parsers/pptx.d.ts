import type { DocumentParseLimits, ParserResult } from "../types.js";
export declare function parsePptx(filePath: string, outputDir: string, limits: DocumentParseLimits): Promise<ParserResult>;
