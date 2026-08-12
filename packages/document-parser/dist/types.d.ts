export type DocumentKind = "text" | "text_with_images" | "visual_only" | "unsupported";
export type ParsedDocumentImage = {
    path: string;
    mime: string;
    label: string;
    source: "pdf_page" | "office_embedded_image";
};
export type DocumentParseLimits = {
    maxFileBytes: number;
    maxTextChars: number;
    maxImages: number;
    maxPdfPages: number;
    maxImageEdgePixels: number;
};
export type ParsedDocument = {
    kind: DocumentKind;
    filename: string;
    mime: string;
    sizeBytes: number;
    text: string;
    textChars: number;
    textTruncated: boolean;
    images: ParsedDocumentImage[];
    warnings: string[];
    limits: {
        fileSizeExceeded: boolean;
        textTruncated: boolean;
        imageCountExceeded: boolean;
        pdfPageLimitExceeded: boolean;
        unsupportedImages: boolean;
    };
};
export type ParseDocumentInput = {
    filePath: string;
    filename?: string;
    mime?: string;
    outputDir: string;
    limits?: Partial<DocumentParseLimits>;
};
export type ParserResult = {
    text?: string;
    images?: ParsedDocumentImage[];
    warnings?: string[];
    unsupportedImages?: boolean;
    imageCountExceeded?: boolean;
    pdfPageLimitExceeded?: boolean;
};
