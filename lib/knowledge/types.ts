/**
 * Shared types for the HR knowledge base.
 *
 * A `KnowledgeDoc` is one thing HR uploaded (or typed in): a policy .docx, a
 * .csv of FAQs, a pasted note. It is split into `KnowledgeChunk`s, because a
 * 40-page leave policy is not a useful retrieval unit — a single section is.
 *
 * Every chunk carries both a dense `embedding` (semantic meaning, from Ollama)
 * and the raw text (for lexical BM25 matching). Keeping both lets retrieval
 * degrade to keyword search when no embedding model is available, rather than
 * failing outright.
 */

/** Where a document came from. Drives how it is parsed and displayed. */
export type SourceKind = 'txt' | 'md' | 'csv' | 'docx' | 'manual';

export interface KnowledgeChunk {
  id: string;
  docId: string;
  /** Position within the document — used to show "section 3 of 12". */
  index: number;
  /**
   * Heading trail this chunk sits under, e.g. ['Leave Policy', 'Carry-forward'].
   * Prepended to the text before embedding so a chunk that says "up to 30 days"
   * still retrieves for "leave carry forward limit".
   */
  headings: string[];
  text: string;
  /** null when no embedding model was reachable at ingest time. */
  embedding: number[] | null;
  /** Term frequencies, for the lexical half of retrieval. Built at ingest. */
  tf: Record<string, number>;
  /** Total token count, for BM25 length normalisation. */
  length: number;
}

export interface KnowledgeDoc {
  id: string;
  title: string;
  kind: SourceKind;
  category: string;
  /** Original filename, or 'Typed in the console' for manual entries. */
  filename: string;
  bytes: number;
  uploadedAt: string;
  uploadedBy: string;
  chunkCount: number;
  /** Model used for embeddings, or null if the doc is lexical-only. */
  embeddingModel: string | null;
  /** Surfaced in the admin list so HR can sanity-check what was extracted. */
  preview: string;
}

/** The on-disk shape. Chunks live beside docs so one read loads everything. */
export interface KnowledgeStore {
  version: 1;
  docs: KnowledgeDoc[];
  chunks: KnowledgeChunk[];
}

export interface RetrievedChunk {
  chunk: KnowledgeChunk;
  /** 0-1, blended dense + lexical. */
  score: number;
  /** Which signals actually fired — shown in the admin retrieval inspector. */
  dense: number;
  lexical: number;
  doc: KnowledgeDoc | undefined;
}

export interface Citation {
  docId: string;
  title: string;
  filename: string;
  headings: string[];
  /** The exact passage the answer was grounded in. */
  snippet: string;
  score: number;
}

// ---------------------------------------------------------------------------
// Relevance thresholds
// ---------------------------------------------------------------------------
//
// These live here rather than in `rag.ts` because the chat client needs them
// to decide *whether to ask* — and `rag.ts` pulls in node:fs, which must never
// reach the browser bundle. This module has no runtime imports at all.

/**
 * Below this retrieval confidence we do not answer from documents at all.
 * Tuned so an off-topic question ("what's the wifi password") falls through
 * to the existing HR escalation path instead of getting a confident non-answer.
 */
export const RELEVANCE_THRESHOLD = 0.34;

/**
 * Above this, a document match is strong enough to pre-empt the NLU's
 * clarifying question — HR's own policy text beats "which of these did you
 * mean?" when the passage plainly covers it.
 */
export const STRONG_RELEVANCE = 0.55;

/** How an answer was produced — surfaced to the employee and in analytics. */
export type AnswerMode =
  | 'generated' // LLM wrote it, grounded in retrieved chunks
  | 'extractive' // no LLM available; best passage returned verbatim
  | 'none'; // nothing in the knowledge base was relevant

export interface KnowledgeAnswer {
  mode: AnswerMode;
  answer: string;
  citations: Citation[];
  /** Retrieval confidence, 0-1. Drives whether the caller trusts this at all. */
  confidence: number;
  /** Model that generated the prose, when mode === 'generated'. */
  model?: string;
  /** Populated when generation was attempted and failed. */
  warning?: string;
}
