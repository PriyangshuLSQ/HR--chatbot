/**
 * Hybrid retrieval over the HR knowledge base.
 *
 * Two signals, blended:
 *
 *  - **Dense** (cosine over Ollama embeddings) catches meaning. "Can I take
 *    time off to look after my newborn?" finds the paternity section even
 *    though it shares no keywords with it.
 *  - **Lexical** (BM25) catches exact terms. Dense models routinely fumble
 *    "Form 16", "80C" and "₹5,00,000" — the tokens that matter most in HR
 *    policy — because they are rare and numeric.
 *
 * Either signal works alone: with no embeddings (Ollama absent) this is a
 * competent BM25 search, which is why upload and chat still work on a machine
 * that has never seen an LLM.
 */

import { tokenize } from './chunk';
import type { KnowledgeChunk, KnowledgeDoc, RetrievedChunk } from './types';

/** Standard BM25 constants: term-frequency saturation and length normalisation. */
const BM25_K1 = 1.4;
const BM25_B = 0.72;

/**
 * Converts an unbounded BM25 score to 0-1. Chosen so a solid multi-term match
 * lands near 0.6-0.8 and a single incidental term match stays under 0.3 —
 * the range the relevance threshold is calibrated against.
 */
const BM25_SATURATION = 7;

/** Cosine below this is noise for `nomic-embed-text`-class models. */
const COSINE_FLOOR = 0.32;
/** Cosine at or above this is treated as a full-strength semantic match. */
const COSINE_CEILING = 0.82;

const DENSE_WEIGHT = 0.62;
const LEXICAL_WEIGHT = 0.38;

/** Never return more than this many chunks from one document. */
const MAX_PER_DOC = 3;

export interface RetrievalResult {
  hits: RetrievedChunk[];
  /** 0-1 — how much to trust that the knowledge base actually covers this. */
  confidence: number;
  /** True when dense vectors contributed; false means BM25-only. */
  usedEmbeddings: boolean;
}

export function retrieve(
  query: string,
  chunks: KnowledgeChunk[],
  docs: KnowledgeDoc[],
  opts: { queryEmbedding?: number[] | null; topK?: number } = {}
): RetrievalResult {
  const topK = opts.topK ?? 5;
  if (!chunks.length) return { hits: [], confidence: 0, usedEmbeddings: false };

  const queryTokens = tokenize(query);
  if (!queryTokens.length && !opts.queryEmbedding) {
    return { hits: [], confidence: 0, usedEmbeddings: false };
  }

  const idf = buildIdf(chunks);
  const avgLength =
    chunks.reduce((sum, c) => sum + c.length, 0) / Math.max(1, chunks.length);

  const queryVector = opts.queryEmbedding ?? null;
  const docById = new Map(docs.map((d) => [d.id, d]));

  let anyDense = false;

  const scored: RetrievedChunk[] = chunks.map((chunk) => {
    const lexical = bm25(queryTokens, chunk, idf, avgLength);

    let dense = 0;
    if (queryVector && chunk.embedding && chunk.embedding.length === queryVector.length) {
      dense = rescaleCosine(cosine(queryVector, chunk.embedding));
      if (dense > 0) anyDense = true;
    }

    // With no vector on either side, lexical carries the full weight rather
    // than being scaled to 38% of a score it is the only contributor to.
    const score =
      queryVector && chunk.embedding
        ? DENSE_WEIGHT * dense + LEXICAL_WEIGHT * lexical
        : lexical;

    return { chunk, score, dense, lexical, doc: docById.get(chunk.docId) };
  });

  const hits = diversify(
    scored.filter((h) => h.score > 0.02).sort((a, b) => b.score - a.score),
    topK
  );

  return {
    hits,
    confidence: confidenceOf(hits, queryTokens),
    usedEmbeddings: anyDense,
  };
}

// ---------------------------------------------------------------------------
// Scoring
// ---------------------------------------------------------------------------

/** Inverse document frequency across chunks, so "leave" counts less than "gratuity". */
function buildIdf(chunks: KnowledgeChunk[]): Map<string, number> {
  const df = new Map<string, number>();
  for (const chunk of chunks) {
    for (const term of Object.keys(chunk.tf)) df.set(term, (df.get(term) ?? 0) + 1);
  }
  const n = chunks.length;
  const idf = new Map<string, number>();
  for (const [term, freq] of df) {
    // Standard BM25 IDF, floored so a term appearing in most chunks still
    // contributes a little rather than going negative.
    idf.set(term, Math.max(0.05, Math.log(1 + (n - freq + 0.5) / (freq + 0.5))));
  }
  return idf;
}

function bm25(
  queryTokens: string[],
  chunk: KnowledgeChunk,
  idf: Map<string, number>,
  avgLength: number
): number {
  let raw = 0;
  const seen = new Set<string>();

  for (const term of queryTokens) {
    if (seen.has(term)) continue;
    seen.add(term);

    const tf = chunk.tf[term];
    if (!tf) continue;

    const weight = idf.get(term) ?? 1.2;
    const norm = 1 - BM25_B + BM25_B * (chunk.length / Math.max(1, avgLength));
    raw += weight * ((tf * (BM25_K1 + 1)) / (tf + BM25_K1 * norm));
  }

  return raw / (raw + BM25_SATURATION);
}

function cosine(a: number[], b: number[]): number {
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  if (na === 0 || nb === 0) return 0;
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

/**
 * Embedding models put unrelated sentences around 0.3-0.4 cosine, not 0 —
 * so raw cosine massively overstates relevance. Rescaling against an observed
 * floor and ceiling is what makes the confidence threshold meaningful.
 */
function rescaleCosine(cos: number): number {
  if (cos <= COSINE_FLOOR) return 0;
  return Math.min(1, (cos - COSINE_FLOOR) / (COSINE_CEILING - COSINE_FLOOR));
}

/** Caps how many chunks any one document can contribute to the result set. */
function diversify(sorted: RetrievedChunk[], topK: number): RetrievedChunk[] {
  const perDoc = new Map<string, number>();
  const out: RetrievedChunk[] = [];

  for (const hit of sorted) {
    if (out.length >= topK) break;
    const used = perDoc.get(hit.chunk.docId) ?? 0;
    if (used >= MAX_PER_DOC) continue;
    perDoc.set(hit.chunk.docId, used + 1);
    out.push(hit);
  }
  return out;
}

/**
 * Blends the top score with query-term coverage.
 *
 * Top score alone is a poor guide: a long chunk can score respectably on one
 * incidental keyword. Requiring that the retrieved passages actually contain
 * most of what was asked about is what stops the bot confidently answering a
 * question the knowledge base never covered.
 */
function confidenceOf(hits: RetrievedChunk[], queryTokens: string[]): number {
  if (!hits.length) return 0;

  const unique = Array.from(new Set(queryTokens));
  if (!unique.length) return Math.min(1, hits[0].score);

  const pool = hits.slice(0, 3);
  const covered = unique.filter((t) => pool.some((h) => h.chunk.tf[t])).length;
  const coverage = covered / unique.length;

  return Math.min(1, 0.7 * hits[0].score + 0.3 * coverage);
}
