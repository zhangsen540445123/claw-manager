<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { KeyRound } from "lucide-vue-next";
import { ApiError } from "../api/http";
import { useSessionStore } from "../stores/session";

const router = useRouter();
const session = useSessionStore();
const loading = ref(false);
const error = ref("");
const form = reactive({
  currentPassword: "",
  newPassword: "",
  confirmPassword: ""
});

const needCurrentPassword = computed(() => !session.user?.mustChangePassword);

async function submit() {
  error.value = "";
  if (form.newPassword !== form.confirmPassword) {
    error.value = "两次输入的新密码不一致。";
    return;
  }

  loading.value = true;
  try {
    await session.changePassword(form.currentPassword, form.newPassword);
    await router.replace("/admin");
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "修改密码失败";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="workspace account-page">
    <div class="section-title">
      <h1>账号设置</h1>
      <el-button text @click="router.push('/admin')">返回</el-button>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="card-title">
          <KeyRound :size="18" />
          <span>修改管理员密码</span>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="submit">
        <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
        <el-form-item v-if="needCurrentPassword" label="当前密码">
          <el-input v-model="form.currentPassword" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <div class="auth-actions">
          <el-button type="primary" native-type="submit" :loading="loading" :icon="KeyRound">保存</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>
