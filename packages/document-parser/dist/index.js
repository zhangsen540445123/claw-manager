import { mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import { formatBytes, mergeLimits } from "./limits.js";
import { inferMime, isCsv, isDocx, isPdf, isPptx, isSpreadsheet, isTextMime } from "./mime.js";
import { parseDocx } from "./parsers/docx.js";
import { parsePdf } from "./parsers/pdf.js";
import { parsePptx } from "./parsers/pptx.js";
import { parseCsv, parseWorkbook } from "./parsers/xlsx.js";
import { parsePlainText, truncateText } from "./text.js";
export { DEFAULT_DOCUMENT_PARSE_LIMITS } from "./limits.js";
const DEFAULT_WORKER_TIMEOUT_MS = 60_000;
const DEFAULT_WORKER_OLD_SPACE_MB = 768;
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
function deriveStatus(parsed) {
    if (parsed.kind === "unsupported")
        return "unsupported";
    if (parsed.textTruncated || parsed.warnings.length > 0 || parsed.limitsHit.length > 0 || parsed.limits.imageCountExceeded || parsed.limits.pdfPageLimitExceeded)
        return "partial";
    return "success";
}
function addGenericWarnings(warnings, result, limits) {
    if (result.imageCountExceeded)
        warnings.push(`文档包含较多图片，仅处理前 ${Math.min(limits.maxImages, limits.maxOfficeMediaFiles)} 张。`);
    if (result.pdfPageLimitExceeded && !warnings.some((w) => w.includes("PDF 页数较多"))) {
        warnings.push(`PDF 页数较多，仅处理前 ${limits.maxPdfPages} 页。`);
    }
    if (result.unsupportedImages && !warnings.some((w) => w.includes("图片") || w.includes("视觉"))) {
        warnings.push("当前运行环境未能生成图片视觉输入，已仅使用文字内容。");
    }
}
async function parseByType(filePath, filename, mime, outputDir, limits) {
    if (isCsv(mime, filename))
        return parseCsv(filePath, limits);
    if (isTextMime(mime, filename))
        return parsePlainText(filePath, limits);
    if (isDocx(mime, filename))
        return parseDocx(filePath, outputDir, limits);
    if (isSpreadsheet(mime, filename))
        return parseWorkbook(filePath, limits);
    if (isPptx(mime, filename))
        return parsePptx(filePath, outputDir, limits);
    if (isPdf(mime, filename))
        return parsePdf(filePath, outputDir, limits);
    return { unsupported: true, warnings: ["文件格式暂不支持，无法读取内容。"], limitsHit: ["unsupportedMime"] };
}
export async function parseDocument(input) {
    const started = Date.now();
    const filename = input.filename?.trim() || path.basename(input.filePath);
    const mime = inferMime(input.filePath, filename, input.mime);
    const limits = mergeLimits(input.limits);
    const sizeBytes = (await stat(input.filePath)).size;
    const limitState = emptyLimitState();
    if (sizeBytes > limits.maxFileBytes) {
        limitState.fileSizeExceeded = true;
        return {
            kind: "unsupported",
            status: "failed",
            filename,
            mime,
            sizeBytes,
            text: "",
            textChars: 0,
            textTruncated: false,
            images: [],
            warnings: [`文件超过 ${formatBytes(limits.maxFileBytes)}，未解析。`],
            limitsHit: ["maxFileBytes"],
            durationMs: Date.now() - started,
            workerExitCode: null,
            limits: limitState,
        };
    }
    await mkdir(input.outputDir, { recursive: true });
    try {
        const result = await parseByType(input.filePath, filename, mime, input.outputDir, limits);
        const truncated = truncateText(result.text ?? "", limits.maxTextChars);
        const warnings = [...(result.warnings ?? [])];
        const limitsHit = [...(result.limitsHit ?? [])];
        if (truncated.truncated) {
            warnings.push(`文档文字较长，已截取前 ${limits.maxTextChars} 字符。`);
            limitsHit.push("maxTextChars");
        }
        addGenericWarnings(warnings, result, limits);
        limitState.textTruncated = truncated.truncated;
        limitState.imageCountExceeded = result.imageCountExceeded === true;
        limitState.pdfPageLimitExceeded = result.pdfPageLimitExceeded === true;
        limitState.unsupportedImages = result.unsupportedImages === true;
        const parsed = {
            kind: deriveKind(truncated.text, result.images?.length ?? 0, result.unsupported === true),
            status: "success",
            filename,
            mime,
            sizeBytes,
            text: truncated.text,
            textChars: truncated.text.length,
            textTruncated: truncated.truncated,
            images: result.images ?? [],
            warnings: [...new Set(warnings)],
            limitsHit: [...new Set(limitsHit)],
            durationMs: Date.now() - started,
            workerExitCode: null,
            limits: limitState,
        };
        return { ...parsed, status: deriveStatus(parsed) };
    }
    catch (error) {
        return {
            kind: "unsupported",
            status: "failed",
            filename,
            mime,
            sizeBytes,
            text: "",
            textChars: 0,
            textTruncated: false,
            images: [],
            warnings: [`文件损坏、加密或格式不支持，无法读取内容。${String(error.message ?? error).slice(0, 160)}`],
            limitsHit: ["parseFailed"],
            durationMs: Date.now() - started,
            workerExitCode: null,
            limits: limitState,
        };
    }
}
async function failedWorkerResult(input, status, warning, durationMs, workerExitCode, extraLimitsHit = []) {
    const filename = input.filename?.trim() || path.basename(input.filePath);
    const mime = inferMime(input.filePath, filename, input.mime);
    let sizeBytes = 0;
    try {
        sizeBytes = (await stat(input.filePath)).size;
    }
    catch { /* noop */ }
    return {
        kind: "unsupported",
        status,
        filename,
        mime,
        sizeBytes,
        text: "",
        textChars: 0,
        textTruncated: false,
        images: [],
        warnings: [warning],
        limitsHit: extraLimitsHit,
        durationMs,
        workerExitCode,
        limits: emptyLimitState(),
    };
}
function isOomLike(exitCode, signal, stderr) {
    return signal === "SIGKILL" || exitCode === 134 || /heap out of memory|allocation failed|javascript heap|out of memory|oom/i.test(stderr);
}
function resolveWorkerEntry() {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    if (currentDir.endsWith(`${path.sep}src`)) {
        return path.join(currentDir, "..", "dist", "worker-entry.js");
    }
    return path.join(currentDir, "worker-entry.js");
}
export async function parseDocumentInWorker(input, options = {}) {
    const started = Date.now();
    const timeoutMs = Math.max(1, options.timeoutMs ?? DEFAULT_WORKER_TIMEOUT_MS);
    const maxOldSpaceMb = Math.max(64, options.maxOldSpaceMb ?? DEFAULT_WORKER_OLD_SPACE_MB);
    const dir = await import("node:fs/promises").then(({ mkdtemp }) => mkdtemp(path.join(os.tmpdir(), "document-parser-worker-")));
    const inputPath = path.join(dir, "input.json");
    const outputPath = path.join(dir, "output.json");
    await writeFile(inputPath, JSON.stringify(input), "utf8");
    return await new Promise((resolve) => {
        let settled = false;
        let stderr = "";
        const finish = async (result) => {
            if (settled)
                return;
            settled = true;
            await rm(dir, { recursive: true, force: true }).catch(() => undefined);
            resolve(result);
        };
        const child = spawn(process.execPath, [`--max-old-space-size=${maxOldSpaceMb}`, resolveWorkerEntry(), inputPath, outputPath], {
            stdio: ["ignore", "ignore", "pipe"],
            env: { ...process.env, NODE_OPTIONS: "" },
        });
        const timer = setTimeout(() => {
            child.kill("SIGKILL");
            void failedWorkerResult(input, "timeout", "文档解析超过 60 秒，已保存原文件但未提取正文。", Date.now() - started, null, ["workerTimeout"]).then(finish);
        }, timeoutMs);
        child.stderr.setEncoding("utf8");
        child.stderr.on("data", (chunk) => {
            stderr += String(chunk).slice(0, 4000);
            if (stderr.length > 8000)
                stderr = stderr.slice(-8000);
        });
        child.on("error", (error) => {
            clearTimeout(timer);
            void failedWorkerResult(input, "failed", `文档解析子进程启动失败，已保存原文件。${String(error.message).slice(0, 120)}`, Date.now() - started, null, ["workerStartFailed"]).then(finish);
        });
        child.on("close", (code, signal) => {
            clearTimeout(timer);
            if (settled)
                return;
            void (async () => {
                if (code === 0) {
                    try {
                        const parsed = JSON.parse(await readFile(outputPath, "utf8"));
                        await finish({ ...parsed, durationMs: parsed.durationMs ?? Date.now() - started, workerExitCode: 0 });
                        return;
                    }
                    catch (error) {
                        await finish(await failedWorkerResult(input, "failed", `文档解析结果读取失败，已保存原文件。${String(error.message ?? error).slice(0, 120)}`, Date.now() - started, code, ["workerResultInvalid"]));
                        return;
                    }
                }
                if (isOomLike(code, signal, stderr)) {
                    await finish(await failedWorkerResult(input, "worker_oom", "文档结构过大或图片解压后过大，解析子进程内存不足。原文件已保存到工作区。", Date.now() - started, code, ["workerOom"]));
                    return;
                }
                await finish(await failedWorkerResult(input, "failed", `文档解析子进程异常退出，已保存原文件。${stderr.slice(0, 160)}`, Date.now() - started, code, ["workerFailed"]));
            })();
        });
    });
}
//# sourceMappingURL=index.js.map