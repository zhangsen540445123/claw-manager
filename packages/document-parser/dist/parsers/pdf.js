export async function parsePdf(filePath, _outputDir, limits) {
    const warnings = [];
    const limitsHit = [];
    const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
    const data = new Uint8Array(await import("node:fs/promises").then(({ readFile }) => readFile(filePath)));
    const loadingTask = pdfjs.getDocument({ data, disableWorker: true });
    let doc;
    try {
        doc = await loadingTask.promise;
        const pageCount = Number(doc.numPages ?? 0);
        const textParts = [];
        const textPages = Math.min(pageCount, limits.maxPdfPages);
        for (let pageNo = 1; pageNo <= textPages; pageNo += 1) {
            const page = await doc.getPage(pageNo);
            try {
                const content = await page.getTextContent();
                const text = (content.items ?? []).map((item) => String(item.str ?? "")).filter(Boolean).join(" ");
                if (text)
                    textParts.push(`## PDF 第 ${pageNo} 页\n${text}`);
                if (textParts.join("\n\n").length >= limits.maxTextChars) {
                    limitsHit.push("maxTextChars");
                    break;
                }
            }
            finally {
                page.cleanup?.();
            }
        }
        if (pageCount > limits.maxPdfPages) {
            warnings.push(`PDF 页数较多，仅处理前 ${limits.maxPdfPages} 页。`);
            limitsHit.push("maxPdfPages");
        }
        warnings.push("PDF 页面图片渲染在当前运行环境中为 best-effort；未生成视觉页时已仅使用可提取文字。");
        return { text: textParts.join("\n\n"), warnings, limitsHit: [...new Set(limitsHit)], unsupportedImages: true, pdfPageLimitExceeded: pageCount > limits.maxPdfPages };
    }
    catch (error) {
        return { text: "", warnings: [`PDF 文件可能损坏、加密或格式异常，无法读取内容。${String(error.message ?? error).slice(0, 120)}`], limitsHit: ["pdfParseFailed"], unsupported: true, unsupportedImages: true };
    }
    finally {
        try {
            await doc?.cleanup?.();
        }
        catch { /* noop */ }
        try {
            await doc?.destroy?.();
        }
        catch { /* noop */ }
        try {
            await loadingTask?.destroy?.();
        }
        catch { /* noop */ }
    }
}
//# sourceMappingURL=pdf.js.map