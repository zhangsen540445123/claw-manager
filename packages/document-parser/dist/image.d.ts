import JSZip from "jszip";
import type { DocumentParseLimits, ParsedDocumentImage } from "./types.js";
export declare function extractOfficeImages(params: {
    zip: JSZip;
    mediaPrefix: string;
    outputDir: string;
    limits: DocumentParseLimits;
    source?: ParsedDocumentImage["source"];
}): Promise<{
    images: ParsedDocumentImage[];
    imageCountExceeded: boolean;
}>;
