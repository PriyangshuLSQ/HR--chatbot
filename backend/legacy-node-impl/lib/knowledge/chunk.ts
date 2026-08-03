/**
 * Splits parsed documents into retrieval-sized chunks.
 *
 * Two rules drive the design:
 *
 *  1. **Headings are hard boundaries.** A chunk that spans "Sick Leave" into
 *     "Maternity Leave" retrieves for both and answers neither correctly.
 *  2. **Chunks carry their heading trail.** "Up to 30 days" is meaningless on
 *     its own; prefixed with "Leave Policy › Carry-forward" it is retrievable
 *     and, just as importantly, citable back to the employee.
 *
 * Tokenisation reuses the NLU pipeline's `normalize` + `stem` so that lexical
 * retrieval sees the same canonical forms the intent matcher does — "pay slip",
 * "payslip" and "salary slip" all collapse to one term.
 */

import { normalize, stem } from '../nlu';
import type { KnowledgeChunk } from './types';

/** Target chunk size in characters. Roughly 200-250 tokens for English prose. */
const TARGET_CHARS = 900;
/** A chunk is never split below this — tiny fragments retrieve badly. */
const MIN_CHARS = 220;
/** Overlap carried into the next chunk so a sentence spanning the seam survives. */
const OVERLAP_CHARS = 160;
/** Above this, a single heading section is split further. */
const MAX_CHARS = 1400;

interface Section {
  headings: string[];
  body: string;
}

// ---------------------------------------------------------------------------
// Tokenisation (shared with lexical retrieval)
// ---------------------------------------------------------------------------

/** Words too common across HR documents to carry retrieval signal. */
const STOP = new Set([
  'a', 'an', 'the', 'is', 'am', 'are', 'was', 'were', 'be', 'been', 'being',
  'do', 'does', 'did', 'have', 'has', 'had', 'i', 'me', 'my', 'we', 'our',
  'you', 'your', 'it', 'its', 'to', 'of', 'in', 'on', 'at', 'for', 'with',
  'and', 'or', 'but', 'if', 'then', 'than', 'so', 'that', 'this', 'these',
  'those', 'there', 'here', 'can', 'could', 'will', 'would', 'shall', 'should',
  'may', 'might', 'must', 'as', 'by', 'from', 'not', 'no', 'any', 'all',
  'please', 'kindly', 'per', 'via', 'such', 'each', 'other', 'more', 'most',
]);

export function tokenize(text: string): string[] {
  return normalize(text)
    .split(' ')
    .filter(Boolean)
    .filter((w) => !STOP.has(w) && w.length > 1)
    .map(stem);
}

export function termFrequencies(tokens: string[]): Record<string, number> {
  const tf: Record<string, number> = {};
  for (const t of tokens) tf[t] = (tf[t] ?? 0) + 1;
  return tf;
}

// ---------------------------------------------------------------------------
// Chunking
// ---------------------------------------------------------------------------

export function chunkDocument(docId: string, markdown: string): KnowledgeChunk[] {
  const sections = splitByHeadings(markdown);
  const chunks: KnowledgeChunk[] = [];

  for (const section of sections) {
    for (const body of splitLongSection(section.body)) {
      const text = body.trim();
      if (!text) continue;

      // The heading trail is part of the embedded and indexed text, not just
      // display metadata — that is what makes a bare "30 days" chunk findable.
      const withContext = section.headings.length
        ? `${section.headings.join(' › ')}\n${text}`
        : text;
      const tokens = tokenize(withContext);
      if (!tokens.length) continue;

      chunks.push({
        id: `${docId}#${chunks.length}`,
        docId,
        index: chunks.length,
        headings: section.headings,
        text,
        embedding: null,
        tf: termFrequencies(tokens),
        length: tokens.length,
      });
    }
  }

  // A document with no headings and very little text still deserves one chunk.
  if (!chunks.length && markdown.trim()) {
    const text = markdown.trim();
    const tokens = tokenize(text);
    chunks.push({
      id: `${docId}#0`,
      docId,
      index: 0,
      headings: [],
      text,
      embedding: null,
      tf: termFrequencies(tokens),
      length: tokens.length,
    });
  }

  return chunks;
}

/** The text that gets embedded — heading trail included, same as the index. */
export function embeddingTextFor(chunk: KnowledgeChunk): string {
  return chunk.headings.length ? `${chunk.headings.join(' › ')}\n${chunk.text}` : chunk.text;
}

/**
 * Walks the Markdown, maintaining a stack of the active headings.
 *
 * The stack is kept *sparse* — index `i` always means heading level `i + 1`,
 * with empty strings for levels a document skipped. Compacting it as we go
 * breaks that correspondence: a run of sibling `##` headings with no `#`
 * parent (exactly what a Q&A CSV produces, one heading per row) would see each
 * heading treated as the parent of the next, and the trail would grow
 * "Question 1 › Question 2 › Question 3". Since the trail is prepended to the
 * text before embedding, that does not just look wrong — it poisons every
 * chunk's vector with unrelated questions. Blanks are dropped at emit time.
 */
function splitByHeadings(markdown: string): Section[] {
  const lines = markdown.split('\n');
  const sections: Section[] = [];
  const stack: string[] = [];
  let buffer: string[] = [];

  const flush = () => {
    const body = buffer.join('\n').trim();
    if (body) sections.push({ headings: stack.filter(Boolean), body });
    buffer = [];
  };

  for (const line of lines) {
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      flush();
      const level = heading[1].length;
      const title = heading[2].trim();
      // Drop deeper levels, pad any the document skipped, then set this one —
      // so jumping ### back up to ## still yields an accurate trail.
      stack.length = level - 1;
      while (stack.length < level - 1) stack.push('');
      stack[level - 1] = title;
      stack.length = level;
      continue;
    }
    buffer.push(line);
  }
  flush();

  return sections;
}

/**
 * Splits an over-long section on paragraph boundaries, with overlap.
 *
 * Overlap matters more than it looks: without it, a rule stated across two
 * paragraphs ("...unless the employee has completed probation." / "In that
 * case, 60 days apply.") lands in different chunks and neither answers the
 * question on its own.
 */
function splitLongSection(body: string): string[] {
  if (body.length <= MAX_CHARS) return [body];

  const paragraphs = body.split(/\n{2,}/).filter((p) => p.trim());
  const out: string[] = [];
  let current = '';

  for (const paragraph of paragraphs) {
    // A single paragraph bigger than the max is split on sentence boundaries.
    const pieces =
      paragraph.length > MAX_CHARS ? splitOnSentences(paragraph) : [paragraph];

    for (const piece of pieces) {
      if (current && current.length + piece.length + 2 > TARGET_CHARS) {
        out.push(current);
        current = tailOf(current) + piece;
      } else {
        current = current ? `${current}\n\n${piece}` : piece;
      }
    }
  }

  if (current.trim()) {
    // Avoid orphaning a scrap: fold it into the previous chunk instead.
    if (out.length && current.length < MIN_CHARS) out[out.length - 1] += `\n\n${current}`;
    else out.push(current);
  }

  return out;
}

function splitOnSentences(paragraph: string): string[] {
  const sentences = paragraph.match(/[^.!?]+[.!?]+(?:\s|$)|[^.!?]+$/g) ?? [paragraph];
  const out: string[] = [];
  let current = '';
  for (const s of sentences) {
    if (current.length + s.length > TARGET_CHARS && current) {
      out.push(current.trim());
      current = '';
    }
    current += s;
  }
  if (current.trim()) out.push(current.trim());
  return out;
}

/** The trailing sentences of a chunk, carried into the next one as overlap. */
function tailOf(text: string): string {
  if (text.length <= OVERLAP_CHARS) return `${text}\n\n`;
  const tail = text.slice(-OVERLAP_CHARS);
  // Start the overlap at a sentence boundary so it reads as prose, not a fragment.
  const boundary = tail.search(/[.!?]\s/);
  const clean = boundary >= 0 ? tail.slice(boundary + 2) : tail;
  return clean.trim() ? `${clean.trim()}\n\n` : '';
}
