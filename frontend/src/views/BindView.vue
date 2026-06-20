<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { RefreshCw, Smartphone } from "lucide-vue-next";
import { ApiError, api } from "../api/http";
import type { PublicWechatBindLink } from "../api/types";
import { bindStatusLabel, bindStatusTagType, isLinkExpired } from "../utils/adminUi";
import { renderQrDataUrl } from "../utils/qr";

const route = useRoute();
const link = ref<PublicWechatBindLink | null>(null);
const loading = ref(false);
const actionLoading = ref("");
const error = ref("");
const qrImage = ref("");
let timer: number | undefined;

const token = computed(() => String(route.params.token || ""));
const showQr = computed(() => {
  const current = link.value;
  return Boolean(current && current.status === "waiting_scan" && !current.qrExpired && qrImage.value);
});
const canRefreshQr = computed(() => {
  const current = link.value;
  return Boolean(current && (current.status === "expired" || current.status === "failed") && !isLinkExpired(current.expiresAt));
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
    await updateQrImage();
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "扫码链接读取失败";
  } finally {
    loading.value = false;
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
    await updateQrImage();
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "二维码刷新失败";
  } finally {
    actionLoading.value = "";
  }
}

async function updateQrImage() {
  const current = link.value;
  if (!current || current.qrExpired || current.status !== "waiting_scan") {
    qrImage.value = "";
    return;
  }
  if (current.qrMode === "image" && current.qrPayload) {
    qrImage.value = current.qrPayload;
    return;
  }
  qrImage.value = current.qrLink ? await renderQrDataUrl(current.qrLink) : "";
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
          <el-tag :type="bindStatusTagType(link.status)" effect="plain">{{ bindStatusLabel(link.status, link.statusLabel) }}</el-tag>
          <span>{{ link.message }}</span>
        </div>

        <div v-if="showQr" class="qr-box">
          <img :src="qrImage" alt="微信二维码" />
        </div>

        <div class="button-row">
          <el-button
            v-if="canRefreshQr"
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
