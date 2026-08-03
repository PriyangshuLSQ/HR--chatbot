/**
 * Retrieval-augmented generation over HR's uploaded documents.
 *
 * This is the "actual AI" layer, and the important word is *grounded*: the
 * model never answers from its own training. It is handed the passages that
 * retrieval selected and told to answer from those alone, or to say it cannot.
 *
 * That is a deliberate choice over fine-tuning. Fine-tuning teaches a model
 * tone, not facts — it would invent plausible-sounding leave allowances, could
 * not cite a source, and would need a retraining run every time HR edited a
 * policy. With RAG, an uploaded document is answerable the moment it lands, and
 * every answer carries the passage it came from.
 */

import {
  chunkDocument,
  embeddingTextFor,
} from './chunk';
import { parseFile, UnsupportedFileError } from './parse';
import { retrieve } from './retrieve';
import { addDoc, allChunks, applyEmbeddings, readStore } from './store';
import {
  chat,
  embedBatch,
  getStatus,
  type ChatMessage,
  type OllamaStatus,
} from './ollama';
import { RELEVANCE_THRESHOLD, STRONG_RELEVANCE } from './types';
import type { Citation, KnowledgeAnswer, KnowledgeDoc, SourceKind } from './types';

export { RELEVANCE_THRESHOLD, STRONG_RELEVANCE };

/** How much retrieved text to hand the model. Keeps prompts inside small-model context. */
const MAX_CONTEXT_CHARS = 6000;

// ---------------------------------------------------------------------------
// Ingest
// ---------------------------------------------------------------------------

export interface IngestInput {
  buffer: Buffer;
  filename: string;
  mimeType?: string;
  category: string;
  uploadedBy: string;
}

export interface IngestResult {
  doc: KnowledgeDoc;
  chunkCount: number;
  embedded: boolean;
  notes: string[];
}

let docSeq = 0;

function newDocId(): string {
  docSeq += 1;
  return `doc_${Date.now().toString(36)}${docSeq.toString(36)}`;
}

export async function ingestFile(input: IngestInput): Promise<IngestResult> {
  const parsed = parseFile(input.buffer, input.filename, input.mimeType);
  return persistDocument({
    kind: parsed.kind,
    text: parsed.text,
    title: titleFrom(input.filename),
    filename: input.filename,
    bytes: input.buffer.byteLength,
    category: input.category,
    uploadedBy: input.uploadedBy,
    notes: parsed.notes,
  });
}

export async function ingestText(input: {
  title: string;
  text: string;
  category: string;
  uploadedBy: string;
}): Promise<IngestResult> {
  if (!input.text.trim()) {
    throw new UnsupportedFileError('There is no text to add.');
  }
  return persistDocument({
    kind: 'manual',
    text: input.text,
    title: input.title,
    filename: 'Typed in the console',
    bytes: Buffer.byteLength(input.text, 'utf8'),
    category: input.category,
    uploadedBy: input.uploadedBy,
    notes: [],
  });
}

async function persistDocument(args: {
  kind: SourceKind;
  text: string;
  title: string;
  filename: string;
  bytes: number;
  category: string;
  uploadedBy: string;
  notes: string[];
}): Promise<IngestResult> {
  const id = newDocId();
  const chunks = chunkDocument(id, args.text);
  if (!chunks.length) {
    throw new UnsupportedFileError('No indexable text was found in that document.');
  }

  // Embedding is best-effort. A failure here must not lose the upload — the
  // chunks are already searchable lexically, and /api/knowledge/reindex can
  // fill the vectors in once Ollama is available.
  const notes = [...args.notes];
  let embeddingModel: string | null = null;

  const status = await getStatus();
  if (status.ok && status.embedModel) {
    const vectors = await embedBatch(chunks.map(embeddingTextFor), status.embedModel);
    if (vectors && vectors.length === chunks.length) {
      chunks.forEach((c, i) => {
        c.embedding = vectors[i];
      });
      embeddingModel = status.embedModel;
    } else {
      notes.push(
        'Semantic embeddings failed — the document is searchable by keyword. Use "Rebuild index" to retry.'
      );
    }
  } else {
    notes.push(
      status.ok
        ? 'No embedding model installed — the document is searchable by keyword only.'
        : 'Ollama is offline — the document is searchable by keyword only.'
    );
  }

  const doc: KnowledgeDoc = {
    id,
    title: args.title,
    kind: args.kind,
    category: args.category,
    filename: args.filename,
    bytes: args.bytes,
    uploadedAt: new Date().toISOString(),
    uploadedBy: args.uploadedBy,
    chunkCount: chunks.length,
    embeddingModel,
    preview: args.text.replace(/\s+/g, ' ').slice(0, 240),
  };

  await addDoc(doc, chunks);
  return { doc, chunkCount: chunks.length, embedded: embeddingModel !== null, notes };
}

function titleFrom(filename: string): string {
  return filename
    .replace(/\.[^.]+$/, '')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^./, (c) => c.toUpperCase());
}

// ---------------------------------------------------------------------------
// Reindex
// ---------------------------------------------------------------------------

export interface ReindexResult {
  embedded: number;
  skipped: number;
  model: string | null;
  message: string;
}

/** Embeds every chunk that has no vector yet. Safe to run repeatedly. */
export async function reindex(force = false): Promise<ReindexResult> {
  // Fresh: this is usually invoked *because* the user just installed a model.
  const status = await getStatus({ fresh: true });
  if (!status.ok || !status.embedModel) {
    return {
      embedded: 0,
      skipped: 0,
      model: null,
      message: status.hint ?? 'No embedding model is available.',
    };
  }

  const { chunks } = await allChunks();
  const pending = force ? chunks : chunks.filter((c) => !c.embedding?.length);
  if (!pending.length) {
    return {
      embedded: 0,
      skipped: chunks.length,
      model: status.embedModel,
      message: 'Every chunk already has an embedding.',
    };
  }

  // Batched so a large knowledge base does not put one enormous request through
  // the model, and so a partial failure still commits the batches that worked.
  const BATCH = 32;
  const vectors = new Map<string, number[]>();
  let failed = 0;

  for (let i = 0; i < pending.length; i += BATCH) {
    const slice = pending.slice(i, i + BATCH);
    const result = await embedBatch(slice.map(embeddingTextFor), status.embedModel);
    if (!result || result.length !== slice.length) {
      failed += slice.length;
      continue;
    }
    slice.forEach((c, n) => vectors.set(c.id, result[n]));
  }

  const embedded = vectors.size ? await applyEmbeddings(vectors, status.embedModel) : 0;

  return {
    embedded,
    skipped: chunks.length - pending.length,
    model: status.embedModel,
    message: failed
      ? `Embedded ${embedded} chunk(s); ${failed} failed. Check that Ollama is still running.`
      : `Embedded ${embedded} chunk(s) with ${status.embedModel}.`,
  };
}

// ---------------------------------------------------------------------------
// Ask
// ---------------------------------------------------------------------------

export interface AskOptions {
  question: string;
  /** Prior turns, oldest first — lets the model resolve "what about mine?". */
  history?: { role: 'user' | 'bot'; text: string }[];
  /** Skip generation and return the best passage verbatim. */
  extractiveOnly?: boolean;
}

export async function ask(opts: AskOptions): Promise<KnowledgeAnswer> {
  const question = opts.question.trim();
  const kb = await readStore();

  if (!kb.chunks.length) {
    return { mode: 'none', answer: '', citations: [], confidence: 0 };
  }

  const status = await getStatus();
  const queryEmbedding = await embedQuery(question, status);

  const { hits, confidence } = retrieve(question, kb.chunks, kb.docs, {
    queryEmbedding,
    topK: 5,
  });

  if (!hits.length || confidence < RELEVANCE_THRESHOLD) {
    return { mode: 'none', answer: '', citations: [], confidence };
  }

  const citations = hits.map(toCitation);

  if (opts.extractiveOnly || !status.ok || !status.chatModel) {
    return {
      mode: 'extractive',
      answer: extractiveAnswer(hits[0], confidence),
      citations,
      confidence,
      warning: status.ok
        ? undefined
        : 'Ollama is offline, so this is the matching policy passage rather than a written answer.',
    };
  }

  const generated = await chat(buildPrompt(question, hits, opts.history), status.chatModel);

  if (!generated) {
    return {
      mode: 'extractive',
      answer: extractiveAnswer(hits[0], confidence),
      citations,
      confidence,
      warning: 'The local model did not respond in time — showing the source passage instead.',
    };
  }

  // The model is instructed to emit this token when the passages do not cover
  // the question. Honouring it is what keeps the bot from bluffing.
  if (/\bNOT_IN_DOCUMENTS\b/i.test(generated)) {
    return { mode: 'none', answer: '', citations: [], confidence };
  }

  return {
    mode: 'generated',
    answer: stripCitationMarkers(generated),
    citations,
    confidence,
    model: status.chatModel,
  };
}

async function embedQuery(question: string, status: OllamaStatus): Promise<number[] | null> {
  if (!status.ok || !status.embedModel) return null;
  const vectors = await embedBatch([question], status.embedModel, 20_000);
  return vectors?.[0] ?? null;
}

function toCitation(hit: ReturnType<typeof retrieve>['hits'][number]): Citation {
  return {
    docId: hit.chunk.docId,
    title: hit.doc?.title ?? 'Uploaded document',
    filename: hit.doc?.filename ?? '',
    headings: hit.chunk.headings,
    snippet: hit.chunk.text.replace(/\s+/g, ' ').slice(0, 300),
    score: Math.round(hit.score * 100) / 100,
  };
}

// ---------------------------------------------------------------------------
// Prompting
// ---------------------------------------------------------------------------

const SYSTEM_PROMPT = `You are the HR assistant for LeadSquared. You answer employee questions using ONLY the HR policy extracts provided in each message.

Rules, in order of importance:
1. Use only the provided extracts. Never use general knowledge about HR, employment law, or other companies.
2. Never invent or adjust a number, date, amount, deadline, or entitlement. If a figure is not in the extracts, do not state one.
3. Do not do arithmetic, unit conversion, or threshold comparison against the employee's own situation. State the rule and its threshold, and let them apply it. Say "remote work is allowed for up to 30 days a year with manager approval; beyond that needs HRBP sign-off" — never "your 3 weeks exceeds the limit". Getting that comparison backwards tells someone they need approval they don't, or that they don't need approval they do.
4. If the extracts do not answer the question, reply with exactly: NOT_IN_DOCUMENTS
   Do this even if you could guess. A wrong HR answer costs the employee real money or leave.
5. If the extracts only partly answer it, give the part that is covered and say plainly what is not.
6. Answer the employee directly in the second person. Do not mention "extracts", "context", "documents provided" or "based on the information".

Style: 2-5 short sentences, or a short bullet list for multi-step processes. Use **bold** for figures and deadlines. No preamble, no sign-off.`;

function buildPrompt(
  question: string,
  hits: ReturnType<typeof retrieve>['hits'],
  history?: { role: 'user' | 'bot'; text: string }[]
): ChatMessage[] {
  const messages: ChatMessage[] = [{ role: 'system', content: SYSTEM_PROMPT }];

  // The caller's transcript already ends with the question being asked, and it
  // is appended again below with the extracts — drop the duplicate so the model
  // does not see the same question twice and answer the first, context-free one.
  const prior = [...(history ?? [])];
  if (prior.length && prior[prior.length - 1].role === 'user') {
    const last = prior[prior.length - 1].text.trim();
    if (last === question.trim()) prior.pop();
  }

  // A couple of prior turns, trimmed — enough to resolve a follow-up like
  // "and for sick leave?" without pushing the extracts out of a small context.
  for (const turn of prior.slice(-4)) {
    messages.push({
      role: turn.role === 'user' ? 'user' : 'assistant',
      content: turn.text.slice(0, 400),
    });
  }

  messages.push({ role: 'user', content: `${contextBlock(hits)}\n\nQuestion: ${question}` });
  return messages;
}

function contextBlock(hits: ReturnType<typeof retrieve>['hits']): string {
  const parts: string[] = [];
  let budget = MAX_CONTEXT_CHARS;

  for (const hit of hits) {
    const label = [hit.doc?.title, ...hit.chunk.headings].filter(Boolean).join(' › ');
    const body = hit.chunk.text.slice(0, Math.max(0, budget));
    if (!body) break;
    parts.push(`--- ${label || 'HR document'} ---\n${body}`);
    budget -= body.length + label.length + 12;
    if (budget <= 200) break;
  }

  return `HR policy extracts:\n\n${parts.join('\n\n')}`;
}

/** Small models sometimes emit "[1]" markers despite not being asked to. */
function stripCitationMarkers(text: string): string {
  return text
    .replace(/\s*\[(?:\d+|source\s*\d*)\]/gi, '')
    .replace(/^\s*(?:answer|response)\s*:\s*/i, '')
    .trim();
}

// ---------------------------------------------------------------------------
// Extractive fallback
// ---------------------------------------------------------------------------

/**
 * The no-LLM path: return the matching passage verbatim, framed honestly.
 *
 * Deliberately not paraphrased. Without a model there is nothing to paraphrase
 * *with*, and quoting HR's own wording is both accurate and auditable.
 */
function extractiveAnswer(
  hit: ReturnType<typeof retrieve>['hits'][number],
  confidence: number
): string {
  const where = [hit.doc?.title, ...hit.chunk.headings].filter(Boolean).join(' › ');
  const lead =
    confidence >= STRONG_RELEVANCE
      ? "Here's the relevant section from the HR knowledge base"
      : "This looks like the closest match in the HR knowledge base";

  return `${lead}${where ? ` — **${where}**` : ''}:\n\n${trimToSentence(hit.chunk.text, 900)}`;
}

function trimToSentence(text: string, limit: number): string {
  if (text.length <= limit) return text;
  const cut = text.slice(0, limit);
  const boundary = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('\n'));
  return `${(boundary > limit * 0.5 ? cut.slice(0, boundary + 1) : cut).trim()}…`;
}
