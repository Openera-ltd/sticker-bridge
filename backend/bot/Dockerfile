# The @stickerbridge bot for Render (free web service, 512 MB).
FROM node:22-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium fonts-noto-color-emoji ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV NODE_ENV=production CHROME_PATH=/usr/bin/chromium PORT=10000
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src

USER node
EXPOSE 10000
CMD ["node", "--experimental-strip-types", "--max-old-space-size=128", "src/main.ts"]
