<script setup lang="ts">
import { computed, onMounted, watch } from "vue";
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
const activeShellRoute = computed(() => {
  if (passwordRoute.value) {
    return "account";
  }
  if (typeof route.meta.shellKey === "string") {
    return route.meta.shellKey;
  }
  return String(route.name || "");
});

const adminRoutePaths = {
  overview: "/admin/overview",
  presets: "/admin/model-presets",
  create: "/admin/instances/new",
  instances: "/admin/instances",
  wechat: "/admin/wechat-links",
  wechatPlugins: "/admin/wechat-plugins",
  wechatUsers: "/admin/wechat-users",
  ops: "/admin/ops"
} as const;

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
    await router.replace("/admin/overview");
  } else if (authRoute.value || route.path === "/") {
    await router.replace("/admin/overview");
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

async function navigate(
  routeName: "overview" | "presets" | "create" | "instances" | "wechat" | "wechatPlugins" | "wechatUsers" | "ops" | "account"
) {
  if (routeName === "account") {
    void router.push("/change-password");
    return;
  }
  await router.push(adminRoutePaths[routeName]);
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
