<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { RefreshCw, Smartphone } from "lucide-vue-next";
import { ApiError, api, jsonBody } from "../api/http";
import type { PublicWechatBindLink } from "../api/types";

const route = useRoute();
const link = ref<PublicWechatBindLink | null>(null);
const loading = ref(false);
const actionLoading = ref("");
const error = ref("");
const phoneForm = reactive({ phone: "" });
let timer: number | undefined;

const token = computed(() => String(route.params.token || ""));
const needsPhone = computed(() => link.value?.status === "phone_required");
const showQr = computed(() => {
  const current = link.value;
  return Boolean(current && current.status === "waiting_scan" && !current.qrExpired && (current.qrPayload || current.qrLink));
});
const qrSource = computed(() => {
  const current = link.value;
  if (!current || current.qrExpired) return "";
  if (current.qrMode === "image" && current.qrPayload) return current.qrPayload;
  if (current.qrLink) {
    return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(current.qrLink)}`;
  }
  return "";
});

onMounted(async () => {
  await loadStatus();
  timer = window.setInterval(loadStatus, 3000);
});

onBeforeUnmount(() => {
  if (timer) {
    window.clearInterval(timer);
  }
});

async function loadStatus() {
  if (!token.value) return;
  loading.value = true;
  error.value = "";
  try {
    const response = await api<{ link: PublicWechatBindLink }>(`/api/public/wechat-bind-links/${encodeURIComponent(token.value)}`);
    link.value = response.link;
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "扫码链接读取失败";
  } finally {
    loading.value = false;
  }
}

async function submitPhone() {
  actionLoading.value = "phone";
  error.value = "";
  try {
    const response = await api<{ link: PublicWechatBindLink }>(`/api/public/wechat-bind-links/${encodeURIComponent(token.value)}/phone`, {
      method: "POST",
      ...jsonBody({ phone: phoneForm.phone })
    });
    link.value = response.link;
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "手机号提交失败";
  } finally {
    actionLoading.value = "";
  }
}

async function refreshQr() {
  actionLoading.value = "refresh";
  error.value = "";
  try {
    const response = await api<{ link: PublicWechatBindLink }>(`/api/public/wechat-bind-links/${encodeURIComponent(token.value)}/qr/refresh`, {
      method: "POST"
    });
    link.value = response.link;
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "二维码刷新失败";
  } finally {
    actionLoading.value = "";
  }
}

function tagType(status: string) {
  if (status === "connected") return "success";
  if (status === "failed" || status === "rejected" || status === "expired") return "danger";
  if (status === "waiting_scan" || status === "scanned") return "warning";
  return "info";
}
</script>

<template>
  <section class="bind-page">
    <el-card class="bind-panel" shadow="never">
      <template #header>
        <div class="card-title">
          <Smartphone :size="18" />
          <span>微信扫码绑定</span>
        </div>
      </template>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

      <template v-if="link">
        <div class="wechat-status">
          <el-tag :type="tagType(link.status)" effect="plain">{{ link.status }}</el-tag>
          <span>{{ link.message }}</span>
        </div>

        <el-form v-if="needsPhone" class="phone-form" label-position="top" @submit.prevent="submitPhone">
          <el-form-item label="手机号">
            <el-input v-model="phoneForm.phone" inputmode="tel" autocomplete="tel" />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="actionLoading === 'phone'">
            获取二维码
          </el-button>
        </el-form>

        <div v-if="showQr" class="qr-box">
          <img v-if="qrSource" :src="qrSource" alt="微信二维码" />
          <pre v-else>{{ link.qrPayload }}</pre>
        </div>

        <div class="button-row">
          <el-button
            v-if="link.status === 'expired' || link.status === 'failed'"
            :icon="RefreshCw"
            :loading="actionLoading === 'refresh'"
            @click="refreshQr"
          >
            重新生成
          </el-button>
          <el-button text :loading="loading" @click="loadStatus">刷新状态</el-button>
        </div>
      </template>

      <el-skeleton v-else :rows="4" animated />
    </el-card>
  </section>
</template>
