import { mkdir, readFile, stat } from "node:fs/promises";
import path from "node:path";
import { formatBytes, mergeLimits } from "./limits.js";
import { inferMime, isCsv, isDocx, isPdf, isPptx, isSpreadsheet, isTextMime } from "./mime.js";
import { parseDocx } from "./parsers/docx.js";
import { parsePdf } from "./parsers/pdf.js";
import { parsePptx } from "./parsers/pptx.js";
import { parseCsv, parseWorkbook } from "./parsers/xlsx.js";
import { truncateText } from "./text.js";
export { DEFAULT_DOCUMENT_PARSE_LIMITS } from "./limits.js";
function emptyLimitState() {
    return {
        fileSizeExceeded: false,
        textTruncated: false,
        imageCountExceeded: false,
        pdfPageLimitExceeded: false,
        unsupportedImages: false,
    };
}
function deriveKind(text, imageCount, unsupported = false) {
    if (unsupported && !text && imageCount === 0)
        return "unsupported";
    if (text && imageCount > 0)
        return "text_with_images";
    if (imageCount > 0)
        return "visual_only";
    if (text)
        return "text";
    return unsupported ? "unsupported" : "text";
}
function addGenericWarnings(warnings, result, limits) {
    if (result.imageCountExceeded)
        warnings.push(`文档包含较多图片，仅处理前 ${limits.maxImages} 张。`);
    if (result.pdfPageLimitExceeded && !warnings.some((w) => w.includes("PDF 页数较多"))) {
        warnings.push(`PDF 页数较多，仅处理前 ${limits.maxPdfPages} 页。`);
    }
    if (result.unsupportedImages && !warnings.some((w) => w.includes("图片") || w.includes("视觉"))) {
        warnings.push("当前运行环境未能生成图片视觉输入，已仅使用文字内容。");
    }
}
async function parseByType(filePath, filename, mime, outputDir, limits) {
    if (isCsv(mime, filename))
        return parseCsv(filePath);
    if (isTextMime(mime, filename))
        return { text: await readFile(filePath, "utf8") };
    if (isDocx(mime, filename))
        return parseDocx(filePath, outputDir, limits);
    if (isSpreadsheet(mime, filename))
        return parseWorkbook(filePath);
    if (isPptx(mime, filename))
        return parsePptx(filePath, outputDir, limits);
    if (isPdf(mime, filename))
        return parsePdf(filePath, outputDir, limits);
    return { unsupported: true, warnings: ["文件格式暂不支持，无法读取内容。"] };
}
export async function parseDocument(input) {
    const filename = input.filename?.trim() || path.basename(input.filePath);
    const mime = inferMime(input.filePath, filename, input.mime);
    const limits = mergeLimits(input.limits);
    const sizeBytes = (await stat(input.filePath)).size;
    const limitState = emptyLimitState();
    if (sizeBytes > limits.maxFileBytes) {
        limitState.fileSizeExceeded = true;
        return {
            kind: "unsupported",
            filename,
            mime,
            sizeBytes,
            text: "",
            textChars: 0,
            textTruncated: false,
            images: [],
            warnings: [`文件超过 ${formatBytes(limits.maxFileBytes)}，未解析。`],
            limits: limitState,
        };
    }
    await mkdir(input.outputDir, { recursive: true });
    try {
        const result = await parseByType(input.filePath, filename, mime, input.outputDir, limits);
        const truncated = truncateText(result.text ?? "", limits.maxTextChars);
        const warnings = [...(result.warnings ?? [])];
        if (truncated.truncated)
            warnings.push(`文档文字较长，已截取前 ${limits.maxTextChars} 字符。`);
        addGenericWarnings(warnings, result, limits);
        limitState.textTruncated = truncated.truncated;
        limitState.imageCountExceeded = result.imageCountExceeded === true;
        limitState.pdfPageLimitExceeded = result.pdfPageLimitExceeded === true;
        limitState.unsupportedImages = result.unsupportedImages === true;
        return {
            kind: deriveKind(truncated.text, result.images?.length ?? 0, result.unsupported === true),
            filename,
            mime,
            sizeBytes,
            text: truncated.text,
            textChars: truncated.text.length,
            textTruncated: truncated.truncated,
            images: result.images ?? [],
            warnings,
            limits: limitState,
        };
    }
    catch (error) {
        return {
            kind: "unsupported",
            filename,
            mime,
            sizeBytes,
            text: "",
            textChars: 0,
            textTruncated: false,
            images: [],
            warnings: [`文件损坏、加密或格式不支持，无法读取内容。${String(error.message ?? error).slice(0, 160)}`],
            limits: limitState,
        };
    }
}
//# sourceMappingURL=index.js.map