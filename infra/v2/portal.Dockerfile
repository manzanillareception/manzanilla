# syntax=docker/dockerfile:1
FROM node:22-bookworm-slim AS build
WORKDIR /build
COPY admin-v2/package.json admin-v2/package-lock.json ./
RUN --mount=type=secret,id=proxy_ca \
    if [ -s /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; \
    npm ci --strict-ssl=true
COPY admin-v2/ ./
RUN npm run build

FROM caddy:2-alpine
RUN addgroup -g 10001 manzanilla \
    && adduser -D -H -u 10001 -G manzanilla manzanilla \
    && mkdir -p /srv /data /config \
    && chown -R 10001:10001 /srv /data /config
COPY --from=build /build/dist/ /srv/
COPY infra/v2/Caddyfile /etc/caddy/Caddyfile
USER 10001:10001
EXPOSE 8080 8443
