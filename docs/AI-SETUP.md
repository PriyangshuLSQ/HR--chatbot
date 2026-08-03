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
| `semantic` | an embedding model | The matching passage, found by meaning |
| `generative` | embedding + chat model | A written answer, plus its sources |

## Turning on the full AI

Everything is open source and runs locally. No API key, no account, no HR data
leaving the machine.

```bash
# 1. Install Ollama — https://ollama.com/download
#    macOS: brew install ollama && brew services start ollama

# 2. Pull the two models (~3.7 GB total, one time)
ollama pull nomic-embed-text   # 274 MB — embeddings for semantic search
ollama pull qwen3.5:4b         # 3.4 GB — writes the grounded answers

# 3. Confirm it is up
curl http://127.0.0.1:11434/api/tags
```

Then open **Admin → Knowledge base** and press **Rebuild index**. That embeds
any documents uploaded before the models were installed. The badge should flip
to *Full AI · generating answers*.

### Choosing models

`qwen3.5:4b` is the default. Before it, `qwen2.5:7b`; before that, `llama3.2` (3B).
The 4B was chosen for a 16 GB machine that also runs the Java service, a Next dev
server and an IDE — 3.4 GB of weights leaves headroom where 4.7 GB did not.

**Qwen3-family models are hybrid-reasoning.** `OllamaClient` sends `think: false`
and strips any `<think>` block that arrives regardless. That is not cosmetic: a
reasoning trace would be shown to the employee as their answer, and it would bury
the `NOT_IN_DOCUMENTS` marker inside prose — turning a refusal into something the
client reads as a real answer. `VisibleTextTest` pins that behaviour.

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
rejected on exactly those cases. The theory had been that grounded rewriting is
easy — the model only has to restate a passage, not *know* anything. That was wrong
in a way worth recording, because it is not a prose-quality problem, it is a
correctness one.

If 3.4 GB is more than you want, `qwen3:1.7b` and `qwen3:4b` are smaller, and
`llama3.2` smaller still — but re-run the two cases above before trusting any of
them. Larger is available too: `qwen3.5:9b` (6.6 GB) is the biggest that fits this
16 GB machine with the rest of the stack running. Override without touching code:

```bash
OLLAMA_CHAT_MODEL=qwen3.5:9b
OLLAMA_EMBED_MODEL=nomic-embed-text
OLLAMA_URL=http://127.0.0.1:11434
```

If you change the embedding model, run **Rebuild index** — vectors from
different models are not comparable, and mixing them silently degrades search.

If a configured model is not installed, the app falls back to any suitable
installed model of the right kind rather than failing.

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
