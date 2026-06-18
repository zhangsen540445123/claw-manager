import { createRouter, createWebHistory } from "vue-router";
import AdminView from "./views/AdminView.vue";
import BindView from "./views/BindView.vue";
import ChangePasswordView from "./views/ChangePasswordView.vue";
import LoginView from "./views/LoginView.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/admin" },
    { path: "/admin", name: "admin", component: AdminView },
    { path: "/login", name: "login", component: LoginView },
    { path: "/change-password", name: "change-password", component: ChangePasswordView },
    { path: "/bind/:token", name: "bind", component: BindView }
  ]
});
