ARG NODE_IMAGE
ARG NGINX_IMAGE

FROM ${NODE_IMAGE} AS dev
ARG PNPM_VERSION
RUN npm install --global "pnpm@${PNPM_VERSION}"
WORKDIR /app
# Without a TTY pnpm aborts when it needs to confirm removing node_modules; CI mode makes it non-interactive.
ENV CI=true
EXPOSE 5173

FROM dev AS build
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build

FROM ${NGINX_IMAGE} AS runtime
# The pinned base lags Alpine security fixes (fixable HIGH CVEs in musl and zlib fail the Trivy gate).
USER 0
# hadolint ignore=DL3017
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
USER 101
