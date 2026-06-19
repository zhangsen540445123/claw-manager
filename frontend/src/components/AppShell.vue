<script setup lang="ts">
import { Activity, Boxes, PlusSquare, QrCode, Server, SlidersHorizontal, Menu, X } from "lucide-vue-next";
import { computed, ref } from "vue";

type ShellNavKey = "overview" | "presets" | "create" | "wechat" | "instances" | "ops" | "account";

const props = defineProps<{
  authenticated: boolean;
  activeRoute: string;
  wsConnected: boolean;
  userName?: string;
}>();

const emit = defineEmits<{
  navigate: [route: ShellNavKey];
  logout: [];
}>();

const mobileNavOpen = ref(false);
const navItems = computed(() => {
  return [
    { key: "overview" as const, label: "运行总览", icon: Activity },
    { key: "presets" as const, label: "模型预设", icon: SlidersHorizontal },
    { key: "create" as const, label: "创建实例", icon: PlusSquare },
    { key: "wechat" as const, label: "扫码链接", icon: QrCode },
    { key: "instances" as const, label: "实例管理", icon: Boxes },
    { key: "ops" as const, label: "系统运维", icon: Server }
  ];
});

const routeTitles: Record<string, string> = {
  overview: "运行总览",
  presets: "模型预设",
  create: "创建实例",
  wechat: "扫码链接",
  instances: "实例管理",
  ops: "系统运维",
  account: "账号设置"
};

function navigate(route: ShellNavKey) {
  mobileNavOpen.value = false;
  emit("navigate", route);
}

function logout() {
  mobileNavOpen.value = false;
  emit("logout");
}
</script>

<template>
  <el-container class="app-shell">
    <template v-if="authenticated">
      <aside class="app-sidebar" :class="{ 'is-open': mobileNavOpen }">
        <div class="sidebar-brand">
          <div class="brand-mark">C</div>
          <div>
            <strong>Claw Manager</strong>
            <span>OpenClaw Console</span>
          </div>
        </div>

        <nav class="sidebar-nav">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ 'is-active': activeRoute === item.key }"
            type="button"
            @click="navigate(item.key)"
          >
            <component :is="item.icon" :size="18" />
            <span>{{ item.label }}</span>
          </button>
        </nav>
      </aside>

      <div v-if="mobileNavOpen" class="mobile-backdrop" @click="mobileNavOpen = false" />
    </template>

    <el-container class="app-body">
      <el-header v-if="authenticated" class="app-topbar">
        <button class="mobile-menu-button" type="button" @click="mobileNavOpen = !mobileNavOpen">
          <Menu v-if="!mobileNavOpen" :size="18" />
          <X v-else :size="18" />
        </button>
        <div class="topbar-title">
          <span>{{ routeTitles[activeRoute] || "后台管理" }}</span>
          <small>OpenClaw 实例、模型与微信绑定</small>
        </div>
        <div class="topbar-actions">
          <el-tag :type="wsConnected ? 'success' : 'warning'" effect="plain">
            {{ wsConnected ? "实时" : "重连中" }}
          </el-tag>
          <button class="account-button" type="button" @click="navigate('account')">
            <span>{{ userName || "账户" }}</span>
          </button>
          <el-button @click="logout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
