import type JSZip from "jszip";
import type { DocumentParseLimits, ParsedDocumentImage } from "./types.js";
export declare function inspectZipEntries(zip: JSZip, limits: DocumentParseLimits): {
    warnings: string[];
    limitsHit: string[];
    safeForMedia: boolean;
    safeForXml: boolean;
};
export declare function extractOfficeImages(params: {
    zip: JSZip;
    mediaPrefix: string;
    outputDir: string;
    limits: DocumentParseLimits;
    source?: ParsedDocumentImage["source"];
}): Promise<{
    images: ParsedDocumentImage[];
    imageCountExceeded: boolean;
    warnings: string[];
    limitsHit: string[];
}>;
