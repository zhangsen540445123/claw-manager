import { createRouter, createWebHistory } from "vue-router";
import AdminOverviewView from "./views/admin/AdminOverviewView.vue";
import InstanceCreateView from "./views/admin/InstanceCreateView.vue";
import InstancesView from "./views/admin/InstancesView.vue";
import MiniappClientsView from "./views/admin/MiniappClientsView.vue";
import ModelPresetsView from "./views/admin/ModelPresetsView.vue";
import OpenVikingSettingsView from "./views/admin/OpenVikingSettingsView.vue";
import OpsView from "./views/admin/OpsView.vue";
import SkillManagementView from "./views/admin/SkillManagementView.vue";
import WechatLinksView from "./views/admin/WechatLinksView.vue";
import WechatPluginsView from "./views/admin/WechatPluginsView.vue";
import WechatUsersView from "./views/admin/WechatUsersView.vue";
import BindView from "./views/BindView.vue";
import ChangePasswordView from "./views/ChangePasswordView.vue";
import LoginView from "./views/LoginView.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/admin/overview" },
    { path: "/admin", redirect: "/admin/overview" },
    { path: "/admin/overview", name: "admin-overview", component: AdminOverviewView, meta: { shellKey: "overview", title: "运行总览" } },
    { path: "/admin/model-presets", name: "admin-model-presets", component: ModelPresetsView, meta: { shellKey: "presets", title: "模型预设" } },
    { path: "/admin/skills", name: "admin-skills", component: SkillManagementView, meta: { shellKey: "skills", title: "Skill 管理" } },
    { path: "/admin/instances/new", name: "admin-instance-new", component: InstanceCreateView, meta: { shellKey: "create", title: "创建实例" } },
    { path: "/admin/instances", name: "admin-instances", component: InstancesView, meta: { shellKey: "instances", title: "实例管理" } },
    { path: "/admin/openviking-settings", name: "admin-openviking-settings", component: OpenVikingSettingsView, meta: { shellKey: "openVikingSettings", title: "OpenViking 配置" } },
    { path: "/admin/wechat-plugins", name: "admin-wechat-plugins", component: WechatPluginsView, meta: { shellKey: "wechatPlugins", title: "插件管理" } },
    { path: "/admin/wechat-links", name: "admin-wechat-links", component: WechatLinksView, meta: { shellKey: "wechat", title: "微信扫码" } },
    { path: "/admin/wechat-users", name: "admin-wechat-users", component: WechatUsersView, meta: { shellKey: "wechatUsers", title: "用户中心" } },
    { path: "/admin/miniapp-clients", name: "admin-miniapp-clients", component: MiniappClientsView, meta: { shellKey: "miniappClients", title: "小程序接入" } },
    { path: "/admin/ops", name: "admin-ops", component: OpsView, meta: { shellKey: "ops", title: "系统运维" } },
    { path: "/login", name: "login", component: LoginView },
    { path: "/change-password", name: "change-password", component: ChangePasswordView },
    { path: "/bind/:token", name: "bind", component: BindView }
  ]
});
