<script setup lang="ts">
import { computed, nextTick, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppShell from "./components/AppShell.vue";
import { connectAppWebSocket, disconnectAppWebSocket } from "./ws/client";
import { useAdminStore } from "./stores/admin";
import { useSessionStore } from "./stores/session";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const admin = useAdminStore();

const publicRoute = computed(() => route.name === "bind");
const authRoute = computed(() => route.name === "login");
const passwordRoute = computed(() => route.name === "change-password");
const forcedPasswordRoute = computed(() => passwordRoute.value && route.query.forced === "1");
const adminSections = new Set(["overview", "presets", "create", "wechat", "instances", "ops"]);
const activeShellRoute = computed(() => {
  if (passwordRoute.value) {
    return "account";
  }
  if (route.name === "admin") {
    const section = route.hash.replace(/^#admin-/, "");
    return adminSections.has(section) ? section : "overview";
  }
  return String(route.name || "");
});

async function redirectUnauthenticated() {
  if (publicRoute.value || authRoute.value) return;
  await router.replace("/login");
}

async function enterAuthenticatedArea() {
  connectAppWebSocket();
  await admin.loadAll();
  if (session.user?.mustChangePassword && !passwordRoute.value) {
    await router.replace({ path: "/change-password", query: { forced: "1" } });
  } else if (!session.user?.mustChangePassword && forcedPasswordRoute.value) {
    await router.replace("/admin");
  } else if (authRoute.value || route.path === "/") {
    await router.replace("/admin");
  }
}

onMounted(async () => {
  if (publicRoute.value) {
    return;
  }
  await session.load();
  if (session.authenticated) {
    await enterAuthenticatedArea();
  } else {
    await redirectUnauthenticated();
  }
});

watch(
  () => session.user,
  async (user) => {
    if (publicRoute.value) return;
    if (user) {
      await enterAuthenticatedArea();
    } else {
      disconnectAppWebSocket();
      admin.$reset();
      await redirectUnauthenticated();
    }
  }
);

watch(
  () => route.fullPath,
  async () => {
    if (publicRoute.value) {
      disconnectAppWebSocket();
      return;
    }
    if (!session.authenticated) {
      await redirectUnauthenticated();
    }
  }
);

async function logout() {
  await session.logout();
  disconnectAppWebSocket();
  admin.$reset();
  await router.replace("/login");
}

async function navigate(routeName: "overview" | "presets" | "create" | "wechat" | "instances" | "ops" | "account") {
  if (routeName === "account") {
    void router.push("/change-password");
    return;
  }
  if (!adminSections.has(routeName)) {
    return;
  }
  await router.push({ path: "/admin", hash: `#admin-${routeName}` });
  await nextTick();
  document.getElementById(`admin-${routeName}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
}
</script>

<template>
  <router-view v-if="publicRoute" />
  <AppShell
    v-else
    :authenticated="session.authenticated"
    :active-route="activeShellRoute"
    :ws-connected="admin.wsConnected"
    :user-name="session.user?.name"
    @navigate="navigate"
    @logout="logout"
  />
</template>
