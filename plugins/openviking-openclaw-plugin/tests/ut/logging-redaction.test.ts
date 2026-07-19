import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

describe("OpenViking log redaction", () => {
  it("keeps inject-detail to aggregate metadata and URI hashes", () => {
    const source = readFileSync(path.resolve(process.cwd(), "auto-recall.ts"), "utf8");
    const detail = source.match(/inject-detail[^\n]+/)?.[0] ?? "";
    expect(detail).toContain("candidateCount");
    expect(detail).toContain("selectedCount");
    expect(detail).toContain("highestScore");
    expect(detail).toContain("resourceType");
    expect(detail).toContain("uriHash");
    expect(detail).not.toContain("summarizeInjectionMemories");
  });
});
