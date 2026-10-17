# Deploy from the repository root with:
# docker build -f tools/xhs-cloud/Dockerfile -t lulu-xhs-readonly .
FROM node:20-bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium fonts-noto-cjk ca-certificates gosu \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY tools/xhs-termux/package.json ./package.json
RUN npm install --omit=dev --no-audit --no-fund

# The upstream package uses puppeteer-core and requires a system Chromium.
# Reuse the audited, allowlisted read-only MCP and its browser launch adapter.
COPY tools/xhs-termux/patch-browser.mjs ./patch-browser.mjs
RUN node patch-browser.mjs
COPY tools/xhs-termux/server.mjs ./server.mjs
COPY tools/xhs-cloud/entrypoint.sh /usr/local/bin/lulu-xhs-entrypoint
RUN chmod 755 /usr/local/bin/lulu-xhs-entrypoint && mkdir -p /data && chown node:node /data

ENV NODE_ENV=production \
    LULU_XHS_BIND_HOST=0.0.0.0 \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    XHS_COOKIES_PATH=/data/cookies.json \
    XDG_CACHE_HOME=/data/.cache \
    XHS_HEADLESS=true \
    PORT=18060

VOLUME /data
EXPOSE 18060
ENTRYPOINT ["/usr/local/bin/lulu-xhs-entrypoint"]
