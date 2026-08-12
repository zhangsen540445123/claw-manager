import { readFile } from "node:fs/promises";
import JSZip from "jszip";
import { extractOfficeImages } from "../image.js";
import { decodeXmlText } from "../text.js";
export async function parsePptx(filePath, outputDir, limits) {
    const zip = await JSZip.loadAsync(await readFile(filePath));
    const slideNames = Object.keys(zip.files)
        .filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name))
        .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    const parts = [];
    for (const name of slideNames) {
        const xml = await zip.files[name].async("string");
        const texts = Array.from(xml.matchAll(/<a:t[^>]*>([\s\S]*?)<\/a:t>/g)).map((match) => decodeXmlText(match[1] ?? ""));
        if (texts.length)
            parts.push(`## ${name}\n${texts.filter(Boolean).join("\n")}`);
    }
    const extracted = await extractOfficeImages({ zip, mediaPrefix: "ppt/media/", outputDir, limits });
    return { text: parts.join("\n\n"), images: extracted.images, imageCountExceeded: extracted.imageCountExceeded };
}
//# sourceMappingURL=pptx.js.map