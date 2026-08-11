import { ElMessage } from "element-plus";

export function formatDateTime(value?: string | null) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("zh-CN", { hour12: false });
}

export function bindStatusLabel(status: string, fallback = "") {
  const labels: Record<string, string> = {
    phone_required: "待填写手机号",
    created: "已创建",
    starting: "出码中",
    waiting_scan: "等待扫码",
    scanned: "已扫码",
    initializing: "初始化中",
    cleaning: "清理迁移中",
    cleanup_failed: "清理失败",
    connected: "已连接",
    expired: "已过期",
    rejected: "已拒绝",
    failed: "出码失败",
    revoked: "已失效"
  };
  return fallback || labels[status] || status || "-";
}

export function bindStatusTagType(status: string) {
  if (status === "connected") return "success";
  if (status === "failed" || status === "cleanup_failed" || status === "rejected" || status === "expired" || status === "revoked") return "danger";
  if (status === "waiting_scan" || status === "scanned" || status === "initializing" || status === "cleaning" || status === "starting") return "warning";
  return "info";
}

export function cleanupStageLabel(stage: string) {
  const labels: Record<string, string> = {
    validated: "身份已校验",
    channels_stopped: "消息通道已停止",
    miniapp_deleted: "小程序身份已清理",
    identity_replaced: "Agent 身份已替换",
    routing_replaced: "消息路由已替换",
    local_files_deleted: "旧 Agent 文件已清理",
    wechat_account_migrated: "微信账号已迁移",
    openviking_key_rotated: "OpenViking Key 已轮换",
    gateway_restarted: "Gateway 已重启",
    completed: "清理迁移已完成"
  };
  return labels[stage] || stage || "-";
}

export function isLinkExpired(expiresAt?: string | null) {
  if (!expiresAt) {
    return false;
  }
  const time = new Date(expiresAt).getTime();
  return Number.isFinite(time) && time <= Date.now();
}

export function canRevokeBindLink(status: string) {
  return !["cleaning", "cleanup_failed", "connected", "rejected", "expired", "revoked"].includes(status);
}

export async function copyText(value: string, label = "内容") {
  if (!value) return;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value);
    } else {
      const input = document.createElement("textarea");
      input.value = value;
      input.setAttribute("readonly", "readonly");
      input.style.position = "fixed";
      input.style.left = "-9999px";
      document.body.appendChild(input);
      input.select();
      document.execCommand("copy");
      document.body.removeChild(input);
    }
    ElMessage.success(`${label}已复制`);
  } catch {
    ElMessage.error(`复制失败，请手动选中${label}复制。`);
  }
}
