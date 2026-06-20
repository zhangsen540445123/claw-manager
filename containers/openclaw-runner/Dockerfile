FROM node:22-bookworm-slim

ARG OPENCLAW_VERSION=2026.6.8

LABEL io.clawbot.openclaw.version="${OPENCLAW_VERSION}"

ENV OPENCLAW_HOME=/var/lib/openclaw \
    OPENCLAW_CONFIG_PATH=/var/lib/openclaw/openclaw.json \
    OPENCLAW_CONFIG=/var/lib/openclaw/openclaw.json \
    OPENCLAW_STATE_DIR=/var/lib/openclaw/.openclaw \
    OPENCLAW_PORT=18789 \
    OPENCLAW_VERSION="${OPENCLAW_VERSION}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g "openclaw@${OPENCLAW_VERSION}"

WORKDIR /workspace

COPY containers/openclaw-runner/entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh \
    && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
