import { readFile, writeFile } from "node:fs/promises";

import { parseDocument } from "./index.js";
import type { ParseDocumentInput } from "./types.js";

async function main(): Promise<void> {
  const [, , inputPath, outputPath] = process.argv;
  if (!inputPath || !outputPath) {
    throw new Error("Usage: worker-entry <input-json> <output-json>");
  }
  const input = JSON.parse(await readFile(inputPath, "utf8")) as ParseDocumentInput;
  const parsed = await parseDocument(input);
  await writeFile(outputPath, JSON.stringify(parsed), "utf8");
}

main().catch((error) => {
  console.error(String((error as Error).stack ?? (error as Error).message ?? error));
  process.exit(1);
});
