export type DocumentKind = "text" | "text_with_images" | "visual_only" | "unsupported";

export type DocumentParseStatus = "success" | "partial" | "failed" | "timeout" | "worker_oom" | "unsupported";

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
  maxZipEntries: number;
  maxZipEntryCompressedBytes: number;
  maxZipEntryUncompressedBytes: number;
  maxZipTotalUncompressedBytes: number;
  maxOfficeMediaFiles: number;
  maxOfficeMediaCompressedBytes: number;
  maxOfficeMediaUncompressedBytes: number;
  maxOfficeXmlBytes: number;
  maxWorkbookSheets: number;
  maxWorkbookRowsPerSheet: number;
  maxWorkbookCells: number;
  maxPlainTextBytes: number;
  maxCsvBytes: number;
};

export type ParsedDocument = {
  kind: DocumentKind;
  status: DocumentParseStatus;
  filename: string;
  mime: string;
  sizeBytes: number;
  text: string;
  textChars: number;
  textTruncated: boolean;
  images: ParsedDocumentImage[];
  warnings: string[];
  limitsHit: string[];
  durationMs?: number;
  workerExitCode?: number | null;
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
  limitsHit?: string[];
  unsupportedImages?: boolean;
  imageCountExceeded?: boolean;
  pdfPageLimitExceeded?: boolean;
  unsupported?: boolean;
};

export type WorkerParseDocumentOptions = {
  timeoutMs?: number;
  maxOldSpaceMb?: number;
};
