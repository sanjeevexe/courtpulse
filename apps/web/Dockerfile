FROM node:24.21.0-alpine AS build

WORKDIR /workspace/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci

COPY contracts/openapi/courtpulse-v1.yaml /workspace/contracts/openapi/courtpulse-v1.yaml
COPY apps/web/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.6-alpine

ENV NGINX_ENVSUBST_FILTER=COURTPULSE_AUTH_ISSUER_ORIGIN

COPY apps/web/nginx.conf /etc/nginx/templates/default.conf.template
COPY --chmod=755 apps/web/docker-entrypoint.d/15-validate-auth-issuer-origin.sh /docker-entrypoint.d/15-validate-auth-issuer-origin.sh
COPY --from=build --chown=101:101 /workspace/apps/web/dist/ /usr/share/nginx/html/

USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
