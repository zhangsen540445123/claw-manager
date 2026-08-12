import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { mimeFromImagePath } from "./mime.js";
export async function extractOfficeImages(params) {
    const imagesDir = path.join(params.outputDir, "images");
    const mediaFiles = Object.keys(params.zip.files)
        .filter((name) => name.startsWith(params.mediaPrefix) && !params.zip.files[name]?.dir)
        .sort();
    const selected = mediaFiles.slice(0, params.limits.maxImages);
    const images = [];
    if (selected.length > 0)
        await mkdir(imagesDir, { recursive: true });
    for (const [index, zipPath] of selected.entries()) {
        const entry = params.zip.files[zipPath];
        if (!entry)
            continue;
        const ext = path.extname(zipPath) || ".bin";
        const filename = `${String(index + 1).padStart(3, "0")}${ext}`;
        const outPath = path.join(imagesDir, filename);
        await writeFile(outPath, await entry.async("nodebuffer"));
        images.push({
            path: outPath,
            mime: mimeFromImagePath(outPath),
            label: filename,
            source: params.source ?? "office_embedded_image",
        });
    }
    return { images, imageCountExceeded: mediaFiles.length > selected.length };
}
//# sourceMappingURL=image.js.map