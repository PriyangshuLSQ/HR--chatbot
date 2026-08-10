# The AI layer — how it works and how to turn it on

## Why RAG and not fine-tuning

The obvious-sounding approach is "train a model on our HR data". For policy
Q&A that is the wrong tool, for three concrete reasons:

- **Fine-tuning teaches style, not facts.** A model fine-tuned on the leave
  policy learns to *sound like* the leave policy. Asked for a number it has
  half-memorised, it will produce a confident, plausible, wrong one. For an
  answer about someone's notice period or gratuity, that is worse than no
  answer.
- **No citations.** A fine-tuned model cannot show which clause an answer came
  from, so nobody — employee or HR — can verify it.
- **Every edit means a retraining run.** Change a carry-forward cap and the
  model keeps repeating the old one until someone retrains and redeploys.

**Retrieval-augmented generation (RAG)** inverts this. HR's documents stay the
source of truth; the model only ever paraphrases text handed to it at question
time:

```
upload  →  parse  →  chunk  →  embed  →  store
                                            │
question  →  embed  →  retrieve top passages ┘
                          │
                          └→  model writes an answer grounded in those passages
                                            │
                                            └→  answer + the passages it used
```

An uploaded document is answerable the moment it lands. Edit it, re-upload, and
the answer changes. Every answer shows its sources.

## Running with no AI installed

**The app works with nothing installed.** That is deliberate, not a fallback
bolted on afterwards. With no model available:

- retrieval degrades from hybrid (semantic + keyword) to **BM25 keyword search**
- answers degrade from *written* to **extractive** — the matching policy
  passage, quoted verbatim with its heading trail

Uploads, search, citations, escalation and the admin console all still work.
The AI panel in **Admin → Knowledge base** always shows which of the three
modes you are actually in.

| Mode | Requires | Employee sees |
|---|---|---|
| `keyword` | nothing | The matching passage, quoted |
| `semantic` | a local embedding model | The matching passage, found by meaning |
| `generative` | embedding model + Anthropic API key | A written answer, plus its sources |

## The two halves, and why only one of them is hosted

Answer writing runs on the **Claude API**. Embedding runs on a **local Ollama
daemon**, and that split is not a half-finished migration — it is forced:

- Anthropic has no embeddings endpoint.
- Both Qdrant collections hold 768-dimensional `nomic-embed-text` vectors.
- A query embedded by any other model lands in a different vector space. Cosine
  similarity against the stored vectors then returns *plausible nonsense* — not an
  error, just the wrong passages, which for an HR answer is the worst failure
  available.

So keeping the existing index means keeping the embedder local. Removing Ollama
entirely is a corpus migration — pick a hosted embedding provider, change
`knowledge.qdrant.vector-size`, drop both collections and re-embed every chunk —
not a config change.

What did go away: the 3.4 GB chat model, its context-window and output-token
tuning, and the RAM contention that made the first question after a lull slow.

## Turning on the full AI

```bash
# 1. Install Ollama — https://ollama.com/download
#    macOS: brew install ollama && brew services start ollama

# 2. Pull the embedding model (274 MB, one time). No chat model is needed.
ollama pull nomic-embed-text

# 3. Confirm it is up
curl http://127.0.0.1:11434/api/tags

# 4. Set the Anthropic key — in application-local.yml (gitignored) or the environment
export ANTHROPIC_API_KEY=sk-ant-...
```

If you are coming from the local-model setup, `ollama rm qwen3.5:4b` reclaims the
3.4 GB. Nothing reads it any more.

Then open **Admin → Knowledge base** and press **Rebuild index**. That embeds
any documents uploaded before the embedding model was installed. The badge should
flip to *Full AI · generating answers*.

### Choosing a model

`claude-haiku-4-5` is the default: fastest current model, $1/$5 per MTok against
Opus 5's $5/$25, and this task is well within it. Most of the speed is not the model
being small — it is that Haiku 4.5 does not think by default, so there is no
pre-answer thinking phase. On Opus 5 that phase was ~4.6s of the ~8s an answer took.

`claude-opus-5` is the switch back if a wrong HR answer starts costing an employee
real money or leave. Run the grounding cases below before trusting either model; the
two hard ones are what a smaller model fails first.

Two knobs behave differently on Haiku 4.5, and neither errors:

- **`effort` does not exist on it** and would return a 400, so it is not sent. The
  admin console shows the effort as `—`. It applies again on Opus 5.
- **The minimum cacheable prefix is 4096 tokens**, against 512 on Opus 5. This system
  prompt is ~1.2k, so prompt caching silently stops applying — `cache=read 0` in the
  generate log is expected here, not a regression. Haiku is cheaper per token anyway.

Tuning knobs, all environment-overridable:

```bash
CLAUDE_MODEL=claude-opus-5            # default claude-haiku-4-5
KNOWLEDGE_CLAUDE_EFFORT=low           # low | medium | high | xhigh | max; ignored on Haiku 4.5
CLAUDE_MAX_ANSWER_TOKENS=2000         # thinking AND visible answer, together
CLAUDE_CACHE_SYSTEM_PROMPT=true       # inert on Haiku 4.5 (see above)
```

`KNOWLEDGE_CLAUDE_EFFORT` is deliberately not named `CLAUDE_EFFORT`: Claude Code
injects a variable by that name into its own session environment, so a backend
launched from inside one silently ran at `high` instead of `low`.

`effort` is the main latency lever now that there is no local generation to tune.
`low` is the default because the model is restating passages retrieval already
selected, not solving anything. Raise it if the grounding cases start failing.

**`max-answer-tokens` is not the 250 the local model had, and must not be.** It
bounds thinking *and* the visible answer together; thinking is on by default on
Claude Opus 5 and can consume most of a small budget before a single visible
character. A ceiling sized to the answer alone truncates it mid-sentence. You are
billed for what is produced, not for the headroom.

**Do not "fix" latency by disabling thinking.** On Claude Opus 5 that risks internal
reasoning tags leaking into the visible response — which for this product means a
reasoning trace shown to an employee as their HR answer, exactly the failure the
old `<think>` stripping existed to prevent. Lower `effort` instead; it is cheaper
and has no such failure mode.

### Reading the generate log

`ClaudeClient` logs one line per answer:

```
generate model=… effort=… first-token=…ms total=…ms in=…tok out=…tok cache=read …/write … stop=…
```

- **`first-token`** is what makes a turn feel fast — network round trip plus
  whatever thinking the effort level bought.
- **`in` / `out`** are the bill.
- **`cache=read N`** is the one to watch after any config change. The system prompt
  is identical every turn and is marked cacheable; a read count stuck at `0` across
  back-to-back questions means something upstream is varying the prefix, and input
  cost has silently multiplied.

### Judging a model swap

Model size is not the thing that matters; following the grounding prompt is. Two
cases separate a usable model from an unusable one, and both came from real
failures — run them after any change:

1. **"what happens when I resign mid year?"** — retrieval returns the Variable Pay
   FAQ entry for *"what happens if I move roles mid-year?"* as the top hit. A weak
   model answers that one instead, verbatim. A good one answers about resigning and
   combines the passages that actually bear on it.
2. **"I have 34 days of earned leave, how many will I lose"** — the answer must
   state the 30-day cap and stop. A model that computes "you will lose 4 days" is
   doing arithmetic the prompt forbids, on a figure that may be stale.

Also check that an off-corpus question ("what is the wifi password") still comes
back as a refusal rather than a helpful-sounding guess.

The history, kept because it is easy to assume small is fine: `llama3.2` (3B) was
rejected on exactly those cases, back when generation was local. The theory had been
that grounded rewriting is easy — the model only has to restate a passage, not
*know* anything. That was wrong in a way worth recording, because it is not a
prose-quality problem, it is a correctness one. The same test applies to a hosted
model at a lower effort setting: run the cases, do not assume.

### The embedding model

```bash
OLLAMA_EMBED_MODEL=nomic-embed-text
OLLAMA_URL=http://127.0.0.1:11434
```

**Changing this invalidates every vector in Qdrant, and run Rebuild index if you
do.** A different width fails loudly — Qdrant rejects the search against
`vector-size: 768`. A same-width different model fails *silently*, which is worse:
the numbers are comparable, the meanings are not, and search quietly degrades.

If the configured embedding model is not installed, the app falls back to any
installed embedding model rather than failing. That is a convenience for a fresh
index and a hazard for an existing one — if `embedModel` in the admin panel is not
what built the collection, reindex.

## Where things live

| Path | Role |
|---|---|
| `backend/.../parse/DocumentParser.java` | `.docx` / `.csv` / `.txt` / `.md` → Markdown |
| `backend/.../text/Chunker.java` | Markdown → retrieval-sized passages, split on headings, with overlap |
| `backend/.../ollama/OllamaClient.java` | The only place that talks to a model. Also `think: false` and `<think>` stripping |
| `backend/.../retrieve/Retriever.java` | Hybrid BM25 + cosine scoring |
| `backend/.../rag/RagService.java` | Ingest, the grounding prompt, streaming, refusal handling |
| `backend/.../store/QdrantStore.java` | Passages and vectors in Qdrant |
| `lib/conversation.ts` | Decides what runs at all (see below) |

Passages and vectors live in Qdrant, not on disk here. `data/knowledge.json` is
only the pre-migration corpus, read once at first boot; it is gitignored because it
holds real HR policy content.

## How RAG fits into the existing bot

RAG does **not** replace the hand-written intents — it runs where they fall
the whole answer. Order of precedence in `lib/conversation.ts`:

1. **Sensitive matters** (harassment, grievances) — routed to a human immediately,
   with a scripted reply. These never reach a model, by design.
2. **The employee's own records** — leave balance, payslip, request status, live
   from Darwinbox. No document holds these.
3. **RAG over uploaded documents** — every policy answer.
4. **One clarifying question** where the subject is genuinely ambiguous.
5. Otherwise: say the documents do not cover it, and offer a human.

There are no hand-written answers behind step 3 any more. `lib/nlu.ts` classifies
topics — for the weekly digest's grouping, spelling correction, and the two special
cases above — but holds no answers.

Two thresholds gate step 3, duplicated in `lib/knowledge/types.ts` and
`backend/.../rag/Thresholds.java` (keep them in step):

- `RELEVANCE_THRESHOLD` (0.34) — below this the corpus does not cover the question
  and the bot escalates instead of guessing.
- `STRONG_RELEVANCE` (0.55) — a document only pre-empts a clarifying question at
  this level.

## Guardrails on the generated answer

`SYSTEM_PROMPT` in `backend/.../rag/RagService.java` is the safety boundary:

- answer only from the supplied extracts, never from the model's own knowledge
- never invent a number, date, amount or deadline
- answer the question *asked*, not the nearest retrieved one, and summarise across
  every relevant extract rather than copying one
- never calculate — state the rule and its threshold, and let the employee apply it
- emit `NOT_IN_DOCUMENTS` when the extracts do not cover the question. The code
  checks for that token *and* for prose non-answers ("the documents do not
  specify..."), downgrading both to "no answer" so an uncovered question reaches a
  human rather than a confident guess
- `temperature: 0.1`, because the job is faithful restatement, not creativity

Citations are always rendered under the answer. An AI-written answer about
someone's leave entitlement is only trustworthy if they can read the policy
text it came from.

## Supported uploads

| Format | Handling |
|---|---|
| `.docx` | Heading styles become section boundaries and the citation trail; numbered/bulleted paragraphs preserved. Images are skipped (and reported) |
| `.csv` / `.tsv` | A sheet with `Question`/`Answer` columns becomes one entry per row. Any other sheet becomes one labelled record per row |
| `.md` | Headings used directly |
| `.txt` | Split on paragraphs |

Not supported: `.pdf`, legacy `.doc`, `.xlsx`. Export to one of the above.
A `.doc` renamed to `.docx` is detected and reported rather than failing
obscurely.

Re-uploading the same filename **replaces** the previous version, so a stale
copy never competes with the current one in retrieval.

## Trying it

`samples/leave-policy.md` and `samples/hr-faqs.csv` are included. Upload both
in **Admin → Knowledge base**, then in the chat ask:

- "how many days do I get for my brother's wedding?"
- "what happens to my unused comp-offs if I resign?"
- "can I carry forward sick leave?"

Each should answer from the uploaded document and show the source passage.
