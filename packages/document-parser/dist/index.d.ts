import type { ParseDocumentInput, ParsedDocument, WorkerParseDocumentOptions } from "./types.js";
export { DEFAULT_DOCUMENT_PARSE_LIMITS } from "./limits.js";
export type { DocumentKind, DocumentParseLimits, DocumentParseStatus, ParseDocumentInput, ParsedDocument, ParsedDocumentImage, WorkerParseDocumentOptions } from "./types.js";
export declare function parseDocument(input: ParseDocumentInput): Promise<ParsedDocument>;
export declare function parseDocumentInWorker(input: ParseDocumentInput, options?: WorkerParseDocumentOptions): Promise<ParsedDocument>;
