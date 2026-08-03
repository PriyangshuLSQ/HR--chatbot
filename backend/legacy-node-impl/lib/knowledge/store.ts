/**
 * Server-side persistence for the HR knowledge base.
 *
 * Backed by a single JSON file (`data/knowledge.json`) rather than localStorage,
 * because uploads have to be *shared*: HR uploads a policy in the admin console
 * and every employee's chat must see it immediately. A per-browser store cannot
 * do that, and embeddings blow past the 5 MB localStorage cap anyway.
 *
 * Writes are serialised through an in-process promise chain and go via a temp
 * file + rename, so a crash mid-write cannot leave a truncated JSON file that
 * takes the whole knowledge base down on next boot.
 *
 * Swap `read`/`write` for a database when this outgrows a file — every caller
 * goes through the exported functions.
 */

import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { KnowledgeChunk, KnowledgeDoc, KnowledgeStore } from './types';

const DATA_DIR = join(process.cwd(), 'data');
const STORE_PATH = join(DATA_DIR, 'knowledge.json');

const EMPTY: KnowledgeStore = { version: 1, docs: [], chunks: [] };

// ---------------------------------------------------------------------------
// Read, with an mtime-keyed cache
// ---------------------------------------------------------------------------

let cache: { store: KnowledgeStore; mtimeMs: number } | null = null;

export async function readStore(): Promise<KnowledgeStore> {
  try {
    const info = await stat(STORE_PATH);
    if (cache && cache.mtimeMs === info.mtimeMs) return cache.store;

    const raw = await readFile(STORE_PATH, 'utf8');
    const parsed = JSON.parse(raw) as KnowledgeStore;

    // Guard against a hand-edited or partially-written file rather than
    // throwing on every subsequent request.
    const store: KnowledgeStore = {
      version: 1,
      docs: Array.isArray(parsed.docs) ? parsed.docs : [],
      chunks: Array.isArray(parsed.chunks) ? parsed.chunks : [],
    };
    cache = { store, mtimeMs: info.mtimeMs };
    return store;
  } catch {
    // Missing file on first run, or unparseable content — start clean.
    return EMPTY;
  }
}

// ---------------------------------------------------------------------------
// Write, serialised
// ---------------------------------------------------------------------------

let writeQueue: Promise<unknown> = Promise.resolve();

/**
 * Runs `mutate` against the current store and persists the result.
 *
 * Queued rather than concurrent: two uploads landing together would otherwise
 * both read the same snapshot and the second write would silently drop the
 * first document.
 */
export function mutateStore<T>(
  mutate: (store: KnowledgeStore) => { store: KnowledgeStore; result: T } | Promise<{ store: KnowledgeStore; result: T }>
): Promise<T> {
  const run = async (): Promise<T> => {
    const current = await readStore();
    const { store, result } = await mutate({
      version: 1,
      docs: [...current.docs],
      chunks: [...current.chunks],
    });
    await persist(store);
    return result;
  };

  // Chain onto the queue, and keep the queue alive even if this write throws.
  const next = writeQueue.then(run, run);
  writeQueue = next.catch(() => undefined);
  return next;
}

async function persist(store: KnowledgeStore): Promise<void> {
  await mkdir(DATA_DIR, { recursive: true });
  const tmp = `${STORE_PATH}.${process.pid}.tmp`;
  await writeFile(tmp, JSON.stringify(store), 'utf8');
  await rename(tmp, STORE_PATH);
  cache = null; // force a re-read; the mtime we'd cache here may be stale
}

// ---------------------------------------------------------------------------
// Operations
// ---------------------------------------------------------------------------

export async function listDocs(): Promise<KnowledgeDoc[]> {
  const store = await readStore();
  return [...store.docs].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt));
}

export async function allChunks(): Promise<{ chunks: KnowledgeChunk[]; docs: KnowledgeDoc[] }> {
  const store = await readStore();
  return { chunks: store.chunks, docs: store.docs };
}

export async function addDoc(doc: KnowledgeDoc, chunks: KnowledgeChunk[]): Promise<KnowledgeDoc> {
  return mutateStore((store) => {
    // Re-uploading the same filename replaces the old version instead of
    // stacking a stale copy that competes with it in retrieval.
    const superseded = store.docs.filter(
      (d) => d.filename === doc.filename && d.kind === doc.kind && d.kind !== 'manual'
    );
    const supersededIds = new Set(superseded.map((d) => d.id));

    return {
      store: {
        version: 1,
        docs: [...store.docs.filter((d) => !supersededIds.has(d.id)), doc],
        chunks: [...store.chunks.filter((c) => !supersededIds.has(c.docId)), ...chunks],
      },
      result: doc,
    };
  });
}

export async function removeDoc(docId: string): Promise<boolean> {
  return mutateStore((store) => {
    const exists = store.docs.some((d) => d.id === docId);
    return {
      store: {
        version: 1,
        docs: store.docs.filter((d) => d.id !== docId),
        chunks: store.chunks.filter((c) => c.docId !== docId),
      },
      result: exists,
    };
  });
}

/** Attaches freshly computed embeddings to existing chunks, by chunk id. */
export async function applyEmbeddings(
  vectors: Map<string, number[]>,
  model: string
): Promise<number> {
  return mutateStore((store) => {
    let updated = 0;
    const chunks = store.chunks.map((c) => {
      const vector = vectors.get(c.id);
      if (!vector) return c;
      updated++;
      return { ...c, embedding: vector };
    });

    const touchedDocs = new Set(
      store.chunks.filter((c) => vectors.has(c.id)).map((c) => c.docId)
    );
    const docs = store.docs.map((d) =>
      touchedDocs.has(d.id) ? { ...d, embeddingModel: model } : d
    );

    return { store: { version: 1, docs, chunks }, result: updated };
  });
}

export interface StoreStats {
  docCount: number;
  chunkCount: number;
  embeddedChunks: number;
  /** Chunks that are indexed lexically but have no vector yet. */
  pendingChunks: number;
  bytes: number;
}

export async function getStats(): Promise<StoreStats> {
  const store = await readStore();
  const embedded = store.chunks.filter((c) => c.embedding && c.embedding.length > 0).length;
  return {
    docCount: store.docs.length,
    chunkCount: store.chunks.length,
    embeddedChunks: embedded,
    pendingChunks: store.chunks.length - embedded,
    bytes: store.docs.reduce((sum, d) => sum + d.bytes, 0),
  };
}
