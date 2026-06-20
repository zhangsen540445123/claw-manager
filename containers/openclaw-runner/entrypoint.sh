#!/bin/sh
set -eu

mkdir -p "${OPENCLAW_HOME}" /workspace

if [ ! -f "${OPENCLAW_CONFIG_PATH}" ]; then
  cat > "${OPENCLAW_CONFIG_PATH}" <<'EOF'
{
  "gateway": {
    "bind": "lan",
    "port": 18789,
    "controlUi": {
      "enabled": true,
      "root": "/usr/local/lib/node_modules/openclaw/dist/control-ui",
      "allowInsecureAuth": true,
      "dangerouslyDisableDeviceAuth": true,
      "dangerouslyAllowHostHeaderOriginFallback": true
    }
  },
  "agents": {
    "defaults": {
      "workspace": "/workspace"
    }
  },
  "session": {
    "dmScope": "per-account-channel-peer"
  }
}
EOF
fi

exec openclaw gateway --allow-unconfigured --bind lan --port "${OPENCLAW_PORT}"
