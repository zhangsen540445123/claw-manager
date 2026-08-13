export const DEFAULT_DOCUMENT_PARSE_LIMITS = {
    maxFileBytes: 20 * 1024 * 1024,
    maxTextChars: 80_000,
    maxImages: 10,
    maxPdfPages: 10,
    maxImageEdgePixels: 1600,
    maxZipEntries: 4_000,
    maxZipEntryCompressedBytes: 20 * 1024 * 1024,
    maxZipEntryUncompressedBytes: 80 * 1024 * 1024,
    maxZipTotalUncompressedBytes: 180 * 1024 * 1024,
    maxOfficeMediaFiles: 10,
    maxOfficeMediaCompressedBytes: 8 * 1024 * 1024,
    maxOfficeMediaUncompressedBytes: 24 * 1024 * 1024,
    maxOfficeXmlBytes: 12 * 1024 * 1024,
    maxWorkbookSheets: 20,
    maxWorkbookRowsPerSheet: 2_000,
    maxWorkbookCells: 50_000,
    maxPlainTextBytes: 2 * 1024 * 1024,
    maxCsvBytes: 4 * 1024 * 1024,
};
export function mergeLimits(limits) {
    return { ...DEFAULT_DOCUMENT_PARSE_LIMITS, ...(limits ?? {}) };
}
export function formatBytes(bytes) {
    if (bytes < 1024)
        return `${bytes}B`;
    if (bytes < 1024 * 1024)
        return `${Math.round(bytes / 1024)}KB`;
    return `${Math.round(bytes / 1024 / 1024)}MB`;
}
//# sourceMappingURL=limits.js.map