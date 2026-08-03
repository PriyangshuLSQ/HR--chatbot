# Robin — HR Assistant

**Powered by LeadSquared.**

Robin is an AI HR assistant that answers employee questions on leave, payroll,
benefits and compliance — available 24/7, grounded in HR's own documents, and
never guessing. Employees reach it in the portal or from Microsoft Teams.

Robin's face is `public/robin.png`. The UI renders `robin-160.png`, a 22 KB
derivative — the original is 1024×1536 and 2.5 MB, and `images.unoptimized` is set,
so using it directly would fetch 2.5 MB to draw a 30px avatar. Regenerate with
`sips -Z 160 robin.png --out robin-160.png` if you replace the artwork.

## Features

### Employee chat (Robin)
- **24/7 Availability**: Employees can ask HR questions anytime
- **Natural Language Understanding**: Keyword-based matching for intelligent responses
- **Quick Categories**: Browse questions by Leave Management, Payroll, Benefits, Compliance
- **Message History**: Conversations are displayed with smooth animations
- **Escalation Support**: Complex queries can be escalated to HR team
- **Beautiful UI**: Modern design with clean message bubbles and responsive layout

### HR Admin Dashboard
- **Document Upload**: Drag and drop `.docx`, `.txt`, `.md` or `.csv` — HR policies
  become answerable immediately, with no retraining step
- **AI Engine Panel**: Shows which of the three answering modes is live, and the
  one command needed to upgrade
- **Escalation Tracking**: Monitor pending and in-progress escalations
- **Weekly Digest**: Lowest-rated topics, ranked — the queue for knowledge fixes
- **Real-time Stats**: Documents, indexed passages, and pending escalations

### AI (retrieval-augmented generation)

Answers **stream** as the model writes them (`POST /api/ai/ask/stream`, server-sent
events). The first words appear in well under a second on a short answer, against
~6s to wait for a complete one. Two details matter:

- The model signals "not in the documents" with a `NOT_IN_DOCUMENTS` token, so
  output is withheld for the first 24 characters — long enough to rule a refusal
  out. A declined answer therefore leaks **no** text at all.
- The final `answer` event is authoritative and carries mode, confidence and
  citations. A client can ignore every token and still be correct, which is what
  keeps streaming from becoming a second source of truth.


Uploaded documents are chunked, embedded, and searched semantically; a local
open-source model then writes an answer **grounded in the retrieved passages**
and cites them. Nothing is sent to an external service.

Fine-tuning is deliberately *not* used — see
[`docs/AI-SETUP.md`](docs/AI-SETUP.md) for why, and for how to install the
models. **The app runs with nothing installed**, degrading to keyword search
with verbatim quoted passages.

## Technology Stack

- **Frontend**: Next.js 16 with React 19
- **Backend**: Spring Boot 3.5 (Java 21) in `backend/`, proxied at `/api/*`
- **Styling**: Custom CSS with Tailwind utilities (no UI libraries)
- **AI**: Ollama (`nomic-embed-text` + `qwen3.5:4b`) over plain `fetch` — no ML
  dependency in `package.json`. The answer is written by the model in its own
  words from the retrieved passages, never copied out of them; see
  [`docs/AI-SETUP.md`](docs/AI-SETUP.md) for how to judge a model swap
- **Storage**: Passages and vectors in **Qdrant**; tickets, answer ratings and
  chat history in **MongoDB**. Only UI preferences (theme, channel, session) stay
  in localStorage — see [Data storage](#data-storage)
- **Authentication**: Microsoft Entra ID SSO (OAuth2 code flow, handled in the
  backend), with role-based access (Employee / HR Admin) — see
  [Authentication](#authentication)

## Authentication

Microsoft Entra ID, as an OAuth2 authorization-code flow handled by the **backend**
— not the frontend. The browser ends up holding a session cookie and nothing else,
and the same filter chain protects every endpoint. Doing it in Next.js would have
left the Java service reachable unauthenticated, which is the whole problem it was
meant to solve.

| Path | Purpose |
|---|---|
| `GET /oauth2/authorization/azure-ad` | starts the flow (full-page navigation) |
| `GET /api/auth/callback/azure-ad` | Microsoft's callback; the code is exchanged here |
| `GET /api/auth/me` | current session — public, so the login page can ask |
| `POST /api/auth/logout` | clears the session and cookie |

The callback path is the NextAuth convention (`/api/auth/callback/<provider>`)
rather than Spring's default `/login/oauth2/code/<id>`, because that is what the
Entra app registration lists — Spring's redirection endpoint is pointed at it. A
side benefit: being under `/api` means the existing proxy rule already forwards it,
so only the `/oauth2/**` kickoff needed adding to `next.config.mjs`. Everything
stays on one origin, so the session cookie belongs to `:3000`.

The registration key in `application.yml`, the last segment of the callback URI,
and `REGISTRATION_ID` in `AuthController` are all `azure-ad` and must agree — that
segment is how Spring matches a callback back to its client config.

**Authorization**

- `/api/knowledge/**` and `/api/feedback/**` — HR admin only
- `/api/**` — any signed-in employee
- `GET /api/tickets` — the whole queue for an admin, **your own escalations only**
  otherwise. An employee reading the full list would be reading colleagues'
  escalations, including the confidential ones.
- `/api/threads` — scoped to the session. There is no owner parameter any more;
  it used to be one, which meant anyone who could reach the API could ask for
  anyone's chat history.
- Ticket attribution (`raisedBy`) is stamped from the session, not the request
  body. HR acts on these, and a ticket naming the wrong employee sends someone to
  discuss the wrong person's pay.

**Who is an HR admin.** An Entra app role named `HR.Admin` (or a `roles`/`groups`
claim containing `hr_admin`) wins. Failing that, the `hr.auth.admin-emails` list.
Everyone else in the tenant is an employee. The role is never chosen by the client
— the tab on the login page only affects the demo path.

**Configuration** (`backend/application-local.yml`, gitignored, or the
environment):

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          azure-ad:
            client-id: <application (client) ID>
            client-secret: <client secret value>
        provider:
          azure-ad:
            issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
            user-name-attribute: preferred_username
hr:
  auth:
    admin-emails: someone@yourcompany.com
```

In the Entra app registration, `http://localhost:3000/api/auth/callback/azure-ad`
must be listed as a redirect URI **under the "Web" platform** — not "Single-page
application". A SPA registration rejects the code exchange with `AADSTS9002327`,
because this is a confidential client using a secret. Set `APP_BASE_URL` and
register the matching URI for any other host.

**With no client id configured** the service runs open and the frontend's demo
login works, exactly as it did before SSO — so a checkout without the secret is
still usable. It logs a warning on every boot, and `/api/auth/me` reports
`ssoEnabled: false` so the login page hides the Microsoft button rather than
offering one that cannot work. `AUTH_ENABLED=false` forces that mode.

**Known gap:** CSRF tokens are not threaded through the frontend's fetches. The
session cookie is `SameSite=Lax`, so another origin cannot make the browser attach
it to a POST, which is the attack those tokens defend against here. Worth closing
before this is exposed outside the office network.

## Data storage

| What | Where | Why |
|---|---|---|
| Documents, passages, embeddings | Qdrant (`hr_chunks`, `hr_docs`) | Vector search |
| Escalation tickets | MongoDB `tickets` | HR triages these from any machine |
| Answer ratings | MongoDB `feedback` | Feeds the weekly digest; anonymous by design |
| Chat history | MongoDB `threads`, scoped per employee | Survives a reload and a device change |
| Theme, channel | localStorage | Per-browser preference, not data |
| Session | `JSESSIONID` cookie, server-side | Identity is not the client's to assert |

Tickets, ratings and chat history were in localStorage until the MongoDB
migration. That meant the admin dashboard only ever saw escalations raised in
that same browser profile — the panels looked populated because the store fell
back to hardcoded demo rows whenever it was empty. Those demo rows now live in
`DemoDataSeed.java` and are written once, on first boot against an empty
database, so an empty dashboard means an empty queue.

Set the connection string in `backend/application-local.yml` (gitignored) or as
`MONGODB_URI` in the environment. Without it the service still boots and answers
questions; ticket and feedback endpoints return 503 and the dashboard says so
rather than showing an empty queue.

## Routes

- `/login` - Login page for both employees and HR admins
- `/chat` - Employee chat interface (requires employee auth)
- `/admin` - HR admin dashboard (requires HR admin auth)

## Signing in

With Entra configured (the normal case), there is one way in: **Sign in with
Microsoft**, using your work account. Whether you land on `/chat` or `/admin`
follows your account, not a choice on the login page.

The demo credentials below only work when no `client-id` is configured — the login
page hides the email form entirely under SSO, because a form the backend will
reject reads as a broken app rather than a locked one.

<details>
<summary>Demo credentials (no-SSO mode only)</summary>

**Employee:** `employee@company.com` / `demo` — or the "Employee" quick login
**HR Admin:** `hr@company.com` / `demo` — or the "HR Admin" quick login
</details>

## Key Files

- `app/chat/page.tsx` - Employee chat interface
- `app/admin/page.tsx` - HR admin dashboard
- `app/login/page.tsx` - Login page
- `lib/nlu.ts` - Offline intent matcher (spell correction, synonyms, IDF scoring)
- `lib/conversation.ts` - Dialogue orchestration and answer precedence
- `lib/knowledge/api.ts` - Client for the knowledge-base endpoints
- `lib/hr-store.ts` - Ticket and feedback store (server-backed cache) + digest
- `lib/hr-api.ts` - Client for the ticket, feedback and chat-history endpoints
- `lib/chatbot-auth.tsx` - Session context: reads `/api/auth/me`, no client-side identity
- `backend/src/main/java/.../security/` - Entra sign-in, role mapping, URL rules
- `app/globals.css` - Custom styling and design tokens
- `backend/src/main/java/.../tickets/Routing.java` - The only definition of who an
  escalation is assigned to, and whether it is confidential

## How It Works

**Every policy answer is generated by the model from HR's uploaded documents,
and cited.** There are no hand-written answers. A question is resolved in strict
order of precedence:

1. **Sensitive matters** (harassment, grievances) route straight to the HRBP or
   HR Head with a scripted response. These bypass every threshold and never reach
   a model — a paraphrased or missed harassment report is not a recoverable
   error, so that one path stays deterministic on purpose.
2. **The employee's own records** — leave balance, payslip, request status, live
   from Darwinbox. No document contains someone's balance.
3. **Uploaded documents** via RAG: retrieved, written up by the local model,
   cited back to the passage.
4. **One clarifying question** where the subject is genuinely ambiguous. Choosing
   an option narrows the *search*; it does not select a stored answer.
5. **Otherwise the bot says the documents don't cover it** and offers a human.

The bot never presents a weak match as an answer: below the relevance floor it
says so and offers a human, which is the correct behaviour when a wrong answer
costs someone real leave or money. It will also not answer from the model's
general knowledge — an answer about *this company's* notice period that nobody
here wrote is worse than no answer.

The consequence is direct: **the assistant knows exactly what you upload.** A
thin corpus means a lot of escalations. That is the intended failure mode — the
fix is uploading the policy, and the weekly digest shows which topics are missing.

`lib/nlu.ts` still classifies topics (it drives the digest's grouping, spelling
correction, and the two special cases above), but it holds no answers.

### Escalation System
- Anything the documents don't cover is flagged as an escalation
- HR admins can view pending escalations in the dashboard
- Escalations can be marked as in-progress or resolved
- Tracks query, reason, NLU confidence, and assignment status

## Customization

To add knowledge, upload documents in **Admin → Knowledge base**, or use the
"Add an answer by hand" form. Both are stored server-side and visible to every
employee immediately.

Tuning knobs:

- Intent-matching thresholds: `HIGH_CONFIDENCE` / `LOW_CONFIDENCE` in `lib/nlu.ts`
- Document-retrieval thresholds: `RELEVANCE_THRESHOLD` / `STRONG_RELEVANCE` in
  `lib/knowledge/types.ts`
- Chunk size and overlap: `lib/knowledge/chunk.ts`
- The grounding prompt: `SYSTEM_PROMPT` in `lib/knowledge/rag.ts`
- Models: `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBED_MODEL` in the backend's environment
  (defaults in `backend/src/main/resources/application.yml`)
- The grounding rules — synthesise in own words, answer the question asked, never
  calculate: `SYSTEM_PROMPT` in `backend/.../rag/RagService.java`

## Deployment

Two processes. The backend first, since the frontend proxies `/api/*` to it:

```bash
cd backend && mvn spring-boot:run    # :8080 — needs Qdrant and MongoDB
```

```bash
pnpm install
pnpm dev              # http://localhost:3000
pnpm typecheck
pnpm test:nlu         # topic-classification accuracy
pnpm test:precedence  # proves no answer is served from source
pnpm build && pnpm start
```

If your pnpm refuses to run scripts with `ERR_PNPM_IGNORED_BUILDS`, run
`pnpm approve-builds` once (it is `msw` and `sharp`), or call the binaries
directly: `./node_modules/.bin/next dev`.

Note this is **no longer a static app** — the frontend needs a Node server (not a
static export) for the API proxy, and the knowledge base, tickets and chat
history all live in the Java service behind it.

For the AI setup, see [`docs/AI-SETUP.md`](docs/AI-SETUP.md).
