# The live @stickerbridge bot for Render's free plan (0.1 CPU, 512 MB).
# Uses Chrome's lightweight "headless shell" build and keeps one TikTok Messages tab open.
FROM node:22-slim

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

# Chrome headless shell matching puppeteer-core, with the system libraries it needs.
RUN npx --yes @puppeteer/browsers@2.13.2 install chrome-headless-shell@155.0.8059.39 --path /opt/chrome --install-deps \
 && ln -s "$(find /opt/chrome -type f -name chrome-headless-shell | head -1)" /usr/local/bin/chrome-headless-shell \
 && apt-get install -y --no-install-recommends fonts-noto-color-emoji ca-certificates \
 && rm -rf /var/lib/apt/lists/* /root/.npm

ENV NODE_ENV=production PORT=10000 CHROME_PATH=/usr/local/bin/chrome-headless-shell
COPY src ./src
USER node
EXPOSE 10000
CMD ["node", "--experimental-strip-types", "--no-warnings", "--max-old-space-size=96", "src/live.ts"]
