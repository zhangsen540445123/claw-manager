export const DEFAULT_DOCUMENT_PARSE_LIMITS = {
    maxFileBytes: 20 * 1024 * 1024,
    maxTextChars: 80_000,
    maxImages: 10,
    maxPdfPages: 10,
    maxImageEdgePixels: 1600,
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