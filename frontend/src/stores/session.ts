import { defineStore } from "pinia";
import { api, jsonBody } from "../api/http";
import type { PublicAdmin } from "../api/types";

interface SessionResponse {
  user: PublicAdmin | null;
}

export const useSessionStore = defineStore("session", {
  state: () => ({
    user: null as PublicAdmin | null,
    loading: false,
    error: ""
  }),
  getters: {
    authenticated: (state) => Boolean(state.user),
    isAdmin: (state) => state.user?.role === "admin"
  },
  actions: {
    async load() {
      this.loading = true;
      this.error = "";
      try {
        const response = await api<SessionResponse>("/api/session");
        this.user = response.user;
      } catch (error) {
        this.error = error instanceof Error ? error.message : "会话读取失败";
        this.user = null;
      } finally {
        this.loading = false;
      }
    },
    async login(email: string, password: string) {
      const response = await api<SessionResponse>("/api/login", {
        method: "POST",
        ...jsonBody({ email, password })
      });
      this.user = response.user;
    },
    async changePassword(currentPassword: string, newPassword: string) {
      const response = await api<SessionResponse>("/api/change-password", {
        method: "POST",
        ...jsonBody({ currentPassword, newPassword })
      });
      this.user = response.user;
    },
    async logout() {
      await api<{ ok: boolean }>("/api/logout", { method: "POST" });
      this.user = null;
    }
  }
});
