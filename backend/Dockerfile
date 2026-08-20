# The Spring Boot knowledge service (Robin's backend) — :8080.
#
# Two stages, because the build needs Maven and a full JDK and the runtime needs
# neither: shipping the builder would triple the image and put a compiler next to
# the employee extract.

# ---------------------------------------------------------------------------
# Stage 1 — compile
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# pom.xml alone first, so the dependency download layer is keyed on the pom and
# survives every source edit. Without this split, changing one .java file
# re-resolves Spring Boot, PDFBox, POI and the Anthropic SDK from scratch.
COPY pom.xml .

# Plain `RUN`, not a BuildKit `--mount=type=cache`. The cache mount is the faster
# idiom, but it silently requires buildx — without it the classic builder fails
# this line outright with "the --mount option requires BuildKit", which is a poor
# thing for a `docker compose up --build` to hit. This works on both builders:
# the downloaded repository becomes an ordinary layer, keyed on pom.xml above, so
# editing sources still skips the download.
#
# Best-effort by design. go-offline does not see plugin dependencies that
# `package` resolves lazily, so it can report failure on a pom that builds fine;
# anything it misses is fetched in the next step. A warmup must not be able to
# fail the build.
RUN mvn -B dependency:go-offline || true

COPY src ./src

# Tests are not run here. They belong in CI, where a failure blocks a merge; in
# an image build they only turn a deploy into a slow, confusing failure — and
# CrossEmployeeAccessTest needs fixtures this context does not carry.
RUN mvn -B -DskipTests package \
 && cp target/hr-chatbot-robin-backend-*.jar /build/app.jar

# ---------------------------------------------------------------------------
# Stage 2 — run
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# curl is for the compose healthcheck only. The JRE image has no HTTP client and
# the alternative — a shell /dev/tcp probe — reports a listening socket as
# healthy, which the backend is well before Spring finishes wiring Mongo.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Non-root. This process parses uploaded PDFs and .xlsx workbooks with PDFBox and
# POI — the two dependencies here most likely to be handed a malicious file — so
# it should not be running as uid 0 when one of them is.
RUN useradd --system --create-home --uid 10001 robin
USER robin

WORKDIR /app

COPY --from=build --chown=robin:robin /build/app.jar /app/app.jar

# WORKDIR matters: application.yml imports `optional:file:./application-local.yml`,
# resolved relative to the working directory. Nothing is copied there — mount the
# file at /app/application-local.yml to use it, or set the environment variables
# instead (the documented path; see docker-compose.yml).

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# `exec` form, so java is PID 1 and receives SIGTERM directly. Behind a shell it
# would not, and `docker compose down` would hit the 10s timeout and SIGKILL on
# every stop instead of shutting down gracefully.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
