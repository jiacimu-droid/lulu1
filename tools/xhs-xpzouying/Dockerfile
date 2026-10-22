# Official Go MCP engine, not the older @the-thing Node adapter.
# Build context: lulu1 repository root; Railway Dockerfile path:
# tools/xhs-xpzouying/Dockerfile
# Production deployment MUST supply AUTH_TOKEN and persistent /app/data.
FROM xpzouying/xiaohongshu-mcp:latest

ENV COOKIES_PATH=/app/data/cookies.json \
    HOME=/app/data/home \
    XDG_CONFIG_HOME=/app/data/config

COPY tools/xhs-xpzouying/entrypoint.sh /usr/local/bin/lulu-xhs-launch
RUN chmod 755 /usr/local/bin/lulu-xhs-launch

EXPOSE 18060
# Upstream runs through tini. Keep its subreaper to avoid Chrome zombies, but
# refuse startup if AUTH_TOKEN is missing: upstream itself permits no auth.
ENTRYPOINT ["/usr/bin/tini", "-s", "--", "/usr/local/bin/lulu-xhs-launch"]
CMD []
