# Official Go MCP engine, not the older @the-thing Node adapter.
# Build context: lulu1 repository root; Railway Dockerfile path:
# tools/xhs-xpzouying/Dockerfile
# Production deployment MUST supply AUTH_TOKEN and persistent /app/data.
FROM xpzouying/xiaohongshu-mcp:latest

ENV COOKIES_PATH=/app/data/cookies.json \
    HOME=/app/data/home \
    XDG_CONFIG_HOME=/app/data/config

EXPOSE 18060
# Keep the upstream official entrypoint and built-in Chromium. It knows how
# to start the Go MCP and exposes its actual /mcp endpoint on port 18060.
