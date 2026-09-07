# Deploying Robin to a single EC2 host

Runbook for the whole stack on one instance, behind TLS. Everything here uses
`docker-compose.prod.yml`, which overlays the base compose file — see the comments in it
for what each override is for.

## 1. Instance

**t3.large (2 vCPU, 8 GB)** is the comfortable size. The runtime fits in less, but the
*build* is the peak: `next build` and Maven's dependency resolution together will exhaust a
4 GB box, and the failure looks like a killed compiler rather than an out-of-memory error.

| | resident |
|---|---|
| Spring Boot (capped at 2 GB in the overlay) | ~1 GB |
| Ollama with `nomic-embed-text` | ~0.3 GB warm |
| Qdrant | small — under 100 MB at this corpus size |
| Next.js standalone | ~250 MB |
| Caddy | negligible |

If you want a **t3.medium**, build the images elsewhere and push to ECR rather than
building on the instance.

Storage: 30 GB gp3 is plenty. Docker images are the bulk of it, not the data.

**No GPU needed.** Embedding is the only local model work, and `nomic-embed-text` on CPU
answers in well under a second at this volume.

## 2. Security group

Inbound: **80 and 443 only**, plus 22 from your IP.

Do not open 3000, 8080, 6333, 27017 or 11434. The overlay stops publishing them at all, so
the app is unreachable on those ports even if a rule is added by mistake — but leaving the
rules closed means two things have to go wrong rather than one.

## 3. Install Docker

```bash
sudo dnf install -y docker git            # Amazon Linux 2023
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user          # log out and back in
docker compose version                    # v2 ships with the plugin
```

## 4. Get the code and write the secrets

```bash
git clone <your-repo> robin && cd robin
cp .env.example .env
```

Edit `.env`. Nothing secret belongs anywhere else — not in the compose files, not in an
image layer, not in a shell command where it lands in `~/.bash_history`.

```bash
ANTHROPIC_API_KEY=sk-ant-...
MONGODB_URI=mongodb+srv://user:pass@cluster.mongodb.net/
APP_BASE_URL=https://robin.example.com
ADMIN_EMAILS=                             # leave empty; authorization comes from IAM
SPRING_AUTOCONFIGURE_EXCLUDE=             # the overlay sets this; do not re-add it
SPRING_APPLICATION_JSON={"spring":{"security":{"oauth2":{"client":{"registration":{"azure-ad":{"client-id":"...","client-secret":"..."}},"provider":{"azure-ad":{"issuer-uri":"https://login.microsoftonline.com/<tenant>/v2.0"}}}}}}}
```

The Entra credentials go in as `SPRING_APPLICATION_JSON` because the registration id
`azure-ad` contains a hyphen, and Spring strips hyphens when binding from the environment —
`SPRING_..._AZURE_AD_CLIENT_ID` would define a registration called `azuread`, which no
longer matches the callback path. JSON keys survive intact.

`chmod 600 .env`.

## 5. Register the redirect URI in Entra — before you deploy

Sign-in will fail without this, and the error is unhelpful.

Add to the app registration, under the **Web** platform (not Single-page application — a SPA
registration rejects the code exchange with `AADSTS9002327`, because this is a confidential
client using a secret):

```
https://robin.example.com/api/auth/callback/azure-ad
```

It must match `APP_BASE_URL` exactly, including the scheme. Keep the localhost URI
registered alongside it so local development still works.

## 6. Point the Caddyfile at your hostname

Replace `robin.example.com` in `Caddyfile` with the real one, and make sure an A record
points at the instance's public IP **before** first start — Caddy requests the certificate
on boot, and a hostname that does not resolve fails the challenge and retries with backoff.

No domain yet? Change the site line to `:80`, leave `COOKIE_SECURE` unset, and expect the
login to loop rather than error: a `Secure` cookie is simply not sent over plain http.

## 7. Start it

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

First run takes several minutes: it compiles the Java service, builds the Next bundle, and
pulls the 274 MB embedding model. `ollama-pull` exiting `0` is success, not a crash.

## 8. Verify, in this order

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

Then check the things most likely to be wrong:

```bash
# Sign-in is ON, not running open. If AUTH_REQUIRED could not be satisfied the
# service refuses to start — so a running backend is itself part of the evidence.
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs backend \
  | grep -i 'entra sign-in'

# The authorization redirect carries the right client and callback.
curl -sI https://robin.example.com/oauth2/authorization/azure-ad | grep -i location
```

Then sign in through the browser. That round trip is the only real test of the Entra
configuration, and nothing on the instance can stand in for it.

## 9. Load the data

The corpus and the employee records do not travel with the image.

- **Policy documents** — Admin → Knowledge base → upload. Or copy `./data` to the instance
  before first start: it is mounted read-only at `/data`, and an empty Qdrant imports
  `/data/knowledge.json` once on boot.
- **Employee extract** — put the workbooks on the instance, set `HR_EXTRACT_DIR` to a path
  under the mounted `./data`, then Admin → Knowledge base → **Employee data → Re-import
  extract**.

## 10. What survives a restart, and what does not

Named volumes hold Qdrant's index and Ollama's models, so `docker compose down` keeps them
and `down -v` destroys them. Tickets, ratings and chat threads are in Atlas and are
unaffected either way.

The index is rebuildable from the source documents; Atlas is not rebuildable from anything
here. Back up Atlas, and keep the extract workbooks somewhere other than this instance.

## Gotchas specific to this project

**`KNOWLEDGE_API_URL` is a build argument, not a runtime one.** Next resolves the rewrites
in `next.config.mjs` during `next build` and freezes them into the route manifest. It is
already set to `http://backend:8080`, which is correct here because both containers share
the compose network — but if you ever split them across hosts, the frontend image has to be
*rebuilt*, not reconfigured. The symptom is the UI loading fine while every `/api` call
fails with `ECONNREFUSED 127.0.0.1:8080`.

**CSRF tokens are still not threaded through the frontend fetches.** The session cookie is
`SameSite=Lax`, which is what stops another origin making the browser attach it to a POST,
so this is defensible on an internal host. It is worth closing before the app is reachable
from the public internet by people outside the company.

**Qdrant and Ollama have no authentication.** They are on the compose network only, never
published. Keep it that way; neither is safe to expose.

**One instance is one point of failure.** Fine for a pilot. Before it matters to 5,000
people, the ordering is: Atlas is already off-box, move Qdrant to a managed instance or an
EBS volume you can reattach, and put the frontend and backend behind an ALB. Ollama is the
awkward one — it is the only stateful-ish local dependency, and replacing it with a hosted
embedding provider means re-embedding the whole corpus, because a different model puts
queries in a different vector space.
