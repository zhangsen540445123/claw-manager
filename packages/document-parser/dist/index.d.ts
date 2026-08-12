import type { ParseDocumentInput, ParsedDocument } from "./types.js";
export { DEFAULT_DOCUMENT_PARSE_LIMITS } from "./limits.js";
export type { DocumentKind, DocumentParseLimits, ParseDocumentInput, ParsedDocument, ParsedDocumentImage } from "./types.js";
export declare function parseDocument(input: ParseDocumentInput): Promise<ParsedDocument>;
