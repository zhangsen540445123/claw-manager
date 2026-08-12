export async function parsePdf(filePath, _outputDir, limits) {
    const warnings = [];
    const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
    const data = new Uint8Array(await import("node:fs/promises").then(({ readFile }) => readFile(filePath)));
    const doc = await pdfjs.getDocument({ data, disableWorker: true }).promise;
    const pageCount = Number(doc.numPages ?? 0);
    const textParts = [];
    const textPages = Math.min(pageCount, Math.max(pageCount, limits.maxPdfPages));
    for (let pageNo = 1; pageNo <= textPages; pageNo += 1) {
        const page = await doc.getPage(pageNo);
        const content = await page.getTextContent();
        const text = (content.items ?? []).map((item) => String(item.str ?? "")).filter(Boolean).join(" ");
        if (text)
            textParts.push(`## PDF 第 ${pageNo} 页\n${text}`);
    }
    if (pageCount > limits.maxPdfPages) {
        warnings.push(`PDF 页数较多，仅处理前 ${limits.maxPdfPages} 页。`);
    }
    warnings.push("PDF 页面图片渲染在当前运行环境中为 best-effort；未生成视觉页时已仅使用可提取文字。");
    return { text: textParts.join("\n\n"), warnings, unsupportedImages: true, pdfPageLimitExceeded: pageCount > limits.maxPdfPages };
}
//# sourceMappingURL=pdf.js.map