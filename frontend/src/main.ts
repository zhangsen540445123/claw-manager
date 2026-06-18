import "element-plus/dist/index.css";
import "./styles.css";

import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";

createApp(App)
  .use(createPinia())
  .use(router)
  .use(VueQueryPlugin)
  .use(ElementPlus)
  .mount("#app");
