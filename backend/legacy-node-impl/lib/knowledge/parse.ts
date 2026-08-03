/**
 * File parsers: .txt / .md / .csv / .docx -> plain text (Markdown-flavoured).
 *
 * Server-only — uses `node:zlib` to inflate .docx, which is a ZIP archive.
 * Deliberately dependency-free: a DOCX text extractor is ~100 lines of ZIP
 * central-directory walking, and adding `mammoth` (plus its transitive tree)
 * to pull out `<w:t>` nodes is not a trade worth making here.
 *
 * Output is normalised to Markdown so one chunker handles every source: Word
 * heading styles become `##`, CSV rows become definition lines.
 */

import { inflateRawSync } from 'node:zlib';
import type { SourceKind } from './types';

export interface ParsedFile {
  kind: SourceKind;
  text: string;
  /** Non-fatal notes to show HR, e.g. "3 empty rows skipped". */
  notes: string[];
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

const DOCX_MAGIC = 0x04034b50; // "PK\x03\x04" — local file header

export function kindFor(filename: string, mimeType?: string): SourceKind | null {
  const ext = filename.toLowerCase().split('.').pop() ?? '';
  if (ext === 'txt' || ext === 'text') return 'txt';
  if (ext === 'md' || ext === 'markdown') return 'md';
  if (ext === 'csv' || ext === 'tsv') return 'csv';
  if (ext === 'docx') return 'docx';

  // Fall back to the browser-declared MIME type for extension-less uploads.
  if (mimeType?.includes('wordprocessingml')) return 'docx';
  if (mimeType === 'text/csv') return 'csv';
  if (mimeType === 'text/markdown') return 'md';
  if (mimeType?.startsWith('text/')) return 'txt';
  return null;
}

export function parseFile(buffer: Buffer, filename: string, mimeType?: string): ParsedFile {
  const kind = kindFor(filename, mimeType);
  if (!kind) {
    throw new UnsupportedFileError(
      `${filename} is not a supported format. Upload .txt, .md, .csv or .docx.`
    );
  }

  // A .doc renamed to .docx is the most common upload mistake, and it fails
  // deep inside the ZIP reader with an opaque error. Catch it up front.
  // The length check matters: readUInt32LE throws on a buffer under 4 bytes.
  if (kind === 'docx' && (buffer.length < 4 || buffer.readUInt32LE(0) !== DOCX_MAGIC)) {
    throw new UnsupportedFileError(
      `${filename} is not a real .docx file. Legacy .doc files are not supported — ` +
        `open it in Word and use "Save As → Word Document (.docx)".`
    );
  }

  switch (kind) {
    case 'docx':
      return { kind, ...parseDocx(buffer) };
    case 'csv':
      return { kind, ...parseCsvText(decodeText(buffer), filename.toLowerCase().endsWith('.tsv')) };
    default:
      return { kind, text: normaliseWhitespace(decodeText(buffer)), notes: [] };
  }
}

export class UnsupportedFileError extends Error {}

/** Strips a UTF-8 BOM, which otherwise becomes a stray glyph in the first heading. */
function decodeText(buffer: Buffer): string {
  const text = buffer.toString('utf8');
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

function normaliseWhitespace(text: string): string {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// ---------------------------------------------------------------------------
// DOCX
// ---------------------------------------------------------------------------

function parseDocx(buffer: Buffer): { text: string; notes: string[] } {
  const xml = readZipEntry(buffer, 'word/document.xml');
  if (!xml) {
    throw new UnsupportedFileError(
      'That .docx has no readable document body. It may be corrupt or password-protected.'
    );
  }

  const notes: string[] = [];
  const text = docxXmlToMarkdown(xml.toString('utf8'));
  if (!text.trim()) {
    throw new UnsupportedFileError(
      'No text could be extracted from that .docx. If the content is images or scans, ' +
        'the text needs to be typed or OCR-ed first.'
    );
  }

  // Images and embedded objects are silently dropped; say so rather than let
  // HR assume a diagram-heavy policy was fully ingested.
  const imageCount = (xml.toString('utf8').match(/<w:drawing[\s>]/g) ?? []).length;
  if (imageCount > 0) {
    notes.push(
      `${imageCount} image${imageCount > 1 ? 's' : ''} skipped — only text is indexed.`
    );
  }

  return { text, notes };
}

/**
 * Word XML -> Markdown, paragraph by paragraph.
 *
 * Paragraph-level (rather than a flat `<w:t>` sweep) so that heading styles
 * survive: those headings become the chunk boundaries and the citation trail,
 * which is most of what makes retrieval over a long policy readable.
 */
function docxXmlToMarkdown(xml: string): string {
  // Only the body — headers, footers and footnotes live in separate parts, but
  // <w:sectPr> at the end carries page setup we don't want.
  const bodyMatch = xml.match(/<w:body\b[^>]*>([\s\S]*)<\/w:body>/);
  const body = bodyMatch ? bodyMatch[1] : xml;

  const paragraphs = body.match(/<w:p\b[^>]*>[\s\S]*?<\/w:p>|<w:p\b[^>]*\/>/g) ?? [];
  const lines: string[] = [];

  for (const p of paragraphs) {
    const text = extractRunText(p);
    if (!text.trim()) {
      // Preserve the blank line — it is the paragraph separator downstream.
      if (lines.length && lines[lines.length - 1] !== '') lines.push('');
      continue;
    }

    const heading = p.match(/<w:pStyle\s+w:val="(?:Heading|heading)\s*(\d)"/);
    if (heading) {
      const level = Math.min(Number(heading[1]) || 1, 6);
      if (lines.length && lines[lines.length - 1] !== '') lines.push('');
      lines.push(`${'#'.repeat(level)} ${text.trim()}`);
      lines.push('');
      continue;
    }

    // Numbered and bulleted paragraphs carry <w:numPr>; Word stores the marker
    // itself in a separate numbering part, so emit a generic bullet.
    if (/<w:numPr\b/.test(p)) {
      lines.push(`- ${text.trim()}`);
      continue;
    }

    lines.push(text.trim());
  }

  return normaliseWhitespace(lines.join('\n'));
}

/** Pulls the visible text out of one paragraph, honouring tabs and line breaks. */
function extractRunText(paragraphXml: string): string {
  const token = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>|<w:tab\s*\/>|<w:br\s*\/>/g;
  let out = '';
  let m: RegExpExecArray | null;
  while ((m = token.exec(paragraphXml)) !== null) {
    if (m[1] !== undefined) out += decodeXmlEntities(stripTags(m[1]));
    else if (m[0].startsWith('<w:tab')) out += ' ';
    else out += '\n';
  }
  return out;
}

/**
 * Removes any residual markup from a captured run.
 *
 * Well-formed Word output never nests an element inside `<w:t>`, so this is
 * belt-and-braces — but a document produced by a third-party exporter can be
 * odd, and the failure mode without it is a literal `<w:t>` appearing in an
 * answer shown to an employee. Runs before entity decoding, so an escaped
 * `&lt;b&gt;` in the source survives as visible text.
 */
function stripTags(s: string): string {
  return s.includes('<') ? s.replace(/<[^>]*>/g, '') : s;
}

function decodeXmlEntities(s: string): string {
  return s
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => safeCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => safeCodePoint(parseInt(d, 10)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    // Ampersand last, so "&amp;lt;" decodes to "&lt;" and not "<".
    .replace(/&amp;/g, '&');
}

function safeCodePoint(code: number): string {
  if (!Number.isFinite(code) || code < 0 || code > 0x10ffff) return '';
  try {
    return String.fromCodePoint(code);
  } catch {
    return '';
  }
}

// ---------------------------------------------------------------------------
// Minimal ZIP reader
// ---------------------------------------------------------------------------

const EOCD_SIG = 0x06054b50;
const CDIR_SIG = 0x02014b50;

/**
 * Reads a single entry out of a ZIP archive by walking the central directory.
 *
 * Scanning the central directory (rather than the local headers front to back)
 * is what makes this correct: local headers can carry a zeroed size with the
 * real size in a trailing data descriptor, and only the central directory is
 * guaranteed to have it.
 */
function readZipEntry(buffer: Buffer, wantedName: string): Buffer | null {
  const eocd = findEocd(buffer);
  if (eocd < 0) return null;

  const entryCount = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);

  for (let i = 0; i < entryCount; i++) {
    if (offset + 46 > buffer.length || buffer.readUInt32LE(offset) !== CDIR_SIG) return null;

    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLen = buffer.readUInt16LE(offset + 28);
    const extraLen = buffer.readUInt16LE(offset + 30);
    const commentLen = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLen);

    if (name === wantedName) {
      return readLocalEntry(buffer, localOffset, method, compressedSize);
    }
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

function readLocalEntry(
  buffer: Buffer,
  localOffset: number,
  method: number,
  compressedSize: number
): Buffer | null {
  if (localOffset + 30 > buffer.length) return null;

  // The local header repeats the name and extra-field lengths, and they can
  // differ from the central directory's — always re-read them here.
  const nameLen = buffer.readUInt16LE(localOffset + 26);
  const extraLen = buffer.readUInt16LE(localOffset + 28);
  const start = localOffset + 30 + nameLen + extraLen;
  const end = start + compressedSize;
  if (end > buffer.length) return null;

  const raw = buffer.subarray(start, end);
  if (method === 0) return Buffer.from(raw); // stored
  if (method !== 8) return null; // anything but deflate is out of scope

  try {
    return inflateRawSync(raw);
  } catch {
    return null;
  }
}

/** Finds the end-of-central-directory record, scanning back over the comment. */
function findEocd(buffer: Buffer): number {
  const minOffset = Math.max(0, buffer.length - 22 - 0xffff);
  for (let i = buffer.length - 22; i >= minOffset; i--) {
    if (buffer.readUInt32LE(i) === EOCD_SIG) return i;
  }
  return -1;
}

// ---------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------

/** Header names that mark a sheet as a question/answer pair list. */
const QUESTION_HEADERS = ['question', 'questions', 'q', 'query', 'prompt', 'issue', 'topic'];
const ANSWER_HEADERS = ['answer', 'answers', 'a', 'response', 'reply', 'resolution', 'details'];

function parseCsvText(text: string, isTsv: boolean): { text: string; notes: string[] } {
  const rows = parseDelimited(text, isTsv ? '\t' : detectDelimiter(text));
  const notes: string[] = [];

  if (rows.length < 2) {
    throw new UnsupportedFileError(
      'That CSV has no data rows — it needs a header row plus at least one row of content.'
    );
  }

  const header = rows[0].map((h) => h.trim());
  const body = rows.slice(1).filter((r) => r.some((cell) => cell.trim()));
  const skipped = rows.length - 1 - body.length;
  if (skipped > 0) notes.push(`${skipped} blank row${skipped > 1 ? 's' : ''} skipped.`);

  const qIdx = header.findIndex((h) => QUESTION_HEADERS.includes(h.toLowerCase()));
  const aIdx = header.findIndex((h) => ANSWER_HEADERS.includes(h.toLowerCase()));

  // Q&A shape: each row becomes its own heading + body, so a question retrieves
  // as one clean unit instead of being glued to its neighbours.
  if (qIdx >= 0 && aIdx >= 0 && qIdx !== aIdx) {
    notes.push(`Detected a Q&A sheet — "${header[qIdx]}" / "${header[aIdx]}".`);
    const extras = header
      .map((h, i) => ({ h, i }))
      .filter(({ i }) => i !== qIdx && i !== aIdx);

    const blocks = body
      .map((row) => {
        const q = (row[qIdx] ?? '').trim();
        const a = (row[aIdx] ?? '').trim();
        if (!q || !a) return null;
        const meta = extras
          .map(({ h, i }) => ({ h, v: (row[i] ?? '').trim() }))
          .filter(({ v }) => v)
          .map(({ h, v }) => `_${h}: ${v}_`)
          .join(' · ');
        return `## ${q}\n\n${a}${meta ? `\n\n${meta}` : ''}`;
      })
      .filter((b): b is string => b !== null);

    if (!blocks.length) {
      throw new UnsupportedFileError(
        'Every row in that CSV was missing either a question or an answer.'
      );
    }
    return { text: blocks.join('\n\n'), notes };
  }

  // Generic table: one labelled line per row keeps each value attached to its
  // column name, which is what makes a row retrievable at all.
  notes.push(
    'No question/answer columns found — each row was indexed as a labelled record. ' +
      'Name your columns "Question" and "Answer" for better matching.'
  );
  const lines = body.map((row, n) => {
    const pairs = header
      .map((h, i) => ({ h, v: (row[i] ?? '').trim() }))
      .filter(({ h, v }) => h && v)
      .map(({ h, v }) => `**${h}:** ${v}`)
      .join(' · ');
    return `- Row ${n + 1} — ${pairs}`;
  });

  return { text: lines.join('\n'), notes };
}

/** Picks between comma and semicolon by counting them outside quoted spans. */
function detectDelimiter(text: string): string {
  const sample = text.slice(0, 4000).replace(/"[^"]*"/g, '');
  const commas = (sample.match(/,/g) ?? []).length;
  const semis = (sample.match(/;/g) ?? []).length;
  return semis > commas ? ';' : ',';
}

/** RFC 4180 parser: handles quoted fields, escaped quotes and embedded newlines. */
function parseDelimited(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;

  const src = text.replace(/\r\n?/g, '\n');

  for (let i = 0; i < src.length; i++) {
    const ch = src[i];

    if (inQuotes) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i++; // consume the escape pair
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
      continue;
    }

    if (ch === '"') inQuotes = true;
    else if (ch === delimiter) {
      row.push(field);
      field = '';
    } else if (ch === '\n') {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else {
      field += ch;
    }
  }

  // Flush the trailing field unless the file ended on a clean newline.
  if (field || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
