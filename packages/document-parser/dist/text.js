export function truncateText(text, maxChars) {
    if (text.length <= maxChars)
        return { text, truncated: false };
    return { text: text.slice(0, Math.max(0, maxChars)), truncated: true };
}
export function decodeXmlText(input) {
    return input
        .replaceAll(/<[^>]+>/g, " ")
        .replaceAll("&lt;", "<")
        .replaceAll("&gt;", ">")
        .replaceAll("&amp;", "&")
        .replaceAll("&quot;", '"')
        .replaceAll("&apos;", "'")
        .replaceAll(/\s+/g, " ")
        .trim();
}
//# sourceMappingURL=text.js.map