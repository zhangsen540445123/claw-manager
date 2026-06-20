import QRCode from "qrcode";

export async function renderQrDataUrl(value: string, size = 220) {
  if (!value.trim()) return "";
  return QRCode.toDataURL(value, {
    width: size,
    margin: 1,
    errorCorrectionLevel: "M"
  });
}
