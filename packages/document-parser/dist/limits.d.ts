import type { DocumentParseLimits } from "./types.js";
export declare const DEFAULT_DOCUMENT_PARSE_LIMITS: DocumentParseLimits;
export declare function mergeLimits(limits?: Partial<DocumentParseLimits>): DocumentParseLimits;
export declare function formatBytes(bytes: number): string;
