import mammoth from "mammoth";
import JSZip from "jszip";
import { extractOfficeImages } from "../image.js";
export async function parseDocx(filePath, outputDir, limits) {
    const [docxText, zip] = await Promise.all([
        mammoth.extractRawText({ path: filePath }).catch((error) => {
            throw new Error(`DOCX_PARSE_FAILED: ${String(error)}`);
        }),
        JSZip.loadAsync(await import("node:fs/promises").then(({ readFile }) => readFile(filePath))),
    ]);
    const extracted = await extractOfficeImages({ zip, mediaPrefix: "word/media/", outputDir, limits });
    return {
        text: docxText.value ?? "",
        images: extracted.images,
        imageCountExceeded: extracted.imageCountExceeded,
        warnings: (docxText.messages ?? []).map((m) => `DOCX 解析提示：${m.message}`),
    };
}
//# sourceMappingURL=docx.js.map