<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { LogIn } from "lucide-vue-next";
import { ApiError } from "../api/http";
import clawManagerLogo from "../claw-manager.png";
import { useSessionStore } from "../stores/session";

const router = useRouter();
const session = useSessionStore();
const loading = ref(false);
const error = ref("");
const form = reactive({
  email: "",
  password: ""
});

async function submit() {
  loading.value = true;
  error.value = "";
  try {
    await session.login(form.email, form.password);
    await router.replace("/");
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "登录失败";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="auth-page login-page">
    <el-form class="auth-panel" label-position="top" @submit.prevent="submit">
      <div class="auth-brand">
        <img class="brand-mark" :src="clawManagerLogo" alt="Claw Manager" />
        <div>
          <strong>Claw Manager</strong>
          <span>OpenClaw 运行与渠道控制台</span>
        </div>
      </div>
      <h1>管理员登录</h1>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <el-form-item label="邮箱">
        <el-input v-model="form.email" autocomplete="email" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
      </el-form-item>
      <div class="auth-actions">
        <el-button type="primary" native-type="submit" :loading="loading" :icon="LogIn">登录</el-button>
      </div>
    </el-form>
  </section>
</template>
