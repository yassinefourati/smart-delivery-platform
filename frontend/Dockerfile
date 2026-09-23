# The SPA image: build with Node, serve with nginx. docs/frontend.md#production-topology.

# ---- build ------------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

# The lockfile alone first, so the dependency layer is reused until dependencies change.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# The same gate CI runs, so an image cannot exist for code that fails it.
RUN npm run typecheck && npm run lint && npm test && npm run build \
    # Hidden source maps are for error reporting, not for serving to the public.
    && find dist -name '*.map' -delete

# ---- runtime ----------------------------------------------------------------------------
FROM nginx:1.27-alpine

# Our nginx.conf replaces the image's: it writes only under /tmp and listens on 8080, so it
# runs unprivileged on a read-only root filesystem. default.conf would listen on 80.
RUN rm -f /etc/nginx/conf.d/default.conf && mkdir -p /etc/nginx/sdp
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html

# Numeric, like the Java images, so Kubernetes' runAsNonRoot can verify it.
USER 1000:1000
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/index.html || exit 1

# nginx directly, not the image's entrypoint: its helper scripts rewrite files under
# /etc/nginx, which is read-only here and has nothing for them to do.
ENTRYPOINT ["nginx", "-g", "daemon off;"]
