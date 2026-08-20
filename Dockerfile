# The Next.js frontend (Robin's UI) — :3000.
#
# Not a static export. This server holds the `/api/*` and `/oauth2/*` rewrites
# that put the browser and the Java service on one origin, which is what keeps
# the session cookie working and CORS out of the picture. See next.config.mjs.

# ---------------------------------------------------------------------------
# Stage 1 — dependencies
# ---------------------------------------------------------------------------
# Node 22 LTS. Next 16 requires >= 20.9; alpine keeps the runtime layer small and
# `images.unoptimized` means sharp — the one dependency that would want glibc — is
# never loaded.
FROM node:22-alpine AS deps

# pnpm, pinned. The repo has a pnpm-lock.yaml and no `packageManager` field, so
# corepack is told the version explicitly rather than falling back to whatever
# default this Node image happens to carry.
RUN corepack enable && corepack prepare pnpm@11.18.0 --activate

WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./

# --ignore-scripts is the fix for the ERR_PNPM_IGNORED_BUILDS that README.md
# warns about: the two packages with build scripts are msw (a dev-only mock
# server) and sharp (image optimisation, switched off by `images.unoptimized`).
# Neither is needed to build or serve, so skipping their scripts is cheaper and
# more reproducible than approving them.
# Plain `RUN`, not a BuildKit `--mount=type=cache` for the pnpm store: the cache
# mount needs buildx, and without it the classic builder fails the line with "the
# --mount option requires BuildKit". Layer caching keyed on the two files copied
# above gives the same skip-on-no-change behaviour on either builder.
RUN pnpm install --frozen-lockfile --ignore-scripts

# ---------------------------------------------------------------------------
# Stage 2 — build
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

RUN corepack enable && corepack prepare pnpm@11.18.0 --activate

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Where this image's /api/* and /oauth2/* rewrites will point.
#
# A build ARG and not a runtime ENV, because Next resolves next.config.mjs's
# rewrites() during `next build` and writes the result into the route manifest.
# Setting KNOWLEDGE_API_URL on the running container has no effect — the symptom
# is every /api call failing with `ECONNREFUSED 127.0.0.1:8080`, the build-time
# default, while the UI itself serves fine.
#
# Defaults to the compose service name. Override for a different topology:
#   docker build --build-arg KNOWLEDGE_API_URL=https://api.example.com .
ARG KNOWLEDGE_API_URL=http://backend:8080
ENV KNOWLEDGE_API_URL=$KNOWLEDGE_API_URL

# NEXT_TELEMETRY_DISABLED because a build should not phone home from CI.
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

# ---------------------------------------------------------------------------
# Stage 3 — run
# ---------------------------------------------------------------------------
FROM node:22-alpine AS runtime

WORKDIR /app

# `output: 'standalone'` (set in next.config.mjs) means `next build` has already
# traced the exact files the server needs and bundled them with a minimal
# node_modules. Copying that instead of the full dependency tree takes this image
# from ~940 MB to a fraction of it, and leaves no package manager in the runtime.
#
# The three COPYs are all required and standalone splits them deliberately:
# server.js plus traced deps, then the static assets and public/ which the trace
# does not include because nothing imports them.
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public

USER node

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
# 0.0.0.0, not the default: bound to loopback inside the container the port is
# published but nothing outside can reach it. server.js reads both of these.
ENV HOSTNAME=0.0.0.0

EXPOSE 3000

# The server standalone generates — not `pnpm start`, and there is no pnpm here at
# all. Two failures that produced before, both worth not reintroducing:
#
#   - pnpm 11 runs a "deps status check" ahead of a script and re-executes
#     `pnpm install` when it decides node_modules is stale. In a runtime container
#     that is both wrong and impossible: it exited 243 on
#     `EACCES: permission denied, open '/app/_tmp_...'` because the process is not
#     root.
#   - corepack resolves and DOWNLOADS pnpm on first invocation, putting a network
#     fetch on the startup path — and it silently fetched a different version
#     (11.22.0) than the one pinned for the build.
CMD ["node", "server.js"]
