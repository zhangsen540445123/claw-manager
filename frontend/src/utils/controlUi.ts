export function resolveControlUiUrl(rawUrl: string) {
  if (!rawUrl) {
    return "";
  }
  try {
    const browserOrigin = new URL(window.location.origin);
    const url = new URL(rawUrl, browserOrigin);
    const localHost = url.hostname === "127.0.0.1" || url.hostname === "localhost" || url.hostname === "::1";
    const relative = rawUrl.startsWith("/");
    if (!localHost && !relative) {
      return url.toString();
    }

    const rebuilt = new URL(`${url.pathname}${url.search}${url.hash}`, browserOrigin);
    if (rebuilt.pathname.startsWith("/proxy/")) {
      const gatewayPath = rebuilt.pathname.replace(/\/$/, "");
      const gatewayProtocol = browserOrigin.protocol === "https:" ? "wss:" : "ws:";
      rebuilt.searchParams.set("gatewayUrl", `${gatewayProtocol}//${browserOrigin.host}${gatewayPath}`);
    }
    return rebuilt.toString();
  } catch {
    return rawUrl;
  }
}
