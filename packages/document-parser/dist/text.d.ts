import type { DocumentParseLimits, ParserResult } from "./types.js";
export declare function truncateText(text: string, maxChars: number): {
    text: string;
    truncated: boolean;
};
export declare function parsePlainText(filePath: string, limits: DocumentParseLimits): Promise<ParserResult>;
export declare function decodeXmlText(input: string): string;
