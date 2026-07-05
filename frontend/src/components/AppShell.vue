<script setup lang="ts">
import {
  Activity,
  Boxes,
  ChevronsLeft,
  ChevronsRight,
  Database,
  KeyRound,
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
  | "miniappClients"
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
const navGroups = computed(() => {
  return [
    {
      label: "运行",
      items: [
        { key: "overview" as const, label: "运行总览", desc: "全局状态", icon: Activity },
        { key: "instances" as const, label: "实例管理", desc: "容器与 Gateway", icon: Boxes }
      ]
    },
    {
      label: "配置",
      items: [
        { key: "create" as const, label: "创建实例", desc: "新建 OpenClaw", icon: PlusSquare },
        { key: "presets" as const, label: "模型预设", desc: "模型供应商", icon: SlidersHorizontal },
        { key: "openVikingSettings" as const, label: "OpenViking预设", desc: "记忆服务", icon: Database }
      ]
    },
    {
      label: "渠道",
      items: [
        { key: "wechatPlugins" as const, label: "插件管理", desc: "渠道插件", icon: PackageCheck },
        { key: "wechat" as const, label: "扫码链接", desc: "微信接入", icon: QrCode },
        { key: "wechatUsers" as const, label: "用户中心", desc: "微信与 API", icon: UsersRound },
        { key: "miniappClients" as const, label: "小程序接入", desc: "AK / SK", icon: KeyRound }
      ]
    },
    {
      label: "运维",
      items: [
        { key: "ops" as const, label: "系统运维", desc: "日志与镜像", icon: Server }
      ]
    }
  ];
});

const routeTitles: Record<string, string> = {
  overview: "运行总览",
  presets: "模型预设",
  create: "创建实例",
  instances: "实例管理",
  openVikingSettings: "OpenViking预设",
  wechat: "扫码链接",
  wechatPlugins: "插件管理",
  wechatUsers: "用户中心",
  miniappClients: "小程序接入",
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
            <span>OpenClaw 控制台</span>
          </div>
          <button class="sidebar-collapse-button" type="button" @click="toggleSidebar">
            <ChevronsRight v-if="sidebarCollapsed" :size="16" />
            <ChevronsLeft v-else :size="16" />
          </button>
        </div>

        <nav class="sidebar-nav">
          <section v-for="group in navGroups" :key="group.label" class="nav-group">
            <span class="nav-group-label">{{ group.label }}</span>
            <el-tooltip
              v-for="item in group.items"
              :key="item.key"
              :content="`${item.label} · ${item.desc}`"
              placement="right"
              :disabled="!sidebarCollapsed"
            >
              <button
                class="nav-item"
                :class="{ 'is-active': activeRoute === item.key }"
                type="button"
                @click="navigate(item.key)"
              >
                <span class="nav-icon"><component :is="item.icon" :size="17" /></span>
                <span class="nav-copy">
                  <strong>{{ item.label }}</strong>
                  <small>{{ item.desc }}</small>
                </span>
              </button>
            </el-tooltip>
          </section>
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
          <small>实例、插件、微信与小程序接入</small>
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
