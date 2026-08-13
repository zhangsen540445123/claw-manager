import mammoth from "mammoth";
import JSZip from "jszip";
import { readFile } from "node:fs/promises";
import { extractOfficeImages, inspectZipEntries } from "../image.js";
export async function parseDocx(filePath, outputDir, limits) {
    const zip = await JSZip.loadAsync(await readFile(filePath));
    const inspection = inspectZipEntries(zip, limits);
    const warnings = [...inspection.warnings];
    const limitsHit = [...inspection.limitsHit];
    let text = "";
    if (inspection.safeForXml) {
        const docxText = await mammoth.extractRawText({ path: filePath }).catch((error) => {
            throw new Error(`DOCX_PARSE_FAILED: ${String(error)}`);
        });
        text = docxText.value ?? "";
        warnings.push(...(docxText.messages ?? []).map((m) => `DOCX 解析提示：${m.message}`));
    }
    else {
        warnings.push("DOCX 内部结构过大，已跳过正文解析以避免内存过高。");
        limitsHit.push("maxOfficeXmlBytes");
    }
    if (!inspection.safeForMedia || limits.maxImages <= 0 || limits.maxOfficeMediaFiles <= 0) {
        if (!inspection.safeForMedia)
            warnings.push("DOCX 内部图片较大或较多，已跳过图片提取。");
        return { text, images: [], imageCountExceeded: true, warnings: [...new Set(warnings)], limitsHit: [...new Set(limitsHit)] };
    }
    const extracted = await extractOfficeImages({ zip, mediaPrefix: "word/media/", outputDir, limits });
    return {
        text,
        images: extracted.images,
        imageCountExceeded: extracted.imageCountExceeded,
        warnings: [...new Set([...warnings, ...extracted.warnings])],
        limitsHit: [...new Set([...limitsHit, ...extracted.limitsHit])],
    };
}
//# sourceMappingURL=docx.js.map