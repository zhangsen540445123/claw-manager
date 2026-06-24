<script setup lang="ts">
import {
  Activity,
  Boxes,
  ChevronsLeft,
  ChevronsRight,
  Database,
  PackageCheck,
  PlusSquare,
  QrCode,
  Server,
  SlidersHorizontal,
  UsersRound,
  Menu,
  X
} from "lucide-vue-next";
import { computed, ref } from "vue";
import clawManagerLogo from "../claw-manager.png";

type ShellNavKey =
  | "overview"
  | "presets"
  | "create"
  | "instances"
  | "openVikingSettings"
  | "wechat"
  | "wechatPlugins"
  | "wechatUsers"
  | "ops"
  | "account";

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
const sidebarCollapsed = ref(localStorage.getItem("claw-manager-sidebar-collapsed") === "1");
const navItems = computed(() => {
  return [
    { key: "overview" as const, label: "运行总览", icon: Activity },
    { key: "presets" as const, label: "模型预设", icon: SlidersHorizontal },
    { key: "create" as const, label: "创建实例", icon: PlusSquare },
    { key: "instances" as const, label: "实例管理", icon: Boxes },
    { key: "openVikingSettings" as const, label: "OpenViking", icon: Database },
    { key: "wechatPlugins" as const, label: "插件管理", icon: PackageCheck },
    { key: "wechat" as const, label: "扫码链接", icon: QrCode },
    { key: "wechatUsers" as const, label: "用户中心", icon: UsersRound },
    { key: "ops" as const, label: "系统运维", icon: Server }
  ];
});

const routeTitles: Record<string, string> = {
  overview: "运行总览",
  presets: "模型预设",
  create: "创建实例",
  instances: "实例管理",
  openVikingSettings: "OpenViking 配置",
  wechat: "扫码链接",
  wechatPlugins: "插件管理",
  wechatUsers: "用户中心",
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

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  localStorage.setItem("claw-manager-sidebar-collapsed", sidebarCollapsed.value ? "1" : "0");
}
</script>

<template>
  <el-container class="app-shell">
    <template v-if="authenticated">
      <aside class="app-sidebar" :class="{ 'is-open': mobileNavOpen, 'is-collapsed': sidebarCollapsed }">
        <div class="sidebar-brand">
          <img class="brand-mark" :src="clawManagerLogo" alt="Claw Manager" />
          <div class="brand-copy">
            <strong>Claw Manager</strong>
          </div>
          <button class="sidebar-collapse-button" type="button" @click="toggleSidebar">
            <ChevronsRight v-if="sidebarCollapsed" :size="16" />
            <ChevronsLeft v-else :size="16" />
          </button>
        </div>

        <nav class="sidebar-nav">
          <el-tooltip
            v-for="item in navItems"
            :key="item.key"
            :content="item.label"
            placement="right"
            :disabled="!sidebarCollapsed"
          >
            <button
              class="nav-item"
              :class="{ 'is-active': activeRoute === item.key }"
              type="button"
              @click="navigate(item.key)"
            >
              <component :is="item.icon" :size="18" />
              <span class="nav-label">{{ item.label }}</span>
            </button>
          </el-tooltip>
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
