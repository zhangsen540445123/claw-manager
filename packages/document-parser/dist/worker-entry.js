import { readFile, writeFile } from "node:fs/promises";
import { parseDocument } from "./index.js";
async function main() {
    const [, , inputPath, outputPath] = process.argv;
    if (!inputPath || !outputPath) {
        throw new Error("Usage: worker-entry <input-json> <output-json>");
    }
    const input = JSON.parse(await readFile(inputPath, "utf8"));
    const parsed = await parseDocument(input);
    await writeFile(outputPath, JSON.stringify(parsed), "utf8");
}
main().catch((error) => {
    console.error(String(error.stack ?? error.message ?? error));
    process.exit(1);
});
//# sourceMappingURL=worker-entry.js.map