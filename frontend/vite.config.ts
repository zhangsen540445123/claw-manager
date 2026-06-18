import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiTarget = env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8080";
  const wsTarget = apiTarget.replace(/^http:/, "ws:").replace(/^https:/, "wss:");

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: true
        },
        "/proxy": {
          target: apiTarget,
          changeOrigin: true,
          ws: true
        },
        "/ws": {
          target: wsTarget,
          changeOrigin: true,
          ws: true
        }
      }
    }
  };
});
