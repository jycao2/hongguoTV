FROM node:22-bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/jycao2/hongguoTV" \
      org.opencontainers.image.description="HongguoTV home content bridge" \
      org.opencontainers.image.licenses="GPL-3.0-only"
WORKDIR /app
COPY server/package*.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY LICENSE ./LICENSE
COPY server/ ./server/
ENV BRIDGE_HOST=0.0.0.0 PORT=8787
EXPOSE 8787
USER node
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "server/healthcheck.mjs"]
CMD ["node", "server/index.mjs"]
