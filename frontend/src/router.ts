import { createRouter, createWebHistory } from "vue-router";
import AdminOverviewView from "./views/admin/AdminOverviewView.vue";
import InstanceCreateView from "./views/admin/InstanceCreateView.vue";
import InstancesView from "./views/admin/InstancesView.vue";
import ModelPresetsView from "./views/admin/ModelPresetsView.vue";
import OpsView from "./views/admin/OpsView.vue";
import WechatLinksView from "./views/admin/WechatLinksView.vue";
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
    { path: "/admin/instances/new", name: "admin-instance-new", component: InstanceCreateView, meta: { shellKey: "create", title: "创建实例" } },
    { path: "/admin/wechat-links", name: "admin-wechat-links", component: WechatLinksView, meta: { shellKey: "wechat", title: "微信扫码" } },
    { path: "/admin/instances", name: "admin-instances", component: InstancesView, meta: { shellKey: "instances", title: "实例管理" } },
    { path: "/admin/ops", name: "admin-ops", component: OpsView, meta: { shellKey: "ops", title: "系统运维" } },
    { path: "/login", name: "login", component: LoginView },
    { path: "/change-password", name: "change-password", component: ChangePasswordView },
    { path: "/bind/:token", name: "bind", component: BindView }
  ]
});
